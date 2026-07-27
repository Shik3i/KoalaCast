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
    /** Media position where the segment began, to measure how far audio actually got. */
    private var startPositionMs: Long = 0
    private var manualSkippedMs: Long = 0
    private var introOutroSkippedMs: Long = 0

    val isRecording: Boolean get() = track != null

    fun start(track: Track, nowMs: Long, speed: Float, positionMs: Long = 0) {
        if (isRecording) return
        this.track = track
        this.startedAtMs = nowMs
        this.speed = speed
        this.startPositionMs = positionMs
        this.manualSkippedMs = 0
        this.introOutroSkippedMs = 0
    }

    /**
     * A speed change splits the segment in two — otherwise the whole segment
     * would be attributed to whichever speed happened to be set last.
     */
    fun onSpeedChanged(newSpeed: Float, nowMs: Long, positionMs: Long = 0): ListeningSession? {
        if (!isRecording || newSpeed == speed) return null
        // Captured first: stop() clears the track, and the new segment needs it.
        val current = track ?: return null
        val closed = stop(nowMs, positionMs)
        start(current, nowMs, newSpeed, positionMs)
        return closed
    }

    /** A ±15/30 s tap: time skipped, not time listened. */
    fun onManualSkip(deltaMs: Long) {
        if (deltaMs > 0) manualSkippedMs += deltaMs
    }

    fun onIntroOutroSkip(deltaMs: Long) {
        if (deltaMs > 0) introOutroSkippedMs += deltaMs
    }

    /** Closes the segment. Returns null when there was nothing worth recording. */
    fun stop(nowMs: Long, positionMs: Long = 0): ListeningSession? {
        val current = track ?: return null
        val wallClockMs = (nowMs - startedAtMs).coerceAtLeast(0)
        val startPosition = startPositionMs
        track = null

        if (wallClockMs <= 0) return null

        // What playing at this speed for this long should have consumed.
        val expectedAudioMs = (wallClockMs * speed).toLong()

        // What the playhead actually covered, minus the parts that were jumped
        // rather than heard. Anything left over is audio that went by without
        // costing time — which is what silence trimming does.
        val positionDeltaMs = (positionMs - startPosition).coerceAtLeast(0)
        val heardMs = (positionDeltaMs - manualSkippedMs - introOutroSkippedMs).coerceAtLeast(0)
        val silenceSavedMs = if (positionMs > 0) {
            (heardMs - expectedAudioMs).coerceAtLeast(0)
        } else {
            // No position reported (an old caller, or a segment that never
            // started cleanly): report nothing rather than guess.
            0L
        }

        val audioListenedMs = expectedAudioMs + silenceSavedMs
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
            speedSavedMs = (expectedAudioMs - wallClockMs).coerceAtLeast(0),
            // Measured, not assumed: the playhead outran wall-clock time by this
            // much, after removing everything that was skipped rather than heard.
            silenceSavedMs = silenceSavedMs,
            manualSkippedMs = manualSkippedMs,
            introOutroSkippedMs = introOutroSkippedMs,
            speedWeightedMs = audioListenedMs,
        )
    }
}
