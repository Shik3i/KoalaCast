package net.koalastuff.koalacast.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import net.koalastuff.koalacast.core.data.db.QueueDao
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.db.QueueItemEntity
import net.koalastuff.koalacast.core.data.mapper.toModel
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.model.QueueEntry
import net.koalastuff.koalacast.core.model.Track
import net.koalastuff.koalacast.core.model.isAllowedByExplicitPreference
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The play queue, ordered and persisted. `positionOrder` is a sparse integer the
 * queue owns: adding to the front or the back only touches one row, and a
 * drag-to-reorder rewrites the whole sequence in one transaction.
 */
@Singleton
class QueueRepository @Inject constructor(
    private val queue: QueueDao,
    private val clock: Clock,
    private val syncMetadata: SecureAccountStore,
    private val preferences: PreferencesRepository? = null,
) {

    val entries: Flow<List<QueueEntry>> =
        queue.observeAll().map { list -> list.map { it.toModel() } }

    val queuedEpisodeIds: Flow<Set<String>> = queue.observeEpisodeIds().map { it.toSet() }

    suspend fun snapshot(): List<QueueEntry> = queue.getAll().map { it.toModel() }

    suspend fun isQueued(episodeId: String): Boolean = queue.getByEpisode(episodeId) != null

    /** Appends. A second add of the same episode is a no-op, not a duplicate row. */
    suspend fun addToEnd(track: Track): Boolean = add(track, queue.tailOrder())

    /** "Play next": jumps the queue without disturbing the rest of the order. */
    suspend fun addToFront(track: Track): Boolean = add(track, queue.headOrder())

    private suspend fun add(track: Track, order: Long): Boolean {
        val includeExplicit = preferences?.preferences?.first()?.allowExplicitContent ?: false
        if (!track.explicit.isAllowedByExplicitPreference(includeExplicit)) return false
        if (isQueued(track.episodeId)) return true
        queue.insert(
            QueueItemEntity(
                id = UUID.randomUUID().toString(),
                episodeId = track.episodeId,
                podcastId = track.podcastId,
                title = track.title,
                podcastTitle = track.podcastTitle,
                artworkUrl = track.artworkUrl,
                enclosureUrl = track.enclosureUrl,
                durationMs = track.durationMs,
                positionOrder = order,
                addedAt = clock.nowMs(),
                categories = track.categories,
                explicit = track.explicit,
            ),
        )
        syncMetadata.markQueueUpdated(clock.nowMs())
        return true
    }

    suspend fun remove(episodeId: String) {
        queue.deleteByEpisode(episodeId)
        syncMetadata.markQueueUpdated(clock.nowMs())
    }

    suspend fun clear() {
        queue.clear()
        syncMetadata.markQueueUpdated(clock.nowMs())
    }

    suspend fun reorder(orderedEpisodeIds: List<String>) {
        queue.reorder(orderedEpisodeIds)
        syncMetadata.markQueueUpdated(clock.nowMs())
    }

    /** The next thing to play, or null when the queue has run dry. */
    suspend fun head(): QueueEntry? = queue.first()?.toModel()

    /**
     * Drops items from the end until the queue fits the budget at the given
     * speed — the "trim to 40 min" action from the design.
     *
     * @return the episode ids that were removed.
     */
    suspend fun trimTo(budgetMs: Long, speed: Float): List<String> {
        val items = queue.getAll()
        var total = 0L
        val keep = mutableListOf<QueueItemEntity>()
        for (item in items) {
            val playbackMs = (item.durationMs / speed.coerceAtLeast(0.1f)).toLong()
            if (total + playbackMs > budgetMs && keep.isNotEmpty()) break
            total += playbackMs
            keep += item
        }
        val dropped = items.drop(keep.size)
        if (dropped.isNotEmpty()) {
            queue.deleteByEpisodeIds(dropped.map { it.episodeId })
            syncMetadata.markQueueUpdated(clock.nowMs())
        }
        return dropped.map { it.episodeId }
    }
}
