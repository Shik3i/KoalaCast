package net.koalastuff.koalacast.core.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.db.FavoriteDao
import net.koalastuff.koalacast.core.data.db.FavoriteEntity
import net.koalastuff.koalacast.core.data.db.KoalaCastDatabase
import net.koalastuff.koalacast.core.data.db.ListeningSessionDao
import net.koalastuff.koalacast.core.data.db.ListeningSessionEntity
import net.koalastuff.koalacast.core.data.db.PlaybackStateDao
import net.koalastuff.koalacast.core.data.db.PlaybackStateEntity
import net.koalastuff.koalacast.core.data.db.PodcastSettingsDao
import net.koalastuff.koalacast.core.data.db.PodcastSettingsEntity
import net.koalastuff.koalacast.core.data.db.QueueDao
import net.koalastuff.koalacast.core.data.db.QueueItemEntity
import net.koalastuff.koalacast.core.data.db.SubscriptionDao
import net.koalastuff.koalacast.core.data.db.SubscriptionEntity
import net.koalastuff.koalacast.core.data.db.TombstoneDao
import net.koalastuff.koalacast.core.data.db.TombstoneEntity
import net.koalastuff.koalacast.core.model.SyncStatus
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.DownloadRetention
import net.koalastuff.koalacast.core.model.HiddenPodcast
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.PaletteId
import net.koalastuff.koalacast.core.model.StartScreen
import net.koalastuff.koalacast.core.model.ThemeMode
import net.koalastuff.koalacast.core.model.VisualizerStyle
import net.koalastuff.koalacast.core.model.UserPreferences
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.network.KoalaCastApi
import net.koalastuff.koalacast.core.network.apiCall
import net.koalastuff.koalacast.core.network.dto.DeleteAccountRequest
import net.koalastuff.koalacast.core.network.dto.SyncChangesetDto
import net.koalastuff.koalacast.core.network.dto.SyncOperationDto
import net.koalastuff.koalacast.core.network.dto.SyncPushRequest
import net.koalastuff.koalacast.core.network.dto.SyncSnapshotResponse
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepository @Inject constructor(
    private val api: KoalaCastApi,
    private val store: SecureAccountStore,
    private val accountData: AccountDataNamespace,
    private val database: KoalaCastDatabase,
    private val subscriptions: SubscriptionDao,
    private val favorites: FavoriteDao,
    private val playbackStates: PlaybackStateDao,
    private val listeningSessions: ListeningSessionDao,
    private val tombstones: TombstoneDao,
    private val queue: QueueDao,
    private val podcastSettings: PodcastSettingsDao,
    private val preferences: PreferencesRepository,
    private val downloads: DownloadRepository,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(if (store.account.value == null) SyncStatus.OFF else SyncStatus.IDLE)
    val status: StateFlow<SyncStatus> = _status
    private val _lastSyncedAt = MutableStateFlow<Long?>(null)
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt

    /**
     * Why the last sync failed, or null after one succeeds.
     *
     * Every failure used to be swallowed by a bare `catch (_: Exception)` that
     * set [SyncStatus.ERROR] and nothing else. A listener whose data never
     * reached the server saw a red dot and no reason, and neither a bug report
     * nor a log could say whether it was the network, a rejected operation or an
     * expired session. The message is deliberately the exception's own text: it
     * is a diagnostic, not a translated user-facing string.
     */
    private val _lastSyncError = MutableStateFlow<String?>(null)
    val lastSyncError: StateFlow<String?> = _lastSyncError

    suspend fun syncNow(): Boolean = mutex.withLock {
        val account = store.account.value ?: run {
            _status.value = SyncStatus.OFF
            return@withLock false
        }
        val generation = store.accountGeneration()
        _status.value = SyncStatus.SYNCING
        try {
            pull(account.userId, generation)
            val rejected = push(account.userId, account.deviceId, generation)
            _lastSyncedAt.value = System.currentTimeMillis()
            // A rejection is not a failed sync — the rest went through — but it
            // must still be said out loud, or the data silently goes missing.
            _lastSyncError.value = if (rejected.isEmpty()) {
                null
            } else {
                "${rejected.size} rejected, rest synced. First: ${rejected.first()}"
            }
            _status.value = SyncStatus.IDLE
            true
        } catch (error: AuthExpired) {
            store.beginAccountTransition()
            accountData.switchTo(AccountDataNamespace.GUEST_OWNER)
            store.clear()
            _status.value = SyncStatus.OFF
            false
        } catch (_: AccountChanged) {
            _status.value = if (store.account.value == null) SyncStatus.OFF else SyncStatus.IDLE
            false
        } catch (error: Exception) {
            android.util.Log.w("KoalaCastSync", "sync failed", error)
            _lastSyncError.value = buildString {
                append(error::class.simpleName ?: "Exception")
                error.message?.let { append(": ").append(it) }
                // Retrofit wraps the useful half; without the cause the message
                // can name a symptom and never the type that actually failed.
                error.cause?.let { cause ->
                    append(" — caused by ")
                    append(cause::class.simpleName ?: "cause")
                    cause.message?.let { append(": ").append(it) }
                }
            }
            _status.value = SyncStatus.ERROR
            false
        }
    }

    fun signedOut() {
        _status.value = SyncStatus.OFF
    }

    suspend fun deleteSynchronizedData(credential: String): DataResult<Unit> = mutex.withLock {
        val account = store.account.value
            ?: return@withLock DataResult.Failure(DataError.Http(401, "unauthorized"))
        val trimmed = credential.trim()
        if (trimmed.isBlank()) {
            return@withLock DataResult.Failure(DataError.Malformed("credential required"))
        }
        val request = DeleteAccountRequest(password = trimmed, recoveryCode = trimmed)
        when (val result = apiCall { api.deleteSynchronizedData(request) }) {
            is DataResult.Failure -> result
            is DataResult.Success -> {
                if (result.data.dataGeneration <= store.dataGeneration(account.userId)) {
                    DataResult.Failure(DataError.Malformed("server data generation did not advance"))
                } else {
                    try {
                        resetLocalData(account.userId, result.data.dataGeneration)
                        _lastSyncedAt.value = System.currentTimeMillis()
                        _lastSyncError.value = null
                        _status.value = SyncStatus.IDLE
                        DataResult.Success(Unit)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // The server reset has committed. Keeping the old local
                        // generation guarantees that the next contact retries the
                        // wipe before any push instead of treating this copy as new.
                        DataResult.Failure(
                            DataError.Malformed(
                                "LOCAL_RESET:${error.message ?: error.javaClass.simpleName}",
                            ),
                        )
                    }
                }
            }
        }
    }

    internal suspend fun pull(
        userId: String,
        generation: Long = store.accountGeneration(),
    ) {
        var cursor = store.cursor(userId)
        var recoveredFromSnapshot = false
        while (true) {
            val response = api.pullSync(cursor, PAGE_LIMIT)
            ensureGeneration(generation)
            when (response.code()) {
                401 -> throw AuthExpired()
                410 -> {
                    if (recoveredFromSnapshot) {
                        throw IOException("sync pull returned 410 after snapshot recovery")
                    }
                    val snapshotResponse = api.syncSnapshot()
                    ensureGeneration(generation)
                    if (snapshotResponse.code() == 401) throw AuthExpired()
                    if (!snapshotResponse.isSuccessful) {
                        throw IOException("sync snapshot failed: ${snapshotResponse.code()}")
                    }
                    val snapshot = snapshotResponse.body()
                        ?: throw IOException("sync snapshot returned no body")
                    verifyServerGeneration(userId, snapshot.dataGeneration)
                    applySnapshot(snapshot, userId, generation)
                    cursor = snapshot.cursor.coerceAtLeast(0)
                    store.setCursor(userId, cursor)
                    recoveredFromSnapshot = true
                    continue
                }
            }
            if (!response.isSuccessful) throw IOException("sync pull failed: ${response.code()}")
            val body = response.body() ?: throw IOException("sync pull returned no body")
            verifyServerGeneration(userId, body.dataGeneration)
            body.changesets.forEach { apply(it, generation) }
            val nextCursor = body.nextCursor
                ?: body.changesets.lastOrNull()?.serverCursor
                ?: maxOf(cursor, body.currentCursor)
            val hasMore = body.hasMore ?: (body.changesets.size >= PAGE_LIMIT)
            if (hasMore && nextCursor <= cursor) {
                throw IOException("sync pull made no cursor progress")
            }
            cursor = nextCursor.coerceAtLeast(cursor)
            store.setCursor(userId, cursor)
            if (!hasMore) return
        }
    }

    private suspend fun applySnapshot(
        snapshot: SyncSnapshotResponse,
        userId: String,
        generation: Long,
    ) {
        if (snapshot.cursor < 0 ||
            snapshot.subscriptions.any {
                it.string("podcast_id").isBlank() || it.long("added_at") <= 0
            } ||
            snapshot.favorites.any {
                it.string("episode_id").isBlank() || it.long("added_at") <= 0
            } ||
            snapshot.playbackStates.any {
                it.string("episode_id").isBlank() || it.long("last_played_at") < 0
            } ||
            snapshot.listeningSessions.any {
                it.string("id").isBlank() || it.long("ended_at") <= 0
            }
        ) {
            throw IOException("sync snapshot contains invalid records")
        }
        val subscriptionWatermark = store.pushWatermark(userId, "subscription")
        val favoriteWatermark = store.pushWatermark(userId, "favorite")
        database.withTransaction {
            ensureGeneration(generation)
            val localFolders = subscriptions.getAll().associate { it.podcastId to it.folder }
            val pendingSubscriptions = subscriptions.getAll().filter { it.updatedAt > subscriptionWatermark }
            val pendingFavorites = favorites.getAll().filter { it.addedAt > favoriteWatermark }
            val pendingTombstones = tombstones.getAll().filter {
                it.deletedAt > store.pushWatermark(userId, it.entityType)
            }
            subscriptions.clear()
            favorites.clear()
            queue.clear()
            podcastSettings.clear()
            tombstones.clear()

            snapshot.subscriptions.forEach { payload ->
                val podcastId = payload.string("podcast_id").ifBlank { payload.string("id") }
                if (podcastId.isBlank()) return@forEach
                subscriptions.upsert(
                    SubscriptionEntity(
                        podcastId = podcastId,
                        feedUrl = payload.string("feed_url"),
                        title = payload.string("title").ifBlank { "Podcast" },
                        artworkUrl = payload.string("artwork_url"),
                        addedAt = payload.long("added_at").takeIf { it > 0 }
                            ?: payload.long("created_at").takeIf { it > 0 }
                            ?: System.currentTimeMillis(),
                        updatedAt = payload.long("updated_at").takeIf { it > 0 }
                            ?: payload.long("added_at").takeIf { it > 0 }
                            ?: System.currentTimeMillis(),
                        inboxMode = payload.string("inbox_mode")
                            .takeIf { it == SubscriptionEntity.INBOX_MODE_LATEST }
                            ?: SubscriptionEntity.INBOX_MODE_ALL,
                        folder = payload.string("folder").ifBlank {
                            localFolders[podcastId].orEmpty()
                        },
                    ),
                )
            }
            snapshot.favorites.forEach { payload ->
                val episodeId = payload.string("episode_id").ifBlank { payload.string("id") }
                if (episodeId.isBlank()) return@forEach
                favorites.upsert(
                    FavoriteEntity(
                        episodeId = episodeId,
                        addedAt = payload.long("added_at").takeIf { it > 0 }
                            ?: payload.long("created_at").takeIf { it > 0 }
                            ?: System.currentTimeMillis(),
                        podcastId = payload.string("podcast_id").ifBlank { null },
                        title = payload.string("title").ifBlank { null },
                        podcastTitle = payload.string("podcast_title").ifBlank { null },
                        artworkUrl = payload.string("artwork_url").ifBlank { null },
                        enclosureUrl = payload.string("enclosure_url").ifBlank { null },
                        durationMs = payload.longOrNull("duration_ms"),
                        categories = payload.strings("categories"),
                        explicit = payload.booleanOrNull("explicit"),
                    ),
                )
            }
            // A snapshot is authoritative only up to the last successful push.
            // Retain newer local mutations that have not reached the server yet.
            pendingSubscriptions.forEach { subscriptions.upsert(it) }
            pendingFavorites.forEach { favorites.upsert(it) }
            pendingTombstones.forEach { item ->
                tombstones.upsert(item)
                when (item.entityType) {
                    TombstoneEntity.TYPE_SUBSCRIPTION -> subscriptions.delete(item.entityId)
                    TombstoneEntity.TYPE_FAVORITE -> favorites.delete(item.entityId)
                }
            }
            snapshot.playbackStates.forEach { payload ->
                val episodeId = payload.string("episode_id").ifBlank { payload.string("id") }
                if (episodeId.isBlank()) return@forEach
                val incomingAt = payload.long("last_played_at")
                val existing = playbackStates.get(episodeId)
                if (existing == null || incomingAt > existing.lastPlayedAt) {
                    playbackStates.upsert(playbackEntity(episodeId, payload, incomingAt))
                }
            }
            snapshot.listeningSessions.forEach { payload ->
                val id = payload.string("id")
                if (id.isBlank()) return@forEach
                val incomingAt = payload.long("ended_at")
                val existing = listeningSessions.get(id)
                if (existing == null || incomingAt > existing.endedAt) {
                    listeningSessions.upsert(listeningEntity(id, payload, incomingAt))
                }
            }
            store.resetQueueUpdatedAt()
            snapshot.queue.forEach { applyQueue(it, authoritative = true) }
            snapshot.podcastSettings.forEach { applyPodcastSettings(it, authoritative = true) }
        }
        ensureGeneration(generation)
        preferences.resetSynced()
        snapshot.settings.forEach {
            ensureGeneration(generation)
            applySettings(it, authoritative = true)
        }
    }

    internal suspend fun push(userId: String, deviceId: String, generation: Long): List<String> {
        ensureGeneration(generation)
        val previousWatermarks = GENERAL_SYNC_ENTITIES.associateWith {
            store.pushWatermark(userId, it)
        }
        val previousListeningWatermark = store.listeningSessionPushWatermark(userId)
        val operations = pendingSyncOperations(
            operations = buildOperations(deviceId, listeningAfter = previousListeningWatermark),
            generalWatermarks = previousWatermarks,
            listeningWatermark = previousListeningWatermark,
        )
        // The newest session actually in this push, not the clock. Sessions are
        // written asynchronously when playback stops, so one that ended a moment
        // ago can land in the database after the query above has run — and a
        // wall-clock watermark would then be past its `endedAt`, dropping it
        // permanently. Advancing only to what was sent means the worst case is
        // sending a session twice, which the server treats idempotently.
        val nextListeningWatermark = operations
            .filter { it.entityType == "listening_session" }
            .maxOfOrNull { it.clientTimestamp }
            ?: previousListeningWatermark
        ensureGeneration(generation)
        val rejected = mutableListOf<String>()
        operations.chunked(PUSH_BATCH).forEach { batch ->
            pushBatch(userId, batch, generation, rejected)
        }

        val nextWatermarks = previousWatermarks.toMutableMap()
        operations.filter { it.entityType != "listening_session" }.forEach { operation ->
            nextWatermarks[operation.entityType] = maxOf(
                nextWatermarks[operation.entityType] ?: 0,
                operation.clientTimestamp,
            )
        }
        store.setPushWatermarks(userId, nextWatermarks)
        store.setListeningSessionPushWatermark(userId, nextListeningWatermark)
        // Cursor deliberately stays unchanged. The next pull re-reads our own
        // idempotent operations so a concurrent device cannot be skipped.
        return rejected
    }

    /**
     * Sends one batch, and refuses to let a single bad record stop everything.
     *
     * A 400 used to abort the whole sync, which meant the watermark never moved
     * and the next attempt sent the same rejected operation again — forever. One
     * unacceptable row could therefore keep an entire account's data off the
     * server permanently, which is precisely what happened. On a rejection the
     * batch is halved and retried until the offending operation is alone; that
     * one is recorded and skipped, and everything else goes through.
     *
     * The server's own explanation is kept. Reporting only the status code threw
     * away the one sentence that says which operation was wrong and why.
     */
    private suspend fun pushBatch(
        userId: String,
        batch: List<SyncOperationDto>,
        generation: Long,
        rejected: MutableList<String>,
    ) {
        if (batch.isEmpty()) return
        ensureGeneration(generation)
        val response = api.pushSync(
            SyncPushRequest(
                operations = batch,
                dataGeneration = store.dataGeneration(userId),
            ),
        )
        ensureGeneration(generation)
        if (response.code() == 401) throw AuthExpired()
        if (response.isSuccessful) return

        val detail = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty().take(300)
        if (response.code() == 409) {
            val conflictGeneration = runCatching {
                Json.parseToJsonElement(detail).jsonObject["data_generation"]
                    ?.jsonPrimitive?.longOrNull
            }.getOrNull() ?: throw IOException("sync generation conflict omitted generation")
            verifyServerGeneration(userId, conflictGeneration)
            throw IOException("sync generation conflict was not newer")
        }
        // Only a rejection of the *content* is worth isolating. A 500 or a 429 is
        // about the request as a whole and must still fail the sync so it retries.
        if (response.code() != 400) {
            throw IOException("sync push failed: ${response.code()} $detail".trim())
        }
        if (batch.size == 1) {
            val op = batch.first()
            android.util.Log.w("KoalaCastSync", "dropping ${op.entityType}/${op.entityId}: $detail")
            rejected += "${op.entityType} ${op.entityId}: $detail"
            return
        }
        val half = batch.size / 2
        pushBatch(userId, batch.subList(0, half), generation, rejected)
        pushBatch(userId, batch.subList(half, batch.size), generation, rejected)
    }

    internal suspend fun buildOperations(
        deviceId: String,
        listeningAfter: Long? = null,
    ): List<SyncOperationDto> {
        val operations = mutableListOf<SyncOperationDto>()
        subscriptions.getAll().forEach { item ->
            if (item.podcastId == item.feedUrl) return@forEach
            operations += operation(
                id = "s:${item.podcastId}:${item.updatedAt}",
                deviceId = deviceId,
                type = "subscription",
                entityId = item.podcastId,
                timestamp = item.updatedAt,
                payload = subscriptionPayload(item),
            )
        }
        favorites.getAll().forEach { item ->
            operations += operation(
                id = "f:${item.episodeId}:${item.addedAt}",
                deviceId = deviceId,
                type = "favorite",
                entityId = item.episodeId,
                timestamp = item.addedAt,
                payload = favoritePayload(item),
            )
        }
        playbackStates.getAll().forEach { item ->
            operations += operation(
                id = "p:${item.episodeId}:${item.lastPlayedAt}",
                deviceId = deviceId,
                type = "playback_state",
                entityId = item.episodeId,
                timestamp = item.lastPlayedAt,
                payload = playbackPayload(item, deviceId),
            )
        }
        val sessions = listeningAfter?.let { listeningSessions.getEndedAfter(it) }
            ?: listeningSessions.getAll()
        sessions.forEach { item ->
            operations += operation(
                id = "l:${item.id}:${item.endedAt}",
                deviceId = deviceId,
                type = "listening_session",
                entityId = item.id,
                timestamp = item.endedAt,
                payload = listeningPayload(item),
            )
        }
        val queueUpdatedAt = store.queueUpdatedAt()
        if (queueUpdatedAt > 0) {
            operations += operation(
                id = "q:main:$queueUpdatedAt",
                deviceId = deviceId,
                type = "queue",
                entityId = "main",
                timestamp = queueUpdatedAt,
                payload = queuePayload(queue.getAll(), queueUpdatedAt),
            )
        }
        podcastSettings.getAll().filter { it.updatedAt > 0 }.forEach { item ->
            operations += operation(
                id = "ps:${item.podcastId}:${item.updatedAt}",
                deviceId = deviceId,
                type = "podcast_settings",
                entityId = item.podcastId,
                timestamp = item.updatedAt,
                payload = podcastSettingsPayload(item),
            )
        }
        val (settings, settingsUpdatedAt) = preferences.syncSnapshot()
        if (settingsUpdatedAt > 0) {
            operations += operation(
                id = "g:global:$settingsUpdatedAt",
                deviceId = deviceId,
                type = "settings",
                entityId = "global",
                timestamp = settingsUpdatedAt,
                payload = SyncedSettings.merge(
                    owned = settingsPayload(
                        settings,
                        settingsUpdatedAt,
                        preferences.settingsFieldTimestamps(),
                    ),
                    foreign = foreignSettings(),
                ),
            )
        }
        tombstones.getAll()
            .filter { it.entityType == "subscription" || it.entityType == "favorite" }
            .forEach { item ->
                operations += operation(
                    id = "d:${item.entityType}:${item.entityId}:${item.deletedAt}",
                    deviceId = deviceId,
                    type = item.entityType,
                    action = "delete",
                    entityId = item.entityId,
                    timestamp = item.deletedAt,
                    payload = JsonObject(emptyMap()),
                )
            }
        return operations
    }

    private suspend fun apply(change: SyncChangesetDto, generation: Long) {
        val payload = change.payload as? JsonObject ?: JsonObject(emptyMap())
        database.withTransaction {
            ensureGeneration(generation)
            when (change.entityType) {
                "subscription" -> applySubscription(change, payload)
                "favorite" -> applyFavorite(change, payload)
                "playback_state" -> applyPlayback(change, payload)
                "listening_session" -> applyListening(change, payload)
                "queue" -> if (change.action != "delete") applyQueue(payload)
                "podcast_settings" -> if (change.action != "delete") {
                    applyPodcastSettings(payload, change.entityId)
                }
            }
        }
        ensureGeneration(generation)
        if (change.entityType == "settings" && change.action != "delete") applySettings(payload)
    }

    private suspend fun applySubscription(change: SyncChangesetDto, payload: JsonObject) {
        if (change.action == "delete") {
            subscriptions.delete(change.entityId)
            tombstones.delete("subscription:${change.entityId}")
            return
        }
        // Pull happens before push. A local deletion that is still pending must
        // not be erased by an older remote upsert encountered during that pull.
        if (tombstones.get("subscription:${change.entityId}") != null) return
        val podcastId = payload.string("podcast_id").ifBlank { change.entityId }
        val localFolder = subscriptions.get(podcastId)?.folder.orEmpty()
        subscriptions.upsert(
            SubscriptionEntity(
                podcastId = podcastId,
                feedUrl = payload.string("feed_url"),
                title = payload.string("title").ifBlank { "Podcast" },
                artworkUrl = payload.string("artwork_url"),
                addedAt = payload.long("added_at").takeIf { it > 0 }
                    ?: change.clientTimestamp.takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
                // Without this the entity default silently rewrote updatedAt to
                // addedAt, so a folder or inbox-mode edit made elsewhere landed
                // here dated to the day the show was subscribed.
                updatedAt = payload.long("updated_at").takeIf { it > 0 }
                    ?: payload.long("added_at").takeIf { it > 0 }
                    ?: change.clientTimestamp.takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
                inboxMode = payload.string("inbox_mode")
                    .takeIf { it == SubscriptionEntity.INBOX_MODE_LATEST }
                    ?: SubscriptionEntity.INBOX_MODE_ALL,
                folder = payload.string("folder").ifBlank { localFolder },
            ),
        )
        tombstones.delete("subscription:${change.entityId}")
    }

    private suspend fun applyFavorite(change: SyncChangesetDto, payload: JsonObject) {
        if (change.action == "delete") {
            favorites.delete(change.entityId)
            tombstones.delete("favorite:${change.entityId}")
            return
        }
        if (tombstones.get("favorite:${change.entityId}") != null) return
        favorites.upsert(
            FavoriteEntity(
                episodeId = payload.string("episode_id").ifBlank { change.entityId },
                addedAt = payload.long("added_at").takeIf { it > 0 }
                    ?: change.clientTimestamp.takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
                podcastId = payload.string("podcast_id").ifBlank { null },
                title = payload.string("title").ifBlank { null },
                podcastTitle = payload.string("podcast_title").ifBlank { null },
                artworkUrl = payload.string("artwork_url").ifBlank { null },
                enclosureUrl = payload.string("enclosure_url").ifBlank { null },
                durationMs = payload.longOrNull("duration_ms"),
                categories = payload.strings("categories"),
                explicit = payload.booleanOrNull("explicit"),
            ),
        )
        tombstones.delete("favorite:${change.entityId}")
    }

    private suspend fun applyPlayback(change: SyncChangesetDto, payload: JsonObject) {
        if (change.action == "delete") return
        val episodeId = payload.string("episode_id").ifBlank { change.entityId }
        val incomingAt = payload.long("last_played_at")
            .takeIf { it > 0 }
            ?: payload.long("client_timestamp").takeIf { it > 0 }
            ?: change.clientTimestamp
        val existing = playbackStates.get(episodeId)
        if (existing != null && existing.lastPlayedAt >= incomingAt) return
        playbackStates.upsert(playbackEntity(episodeId, payload, incomingAt))
    }

    private suspend fun applyListening(change: SyncChangesetDto, payload: JsonObject) {
        if (change.action == "delete") return
        val id = payload.string("id").ifBlank { change.entityId }
        val incomingAt = payload.long("ended_at").takeIf { it > 0 } ?: change.clientTimestamp
        val existing = listeningSessions.get(id)
        if (existing != null && existing.endedAt >= incomingAt) return
        listeningSessions.upsert(listeningEntity(id, payload, incomingAt))
    }

    private suspend fun applyQueue(payload: JsonObject, authoritative: Boolean = false) {
        val updatedAt = payload.long("updated_at")
        if (!authoritative && updatedAt <= store.queueUpdatedAt()) return
        val items = payload["items"] as? JsonArray ?: return
        if (!authoritative) queue.clear()
        items.take(500).forEachIndexed { index, element ->
            val item = element as? JsonObject ?: return@forEachIndexed
            val episodeId = item.string("episode_id")
            if (episodeId.isBlank()) return@forEachIndexed
            queue.insert(
                QueueItemEntity(
                    id = item.string("id").ifBlank { "sync-$episodeId" },
                    episodeId = episodeId,
                    podcastId = item.string("podcast_id"),
                    title = item.string("title").ifBlank { "Episode" },
                    podcastTitle = item.string("podcast_title"),
                    artworkUrl = item.string("artwork_url"),
                    enclosureUrl = item.string("enclosure_url"),
                    durationMs = item.long("duration_ms"),
                    positionOrder = item.longOrNull("position_order") ?: index.toLong(),
                    addedAt = item.long("added_at").takeIf { it > 0 } ?: updatedAt,
                    categories = item.strings("categories"),
                    explicit = item.booleanOrNull("explicit"),
                ),
            )
        }
        store.markQueueUpdated(updatedAt)
    }

    private suspend fun applyPodcastSettings(
        payload: JsonObject,
        fallbackId: String = "",
        authoritative: Boolean = false,
    ) {
        val podcastId = payload.string("podcast_id").ifBlank { fallbackId }
        val updatedAt = payload.long("updated_at")
        if (podcastId.isBlank() || updatedAt <= 0) return
        if (!authoritative && (podcastSettings.get(podcastId)?.updatedAt ?: 0) >= updatedAt) return
        podcastSettings.upsert(
            PodcastSettingsEntity(
                podcastId = podcastId,
                skipIntroSeconds = payload.intEither("skip_intro_seconds", "skipIntroSeconds")
                    .coerceIn(0, 600),
                skipOutroSeconds = payload.intEither("skip_outro_seconds", "skipOutroSeconds")
                    .coerceIn(0, 600),
                speed = payload["speed"]?.jsonPrimitive?.floatOrNull,
                volumeBoost = payload.booleanOrNull("volume_boost", "volumeBoost"),
                skipSilence = payload.booleanOrNull("skip_silence", "skipSilence"),
                autoQueueNew = payload.booleanEither("auto_queue_new", "autoQueueNew"),
                notifyNewEpisodes = payload.booleanEither(
                    "notify_new_episodes",
                    "notifyNewEpisodes",
                ),
                autoDownload = payload.booleanEither("auto_download", "autoDownload"),
                updatedAt = updatedAt,
            ),
        )
    }

    private suspend fun applySettings(payload: JsonObject, authoritative: Boolean = false) {
        val updatedAt = payload.long("updated_at")
        if (updatedAt <= 0) return
        val (current, localUpdatedAt) = preferences.syncSnapshot()
        // No blob-level early return any more. Rejecting a payload whose
        // `updated_at` is older than ours threw away every field it carried,
        // including the one the other device had just changed and we had not.
        // Each field is weighed on its own timestamp instead.
        val localStamps = preferences.settingsFieldTimestamps()
        val accepted = SyncedSettings.decide(
            incoming = SyncedSettings.parseTimestamps(payload),
            incomingUpdatedAt = updatedAt,
            local = localStamps,
            localUpdatedAt = localUpdatedAt,
            authoritative = authoritative,
        )
        if (accepted.isEmpty() && updatedAt <= localUpdatedAt) return
        fun wins(field: String) = field in accepted

        // Keep whatever this payload carries for other clients, so the next push
        // from this device hands it back rather than dropping it. Merged rather
        // than replaced: a payload that loses every field of ours may still be the
        // only carrier of a key neither client understands.
        val foreign = SyncedSettings.merge(
            owned = SyncedSettings.foreignOf(payload),
            foreign = runCatching {
                Json.parseToJsonElement(preferences.foreignSettings()) as? JsonObject
            }.getOrNull() ?: JsonObject(emptyMap()),
        )
        preferences.setForeignSettings(if (foreign.isEmpty()) "" else foreign.toString())
        preferences.setSettingsFieldTimestamps(localStamps + accepted)

        preferences.applySynced(
            current.copy(
                themeMode = if (!wins("theme_mode")) current.themeMode else payload.string("theme_mode").ifBlank {
                    payload.string("theme")
                }.let { runCatching { ThemeMode.valueOf(it.uppercase()) }.getOrNull() }
                    ?: current.themeMode,
                palette = payload.string("palette").takeIf { wins("palette") && it.isNotBlank() }
                    ?.let(PaletteId::fromId) ?: current.palette,
                languages = payload.strings("languages").toSet()
                    .takeIf { wins("languages") && it.isNotEmpty() } ?: current.languages,
                interests = if (wins("interests") && "interests" in payload) {
                    payload.strings("interests").toSet()
                } else {
                    current.interests
                },
                hiddenGenres = if (wins("hidden_genres") && "hidden_genres" in payload) {
                    payload.strings("hidden_genres").toSet()
                } else {
                    current.hiddenGenres
                },
                hiddenPodcasts = if (wins("hidden_podcasts") && "hidden_podcasts" in payload) {
                    payload.hiddenPodcasts()
                } else {
                    current.hiddenPodcasts
                },
                defaultInboxMode = when (payload.string("default_inbox_mode").takeIf { wins("default_inbox_mode") }) {
                    InboxMode.LATEST.name.lowercase() -> InboxMode.LATEST
                    InboxMode.ALL.name.lowercase() -> InboxMode.ALL
                    else -> current.defaultInboxMode
                },
                startScreen = payload.string("start_screen").takeIf { wins("start_screen") && it.isNotBlank() }
                    ?.let(StartScreen::fromId) ?: current.startScreen,
                visualizer = payload.string("visualizer").takeIf { wins("visualizer") && it.isNotBlank() }
                    ?.let(VisualizerStyle::fromId) ?: current.visualizer,
                proxyImages = if (wins("proxy_images")) payload.booleanOr("proxy_images", current.proxyImages) else current.proxyImages,
                playbackSpeed = if (wins("playback_speed")) payload.floatOr("playback_speed", current.playbackSpeed) else current.playbackSpeed,
                downloadWifiOnly = if (wins("download_wifi_only")) {
                    payload.booleanOr("download_wifi_only", current.downloadWifiOnly)
                } else {
                    current.downloadWifiOnly
                },
                skipSilence = if (wins("skip_silence")) payload.booleanOr("skip_silence", current.skipSilence) else current.skipSilence,
                volumeBoost = if (wins("volume_boost")) payload.booleanOr("volume_boost", current.volumeBoost) else current.volumeBoost,
                autoDownloadCount = if (wins("auto_download_count")) {
                    payload.intOr("auto_download_count", current.autoDownloadCount)
                } else {
                    current.autoDownloadCount
                },
                downloadRetention = payload.string("download_retention")
                    .takeIf { wins("download_retention") && it.isNotBlank() }
                    ?.let(DownloadRetention::fromId) ?: current.downloadRetention,
                downloadConcurrency = if (wins("download_concurrency")) {
                    payload.intOr("download_concurrency", current.downloadConcurrency)
                } else {
                    current.downloadConcurrency
                },
                downloadBudgetBytes = if (wins("download_budget_bytes") && "download_budget_bytes" in payload) {
                    payload.longOrNull("download_budget_bytes")
                        ?.coerceIn(0L, MAX_SYNCED_DOWNLOAD_BUDGET_BYTES)
                        ?: current.downloadBudgetBytes
                } else {
                    current.downloadBudgetBytes
                },
            ),
            updatedAt,
            // The per-field decision has already been made above; applySynced must
            // not second-guess it with its own blob comparison.
            force = true,
        )
    }

    private fun playbackEntity(
        episodeId: String,
        payload: JsonObject,
        incomingAt: Long,
    ) = PlaybackStateEntity(
        episodeId = episodeId,
        podcastId = payload.string("podcast_id"),
        positionMs = payload.long("position_ms"),
        completed = payload.boolean("completed"),
        progressPercent = payload.int("progress_percent"),
        lastPlayedAt = incomingAt.takeIf { it > 0 }
            ?: payload.long("client_timestamp").takeIf { it > 0 }
            ?: System.currentTimeMillis(),
        title = payload.string("title").ifBlank { null },
        podcastTitle = payload.string("podcast_title").ifBlank { null },
        artworkUrl = payload.string("artwork_url").ifBlank { null },
        enclosureUrl = payload.string("enclosure_url").ifBlank { null },
        durationMs = payload.longOrNull("duration_ms"),
        categories = payload.strings("categories"),
        explicit = payload.booleanOrNull("explicit"),
        eventType = payload.string("event_type").ifBlank { "PROGRESS_TICK" },
        playbackSessionId = payload.string("playback_session_id"),
        perSessionSeq = payload.long("per_session_seq"),
    )

    private fun listeningEntity(
        id: String,
        payload: JsonObject,
        incomingAt: Long,
    ) = ListeningSessionEntity(
        id = id,
        episodeId = payload.string("episode_id"),
        podcastId = payload.string("podcast_id"),
        title = payload.string("title").ifBlank { "Episode" },
        podcastTitle = payload.string("podcast_title").ifBlank { "Podcast" },
        categories = payload.strings("categories"),
        startedAt = payload.long("started_at").takeIf { it > 0 } ?: incomingAt,
        endedAt = incomingAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        wallClockMs = payload.long("wall_clock_ms").coerceAtLeast(0),
        audioListenedMs = payload.long("audio_listened_ms").coerceAtLeast(0),
        speedSavedMs = payload.long("speed_saved_ms").coerceAtLeast(0),
        silenceSavedMs = payload.long("silence_saved_ms").coerceAtLeast(0),
        manualSkippedMs = payload.long("manual_skipped_ms").coerceAtLeast(0),
        introOutroSkippedMs = payload.long("intro_outro_skipped_ms").coerceAtLeast(0),
        speedWeightedMs = payload.long("speed_weighted_ms").coerceAtLeast(0),
    )

    private fun operation(
        id: String,
        deviceId: String,
        type: String,
        entityId: String,
        timestamp: Long,
        payload: JsonElement,
        action: String = "upsert",
    ) = SyncOperationDto(id, deviceId, type, action, entityId, payload, timestamp)

    private fun subscriptionPayload(item: SubscriptionEntity) = buildJsonObject {
        put("podcast_id", item.podcastId)
        put("feed_url", item.feedUrl)
        put("title", item.title)
        put("artwork_url", item.artworkUrl)
        put("added_at", item.addedAt)
        put("updated_at", item.updatedAt)
        put("inbox_mode", item.inboxMode)
        put("folder", item.folder)
    }

    private fun favoritePayload(item: FavoriteEntity) = buildJsonObject {
        put("episode_id", item.episodeId)
        put("added_at", item.addedAt)
        nullable("podcast_id", item.podcastId)
        nullable("title", item.title)
        nullable("podcast_title", item.podcastTitle)
        nullable("artwork_url", item.artworkUrl)
        nullable("enclosure_url", item.enclosureUrl)
        item.durationMs?.let { put("duration_ms", it) }
        put("categories", JsonArray(item.categories.map(::JsonPrimitive)))
        item.explicit?.let { put("explicit", it) }
    }

    private fun playbackPayload(item: PlaybackStateEntity, deviceId: String) = buildJsonObject {
        put("episode_id", item.episodeId)
        put("podcast_id", item.podcastId)
        put("position_ms", item.positionMs)
        put("completed", item.completed)
        put("progress_percent", item.progressPercent)
        put("last_played_at", item.lastPlayedAt)
        nullable("title", item.title)
        nullable("podcast_title", item.podcastTitle)
        nullable("artwork_url", item.artworkUrl)
        nullable("enclosure_url", item.enclosureUrl)
        item.durationMs?.let { put("duration_ms", it) }
        put("categories", JsonArray(item.categories.map(::JsonPrimitive)))
        item.explicit?.let { put("explicit", it) }
        put("event_type", item.eventType)
        put("playback_session_id", item.playbackSessionId.ifBlank { "sync:$deviceId:${item.episodeId}" })
        put("device_id", deviceId)
        put("per_session_seq", item.perSessionSeq.takeIf { it > 0 } ?: item.lastPlayedAt)
        put("client_timestamp", item.lastPlayedAt)
    }

    /**
     * Clamped to the server's own ceilings for a listening session. Past them the
     * push is rejected with a 400, so this is not cosmetic: an unclamped session
     * is a record that can never be uploaded. A session paused and resumed across
     * days reaches the span limit on its own.
     */
    private fun listeningPayload(item: ListeningSessionEntity) = buildJsonObject {
        val endedAt = maxOf(item.endedAt, item.startedAt)
        put("id", item.id)
        put("episode_id", item.episodeId)
        put("podcast_id", item.podcastId)
        put("title", item.title)
        put("podcast_title", item.podcastTitle)
        put("categories", JsonArray(item.categories.map(::JsonPrimitive)))
        // Hold the span, not the end: the end is what every last-writer-wins
        // comparison keys on.
        put("started_at", maxOf(item.startedAt, endedAt - MAX_SESSION_SPAN_MS))
        put("ended_at", endedAt)
        put("wall_clock_ms", item.wallClockMs.coerceIn(0, MAX_SESSION_SPAN_MS))
        put("audio_listened_ms", item.audioListenedMs.coerceIn(0, MAX_SESSION_METRIC_MS))
        put("speed_saved_ms", item.speedSavedMs.coerceIn(0, MAX_SESSION_METRIC_MS))
        put("silence_saved_ms", item.silenceSavedMs.coerceIn(0, MAX_SESSION_METRIC_MS))
        put("manual_skipped_ms", item.manualSkippedMs.coerceIn(0, MAX_SESSION_METRIC_MS))
        put("intro_outro_skipped_ms", item.introOutroSkippedMs.coerceIn(0, MAX_SESSION_METRIC_MS))
        put("speed_weighted_ms", item.speedWeightedMs.coerceIn(0, MAX_SESSION_METRIC_MS))
    }

    private fun queuePayload(items: List<QueueItemEntity>, updatedAt: Long) = buildJsonObject {
        put(
            "items",
            JsonArray(items.map { item ->
                buildJsonObject {
                    put("id", item.id)
                    put("episode_id", item.episodeId)
                    put("podcast_id", item.podcastId)
                    put("title", item.title)
                    put("podcast_title", item.podcastTitle)
                    put("artwork_url", item.artworkUrl)
                    put("enclosure_url", item.enclosureUrl)
                    put("duration_ms", item.durationMs)
                    put("position_order", item.positionOrder)
                    put("added_at", item.addedAt)
                    put("categories", JsonArray(item.categories.map(::JsonPrimitive)))
                    item.explicit?.let { put("explicit", it) }
                }
            }),
        )
        put("updated_at", updatedAt)
    }

    private fun podcastSettingsPayload(item: PodcastSettingsEntity) = buildJsonObject {
        put("podcast_id", item.podcastId)
        put("skip_intro_seconds", item.skipIntroSeconds)
        put("skip_outro_seconds", item.skipOutroSeconds)
        item.speed?.let { put("speed", it) }
        item.volumeBoost?.let { put("volume_boost", it) }
        item.skipSilence?.let { put("skip_silence", it) }
        put("auto_queue_new", item.autoQueueNew)
        put("notify_new_episodes", item.notifyNewEpisodes)
        put("auto_download", item.autoDownload)
        put("updated_at", item.updatedAt)
    }

    /** The other client's keys, as stored by [applySettings]. */
    private suspend fun foreignSettings(): JsonObject =
        runCatching {
            Json.parseToJsonElement(preferences.foreignSettings()) as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())

    private fun settingsPayload(
        item: UserPreferences,
        updatedAt: Long,
        fieldUpdatedAt: Map<String, Long>,
    ) = buildJsonObject {
        // Per-field timestamps, so the other client can merge this payload field by
        // field instead of taking or discarding all of it; see SyncedSettings.
        put(
            SyncedSettings.FIELD_UPDATED_AT,
            JsonObject(fieldUpdatedAt.mapValues { (_, at) -> JsonPrimitive(at) }),
        )
        put("theme_mode", item.themeMode.name.lowercase())
        put("palette", item.palette.id)
        put("languages", JsonArray(item.languages.map(::JsonPrimitive)))
        put("interests", JsonArray(item.interests.map(::JsonPrimitive)))
        put("hidden_genres", JsonArray(item.hiddenGenres.map(::JsonPrimitive)))
        put(
            "hidden_podcasts",
            JsonArray(
                item.hiddenPodcasts.map { podcast ->
                    buildJsonObject {
                        put("key", podcast.key)
                        put("title", podcast.title)
                    }
                },
            ),
        )
        put("default_inbox_mode", item.defaultInboxMode.name.lowercase())
        put("start_screen", item.startScreen.id)
        put("visualizer", item.visualizer.id)
        put("proxy_images", item.proxyImages)
        put("playback_speed", item.playbackSpeed)
        put("download_wifi_only", item.downloadWifiOnly)
        put("skip_silence", item.skipSilence)
        put("volume_boost", item.volumeBoost)
        put("auto_download_count", item.autoDownloadCount)
        put("download_retention", item.downloadRetention.id)
        put("download_concurrency", item.downloadConcurrency)
        put("download_budget_bytes", item.downloadBudgetBytes)
        put("updated_at", updatedAt)
    }

    private fun JsonObject.string(key: String) =
        get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.long(key: String) = get(key)?.jsonPrimitive?.longOrNull ?: 0
    private fun JsonObject.longOrNull(key: String) = get(key)?.jsonPrimitive?.longOrNull
    private fun JsonObject.int(key: String) =
        get(key)?.jsonPrimitive?.intOrNull
            ?: get(key)?.jsonPrimitive?.floatOrNull?.toInt()
            ?: 0
    private fun JsonObject.boolean(key: String) =
        get(key)?.jsonPrimitive?.booleanOrNull ?: false
    private fun JsonObject.booleanOrNull(key: String) =
        get(key)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.booleanEither(first: String, second: String) =
        get(first)?.jsonPrimitive?.booleanOrNull
            ?: get(second)?.jsonPrimitive?.booleanOrNull
            ?: false
    private fun JsonObject.booleanOrNull(first: String, second: String) =
        get(first)?.jsonPrimitive?.booleanOrNull
            ?: get(second)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.booleanOr(key: String, fallback: Boolean) =
        get(key)?.jsonPrimitive?.booleanOrNull ?: fallback
    private fun JsonObject.intEither(first: String, second: String) =
        get(first)?.jsonPrimitive?.intOrNull
            ?: get(second)?.jsonPrimitive?.intOrNull
            ?: 0
    private fun JsonObject.intOr(key: String, fallback: Int) =
        get(key)?.jsonPrimitive?.intOrNull ?: fallback
    private fun JsonObject.floatOr(key: String, fallback: Float) =
        get(key)?.jsonPrimitive?.floatOrNull ?: fallback
    private fun JsonObject.strings(key: String) =
        (get(key) as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    private fun JsonObject.hiddenPodcasts(): Set<HiddenPodcast> =
        (get("hidden_podcasts") as? JsonArray)
            ?.mapNotNull { element ->
                val value = element as? JsonObject ?: return@mapNotNull null
                value.string("key").takeIf(String::isNotBlank)?.let { key ->
                    HiddenPodcast(key = key, title = value.string("title").ifBlank { key })
                }
            }
            ?.toSet()
            .orEmpty()
    private fun kotlinx.serialization.json.JsonObjectBuilder.nullable(key: String, value: String?) {
        if (value != null) put(key, value)
    }

    private fun ensureGeneration(expected: Long) {
        if (store.accountGeneration() != expected) throw AccountChanged()
    }

    private suspend fun verifyServerGeneration(userId: String, serverGeneration: Long) {
        val localGeneration = store.dataGeneration(userId)
        if (serverGeneration < localGeneration) throw IOException("server data generation regressed")
        if (serverGeneration == localGeneration) return
        resetLocalData(userId, serverGeneration)
        // The process generation changed before any pre-reset response can be
        // applied or any locally queued operation can be pushed.
        throw AccountChanged()
    }

    private suspend fun resetLocalData(userId: String, serverGeneration: Long) {
        val ownerId = store.ownerIdFor(userId)
        store.beginAccountTransition()
        downloads.clearAll()
        accountData.resetCurrent(ownerId)
        preferences.resetSynced()
        store.resetSyncState(userId, serverGeneration)
    }

    private class AuthExpired : Exception()
    private class AccountChanged : Exception()

    private companion object {
        const val PAGE_LIMIT = 500
        const val PUSH_BATCH = 250
        const val MAX_SYNCED_DOWNLOAD_BUDGET_BYTES = 10L * 1024 * 1024 * 1024
        const val MAX_SESSION_SPAN_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_SESSION_METRIC_MS = MAX_SESSION_SPAN_MS * 4
        val GENERAL_SYNC_ENTITIES = setOf(
            "subscription",
            "favorite",
            "playback_state",
            "queue",
            "podcast_settings",
            "settings",
        )
    }
}

internal fun pendingSyncOperations(
    operations: List<SyncOperationDto>,
    generalWatermarks: Map<String, Long>,
    listeningWatermark: Long,
): List<SyncOperationDto> = operations.filter { operation ->
    operation.clientTimestamp > if (operation.entityType == "listening_session") {
        listeningWatermark
    } else {
        generalWatermarks[operation.entityType] ?: 0
    }
}
