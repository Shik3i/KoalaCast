package net.koalastuff.koalacast.core.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import net.koalastuff.koalacast.core.data.db.SubscriptionDao
import net.koalastuff.koalacast.core.data.db.SubscriptionEntity
import net.koalastuff.koalacast.core.data.db.TombstoneDao
import net.koalastuff.koalacast.core.model.SyncStatus
import net.koalastuff.koalacast.core.network.KoalaCastApi
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
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(if (store.account.value == null) SyncStatus.OFF else SyncStatus.IDLE)
    val status: StateFlow<SyncStatus> = _status
    private val _lastSyncedAt = MutableStateFlow<Long?>(null)
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt

    suspend fun syncNow(): Boolean = mutex.withLock {
        val account = store.account.value ?: run {
            _status.value = SyncStatus.OFF
            return@withLock false
        }
        _status.value = SyncStatus.SYNCING
        try {
            pull(account.userId)
            push(account.userId, account.deviceId)
            _lastSyncedAt.value = System.currentTimeMillis()
            _status.value = SyncStatus.IDLE
            true
        } catch (error: AuthExpired) {
            accountData.switchTo(null)
            store.clear()
            _status.value = SyncStatus.OFF
            false
        } catch (_: Exception) {
            _status.value = SyncStatus.ERROR
            false
        }
    }

    fun signedOut() {
        _status.value = SyncStatus.OFF
    }

    internal suspend fun pull(userId: String) {
        var cursor = store.cursor(userId)
        var recoveredFromSnapshot = false
        while (true) {
            val response = api.pullSync(cursor, PAGE_LIMIT)
            when (response.code()) {
                401 -> throw AuthExpired()
                410 -> {
                    if (recoveredFromSnapshot) {
                        throw IOException("sync pull returned 410 after snapshot recovery")
                    }
                    val snapshotResponse = api.syncSnapshot()
                    if (snapshotResponse.code() == 401) throw AuthExpired()
                    if (!snapshotResponse.isSuccessful) {
                        throw IOException("sync snapshot failed: ${snapshotResponse.code()}")
                    }
                    val snapshot = snapshotResponse.body()
                        ?: throw IOException("sync snapshot returned no body")
                    applySnapshot(snapshot)
                    cursor = snapshot.cursor.coerceAtLeast(0)
                    store.setCursor(userId, cursor)
                    recoveredFromSnapshot = true
                    continue
                }
            }
            if (!response.isSuccessful) throw IOException("sync pull failed: ${response.code()}")
            val body = response.body() ?: throw IOException("sync pull returned no body")
            body.changesets.forEach { apply(it) }
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

    private suspend fun applySnapshot(snapshot: SyncSnapshotResponse) {
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
        database.withTransaction {
            subscriptions.clear()
            favorites.clear()
            playbackStates.clear()
            listeningSessions.clear()
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
                        inboxMode = payload.string("inbox_mode")
                            .takeIf { it == SubscriptionEntity.INBOX_MODE_LATEST }
                            ?: SubscriptionEntity.INBOX_MODE_ALL,
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
                    ),
                )
            }
            snapshot.playbackStates.forEach { payload ->
                val episodeId = payload.string("episode_id").ifBlank { payload.string("id") }
                if (episodeId.isBlank()) return@forEach
                playbackStates.upsert(playbackEntity(episodeId, payload, payload.long("last_played_at")))
            }
            snapshot.listeningSessions.forEach { payload ->
                val id = payload.string("id")
                if (id.isBlank()) return@forEach
                listeningSessions.upsert(listeningEntity(id, payload, payload.long("ended_at")))
            }
        }
    }

    private suspend fun push(userId: String, deviceId: String) {
        val previousWatermark = store.pushWatermark(userId)
        val nextWatermark = System.currentTimeMillis()
        val operations = buildOperations(deviceId)
            .filter { it.clientTimestamp > previousWatermark }
        operations.chunked(PUSH_BATCH).forEach { batch ->
            val response = api.pushSync(SyncPushRequest(batch))
            if (response.code() == 401) throw AuthExpired()
            if (!response.isSuccessful) throw IOException("sync push failed: ${response.code()}")
        }
        store.setPushWatermark(userId, nextWatermark)
        // Cursor deliberately stays unchanged. The next pull re-reads our own
        // idempotent operations so a concurrent device cannot be skipped.
    }

    internal suspend fun buildOperations(deviceId: String): List<SyncOperationDto> {
        val operations = mutableListOf<SyncOperationDto>()
        subscriptions.getAll().forEach { item ->
            if (item.podcastId == item.feedUrl) return@forEach
            operations += operation(
                id = "s:${item.podcastId}:${item.addedAt}",
                deviceId = deviceId,
                type = "subscription",
                entityId = item.podcastId,
                timestamp = item.addedAt,
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
        listeningSessions.getAll().forEach { item ->
            operations += operation(
                id = "l:${item.id}:${item.endedAt}",
                deviceId = deviceId,
                type = "listening_session",
                entityId = item.id,
                timestamp = item.endedAt,
                payload = listeningPayload(item),
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

    private suspend fun apply(change: SyncChangesetDto) {
        val payload = change.payload as? JsonObject ?: JsonObject(emptyMap())
        database.withTransaction {
            when (change.entityType) {
                "subscription" -> applySubscription(change, payload)
                "favorite" -> applyFavorite(change, payload)
                "playback_state" -> applyPlayback(change, payload)
                "listening_session" -> applyListening(change, payload)
            }
        }
    }

    private suspend fun applySubscription(change: SyncChangesetDto, payload: JsonObject) {
        if (change.action == "delete") {
            subscriptions.delete(change.entityId)
            tombstones.delete("subscription:${change.entityId}")
            return
        }
        subscriptions.upsert(
            SubscriptionEntity(
                podcastId = payload.string("podcast_id").ifBlank { change.entityId },
                feedUrl = payload.string("feed_url"),
                title = payload.string("title").ifBlank { "Podcast" },
                artworkUrl = payload.string("artwork_url"),
                addedAt = payload.long("added_at").takeIf { it > 0 }
                    ?: change.clientTimestamp.takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
                inboxMode = payload.string("inbox_mode")
                    .takeIf { it == SubscriptionEntity.INBOX_MODE_LATEST }
                    ?: SubscriptionEntity.INBOX_MODE_ALL,
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
        put("inbox_mode", item.inboxMode)
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
        put("event_type", "PROGRESS_TICK")
        put("playback_session_id", "")
        put("device_id", deviceId)
        put("per_session_seq", 0)
        put("client_timestamp", item.lastPlayedAt)
    }

    private fun listeningPayload(item: ListeningSessionEntity) = buildJsonObject {
        put("id", item.id)
        put("episode_id", item.episodeId)
        put("podcast_id", item.podcastId)
        put("title", item.title)
        put("podcast_title", item.podcastTitle)
        put("categories", JsonArray(item.categories.map(::JsonPrimitive)))
        put("started_at", item.startedAt)
        put("ended_at", item.endedAt)
        put("wall_clock_ms", item.wallClockMs)
        put("audio_listened_ms", item.audioListenedMs)
        put("speed_saved_ms", item.speedSavedMs)
        put("silence_saved_ms", item.silenceSavedMs)
        put("manual_skipped_ms", item.manualSkippedMs)
        put("intro_outro_skipped_ms", item.introOutroSkippedMs)
        put("speed_weighted_ms", item.speedWeightedMs)
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
    private fun JsonObject.strings(key: String) =
        (get(key) as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    private fun kotlinx.serialization.json.JsonObjectBuilder.nullable(key: String, value: String?) {
        if (value != null) put(key, value)
    }

    private class AuthExpired : Exception()

    private companion object {
        const val PAGE_LIMIT = 500
        const val PUSH_BATCH = 250
    }
}
