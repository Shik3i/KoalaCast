package net.koalastuff.koalacast.feature.player

import net.koalastuff.koalacast.core.model.Chapter

/**
 * Chapter arithmetic for the player, kept out of the composable so it can be
 * tested without one. A chapter file is a list of start times only — no ends and
 * no durations — so "where am I" and "what is next" are derived here.
 */
internal object ChapterState {

    /** Index of the chapter containing [positionMs], or -1 before the first one starts. */
    fun currentIndex(chapters: List<Chapter>, positionMs: Long): Int =
        chapters.indexOfLast { it.startMs <= positionMs }

    fun current(chapters: List<Chapter>, positionMs: Long): Chapter? =
        chapters.getOrNull(currentIndex(chapters, positionMs))

    /**
     * Where "next chapter" goes, or null at the last one.
     */
    fun nextStartMs(chapters: List<Chapter>, positionMs: Long): Long? {
        val next = chapters.firstOrNull { it.startMs > positionMs } ?: return null
        return next.startMs
    }

    /**
     * Where "previous chapter" goes. Like every music player, this restarts the
     * current chapter first and only steps back when already near its start —
     * otherwise the button is unusable halfway through a long chapter.
     */
    fun previousStartMs(
        chapters: List<Chapter>,
        positionMs: Long,
        restartWindowMs: Long = RESTART_WINDOW_MS,
    ): Long? {
        val index = currentIndex(chapters, positionMs)
        if (index < 0) return null
        val currentStart = chapters[index].startMs
        if (positionMs - currentStart > restartWindowMs) return currentStart
        return chapters.getOrNull(index - 1)?.startMs
    }

    /** Marker positions as 0..1 fractions of the episode, for the scrubber. */
    fun markerFractions(chapters: List<Chapter>, durationMs: Long): List<Float> {
        if (durationMs <= 0) return emptyList()
        return chapters
            .map { (it.startMs.toFloat() / durationMs) }
            .filter { it > 0f && it < 1f }
    }

    private const val RESTART_WINDOW_MS = 3_000L
}
