package net.koalastuff.koalacast.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.koalastuff.koalacast.core.data.db.ListeningSessionDao
import net.koalastuff.koalacast.core.data.db.PlaybackStateDao
import net.koalastuff.koalacast.core.data.db.PlaybackStateEntity
import net.koalastuff.koalacast.core.data.mapper.toEntity
import net.koalastuff.koalacast.core.data.mapper.toModel
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.model.ListeningSession
import net.koalastuff.koalacast.core.model.PlaybackProgress
import net.koalastuff.koalacast.core.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playback position and the listening telemetry behind the Profile screen. Both
 * stay on-device; the telemetry only ever leaves it if the listener signs in and
 * syncs, and even then it is their own account.
 *
 * Positions are written with the track's metadata denormalised alongside, which
 * is what lets "continue listening" render and resume with the network off.
 */
@Singleton
class ProgressRepository @Inject constructor(
    private val playbackStates: PlaybackStateDao,
    private val listeningSessions: ListeningSessionDao,
    private val clock: Clock,
) {

    val inProgress: Flow<List<PlaybackProgress>> =
        playbackStates.observeInProgress().map { list -> list.map { it.toModel() } }

    val completedEpisodeIds: Flow<Set<String>> =
        playbackStates.observeCompletedIds().map { it.toSet() }

    val allProgress: Flow<List<PlaybackProgress>> =
        playbackStates.observeAll().map { list -> list.map { it.toModel() } }

    fun progress(episodeId: String): Flow<PlaybackProgress?> =
        playbackStates.observe(episodeId).map { it?.toModel() }

    suspend fun progressSnapshot(episodeId: String): PlaybackProgress? =
        playbackStates.get(episodeId)?.toModel()

    suspend fun completedIdsSnapshot(): Set<String> = playbackStates.completedIds().toSet()

    /**
     * Records a position. An episode within [COMPLETION_THRESHOLD_PERCENT] of the
     * end counts as finished — publishers pad the tail with credits, and demanding
     * the last millisecond would leave everything permanently "in progress".
     */
    suspend fun savePosition(track: Track, positionMs: Long, durationMs: Long) {
        val effectiveDuration = durationMs.takeIf { it > 0 } ?: track.durationMs
        val percent = if (effectiveDuration > 0) {
            ((positionMs.toDouble() / effectiveDuration) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }

        playbackStates.upsert(
            PlaybackStateEntity(
                episodeId = track.episodeId,
                podcastId = track.podcastId,
                positionMs = positionMs.coerceAtLeast(0),
                completed = percent >= COMPLETION_THRESHOLD_PERCENT,
                progressPercent = percent,
                lastPlayedAt = clock.nowMs(),
                title = track.title,
                podcastTitle = track.podcastTitle,
                artworkUrl = track.artworkUrl,
                enclosureUrl = track.enclosureUrl,
                durationMs = effectiveDuration,
                categories = track.categories,
            ),
        )
    }

    /**
     * Marks played/unplayed without listening. Marking played keeps the existing
     * position so resuming still lands where the listener left off; marking
     * unplayed resets to the start, which is what "listen again" means.
     */
    suspend fun setPlayed(track: Track, played: Boolean) {
        val existing = playbackStates.get(track.episodeId)
        playbackStates.upsert(
            PlaybackStateEntity(
                episodeId = track.episodeId,
                podcastId = track.podcastId,
                positionMs = if (played) existing?.positionMs ?: 0L else 0L,
                completed = played,
                progressPercent = if (played) 100 else 0,
                lastPlayedAt = clock.nowMs(),
                title = track.title.ifBlank { existing?.title.orEmpty() },
                podcastTitle = track.podcastTitle.ifBlank { existing?.podcastTitle.orEmpty() },
                artworkUrl = track.artworkUrl.ifBlank { existing?.artworkUrl.orEmpty() },
                enclosureUrl = track.enclosureUrl.ifBlank { existing?.enclosureUrl.orEmpty() },
                durationMs = track.durationMs.takeIf { it > 0 } ?: existing?.durationMs,
                categories = track.categories.ifEmpty { existing?.categories ?: emptyList() },
            ),
        )
    }

    // ---- Listening telemetry ----

    val listeningHistory: Flow<List<ListeningSession>> =
        listeningSessions.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun listeningHistorySnapshot(): List<ListeningSession> =
        listeningSessions.getAll().map { it.toModel() }

    suspend fun recordListeningSession(session: ListeningSession) {
        // A segment shorter than a second is a scrub, not listening.
        if (session.wallClockMs < MIN_SESSION_MS) return
        listeningSessions.upsert(session.toEntity())
    }

    private companion object {
        const val COMPLETION_THRESHOLD_PERCENT = 98
        const val MIN_SESSION_MS = 1_000L
    }
}
