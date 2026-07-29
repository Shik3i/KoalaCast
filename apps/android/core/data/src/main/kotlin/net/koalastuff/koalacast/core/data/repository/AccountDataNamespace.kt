package net.koalastuff.koalacast.core.data.repository

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.koalastuff.koalacast.core.data.db.AccountDataArchiveEntity
import net.koalastuff.koalacast.core.data.db.AccountNamespaceStateEntity
import net.koalastuff.koalacast.core.data.db.FavoriteEntity
import net.koalastuff.koalacast.core.data.db.EpisodeDownloadEntity
import net.koalastuff.koalacast.core.data.db.KoalaCastDatabase
import net.koalastuff.koalacast.core.data.db.ListeningSessionEntity
import net.koalastuff.koalacast.core.data.db.PlaybackStateEntity
import net.koalastuff.koalacast.core.data.db.PodcastSettingsEntity
import net.koalastuff.koalacast.core.data.db.QueueItemEntity
import net.koalastuff.koalacast.core.data.db.SubscriptionEntity
import net.koalastuff.koalacast.core.data.db.TombstoneEntity

/**
 * The active Room tables are a fast working set. Account-owned copies are archived
 * under a stable owner key when identity changes, so logging out never exposes A's
 * records to a guest or B and logging back into A restores them without data loss.
 */
@Singleton
class AccountDataNamespace @Inject constructor(
    private val database: KoalaCastDatabase,
    private val json: Json,
) {
    private val mutex = Mutex()

    suspend fun initialize(userId: String?) {
        val mustSwitch = mutex.withLock {
            database.withTransaction {
                val archives = database.accountDataArchiveDao()
                val state = archives.state()
                if (state == null) {
                    archives.setState(
                        AccountNamespaceStateEntity(activeOwnerKey = ownerKey(userId)),
                    )
                    false
                } else {
                    state.activeOwnerKey != ownerKey(userId)
                }
            }
        }
        if (mustSwitch) switchTo(userId)
    }

    suspend fun switchTo(userId: String?) = mutex.withLock {
        val targetOwner = ownerKey(userId)
        database.withTransaction {
            val archives = database.accountDataArchiveDao()
            val state = archives.state()
                ?: AccountNamespaceStateEntity(activeOwnerKey = GUEST)
            if (state.activeOwnerKey == targetOwner) {
                archives.setState(state)
                return@withTransaction
            }

            val current = capture()
            archives.upsert(
                AccountDataArchiveEntity(
                    ownerKey = state.activeOwnerKey,
                    payloadJson = json.encodeToString(AccountDataBundle.serializer(), current),
                ),
            )
            clearActive()

            archives.get(targetOwner)?.let { archive ->
                restore(json.decodeFromString(AccountDataBundle.serializer(), archive.payloadJson))
            }

            val mergeGuest = state.activeOwnerKey == GUEST &&
                targetOwner != GUEST &&
                !state.guestMerged
            if (mergeGuest) {
                restore(current)
                archives.delete(GUEST)
            }
            archives.setState(
                state.copy(
                    activeOwnerKey = targetOwner,
                    guestMerged = state.guestMerged || mergeGuest,
                ),
            )
        }
    }

    private suspend fun capture() = AccountDataBundle(
        subscriptions = database.subscriptionDao().getAll(),
        favorites = database.favoriteDao().getAll(),
        playbackStates = database.playbackStateDao().getAll(),
        listeningSessions = database.listeningSessionDao().getAll(),
        queue = database.queueDao().getAll(),
        podcastSettings = database.podcastSettingsDao().getAll(),
        downloads = database.episodeDownloadDao().getAllOldestFirst(),
        tombstones = database.tombstoneDao().getAll(),
    )

    private suspend fun clearActive() {
        database.subscriptionDao().clear()
        database.favoriteDao().clear()
        database.playbackStateDao().clear()
        database.listeningSessionDao().clear()
        database.queueDao().clear()
        database.podcastSettingsDao().clear()
        database.episodeDownloadDao().clear()
        database.tombstoneDao().clear()
    }

    private suspend fun restore(bundle: AccountDataBundle) {
        database.subscriptionDao().upsertAll(bundle.subscriptions)
        bundle.favorites.forEach { database.favoriteDao().upsert(it) }
        bundle.playbackStates.forEach { database.playbackStateDao().upsert(it) }
        bundle.listeningSessions.forEach { database.listeningSessionDao().upsert(it) }
        bundle.queue.forEach { database.queueDao().insert(it) }
        bundle.podcastSettings.forEach { database.podcastSettingsDao().upsert(it) }
        bundle.downloads.forEach { database.episodeDownloadDao().upsert(it) }
        bundle.tombstones.forEach { database.tombstoneDao().upsert(it) }
    }

    private fun ownerKey(userId: String?) =
        userId?.takeIf(String::isNotBlank)?.let { "account:$it" } ?: GUEST

    private companion object {
        const val GUEST = "guest"
    }
}

@Serializable
private data class AccountDataBundle(
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    val favorites: List<FavoriteEntity> = emptyList(),
    val playbackStates: List<PlaybackStateEntity> = emptyList(),
    val listeningSessions: List<ListeningSessionEntity> = emptyList(),
    val queue: List<QueueItemEntity> = emptyList(),
    val podcastSettings: List<PodcastSettingsEntity> = emptyList(),
    val downloads: List<EpisodeDownloadEntity> = emptyList(),
    val tombstones: List<TombstoneEntity> = emptyList(),
)
