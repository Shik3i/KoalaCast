package net.koalastuff.koalacast.core.player

import net.koalastuff.koalacast.core.model.ListeningSession
import net.koalastuff.koalacast.core.model.Track
import java.util.UUID

/**
 * Accumulates one uninterrupted play segment at a time — play → pause/stop/end —
 * so the Profile screen can say how much real time was spent listening and how
 * much of it variable speed saved.
 *
 * The arithmetic is deliberately conservative: wall-clock time is measured, and
 * audio consumed is derived from it, rather than the other way round. A seek is
 * not listening, so it closes the segment and opens a new one.
 */
class ListeningSessionRecorder {

    private var track: Track? = null
    private var startedAtMs: Long = 0
    private var speed: Float = 1f
    private var manualSkippedMs: Long = 0

    val isRecording: Boolean get() = track != null

    fun start(track: Track, nowMs: Long, speed: Float) {
        if (isRecording) return
        this.track = track
        this.startedAtMs = nowMs
        this.speed = speed
        this.manualSkippedMs = 0
    }

    /**
     * A speed change splits the segment in two — otherwise the whole segment
     * would be attributed to whichever speed happened to be set last.
     */
    fun onSpeedChanged(newSpeed: Float, nowMs: Long): ListeningSession? {
        if (!isRecording || newSpeed == speed) return null
        // Captured first: stop() clears the track, and the new segment needs it.
        val current = track ?: return null
        val closed = stop(nowMs)
        start(current, nowMs, newSpeed)
        return closed
    }

    /** A ±15/30 s tap: time skipped, not time listened. */
    fun onManualSkip(deltaMs: Long) {
        if (deltaMs > 0) manualSkippedMs += deltaMs
    }

    /** Closes the segment. Returns null when there was nothing worth recording. */
    fun stop(nowMs: Long): ListeningSession? {
        val current = track ?: return null
        val wallClockMs = (nowMs - startedAtMs).coerceAtLeast(0)
        track = null

        if (wallClockMs <= 0) return null

        val audioListenedMs = (wallClockMs * speed).toLong()
        return ListeningSession(
            id = UUID.randomUUID().toString(),
            episodeId = current.episodeId,
            podcastId = current.podcastId,
            title = current.title,
            podcastTitle = current.podcastTitle,
            categories = current.categories,
            startedAtMs = startedAtMs,
            endedAtMs = nowMs,
            wallClockMs = wallClockMs,
            audioListenedMs = audioListenedMs,
            // Playing at 1.4x for ten minutes saves the four minutes of audio
            // that would otherwise have taken fourteen.
            speedSavedMs = (audioListenedMs - wallClockMs).coerceAtLeast(0),
            // Silence trimming is not implemented yet; reporting a number here
            // would be inventing one.
            silenceSavedMs = 0,
            manualSkippedMs = manualSkippedMs,
            introOutroSkippedMs = 0,
            speedWeightedMs = audioListenedMs,
        )
    }
}
