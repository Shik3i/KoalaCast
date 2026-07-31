package net.koalastuff.koalacast.feature.profile

import net.koalastuff.koalacast.core.model.ListeningSession
import net.koalastuff.koalacast.core.model.PlaybackProgress
import net.koalastuff.koalacast.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ListeningAnalyticsTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun `session crossing an hour is distributed proportionally`() {
        val start = ZonedDateTime.of(2026, 7, 1, 10, 30, 0, 0, zone)
        val stats = summarizeListening(
            sessions = listOf(session("one", start, 60 * 60_000L)),
            states = emptyList(),
            zoneId = zone,
        )

        assertEquals(30 * 60_000L, stats.hourTotals[10])
        assertEquals(30 * 60_000L, stats.hourTotals[11])
        assertEquals(60 * 60_000L, stats.totalWallMs)
    }

    @Test
    fun `summary matches saving streak show and category semantics`() {
        val day = ZonedDateTime.of(2026, 7, 1, 10, 0, 0, 0, zone)
        val stats = summarizeListening(
            sessions = listOf(
                session("one", day, 10_000),
                session("two", day.plusDays(1), 20_000),
                session("three", day.plusDays(3), 30_000),
            ),
            states = listOf(progress("done", true), progress("open", false)),
            zoneId = zone,
        )

        assertEquals(60_000, stats.totalWallMs)
        assertEquals(3_000, stats.totalSavedMs)
        assertEquals(2, stats.longestStreak)
        assertEquals(3, stats.activeDays)
        // "done" carries the completed flag but no listening session, which is what
        // "mark as played" leaves behind. It no longer counts as finished.
        assertEquals(0, stats.completedCount)
        assertEquals(1, stats.showTotals.size)
        assertEquals(3, stats.showTotals.first().episodes)
        assertEquals("Technology", stats.categoryTotals.first().label)
    }

    @Test
    fun `heatmap uses web thresholds`() {
        val today = LocalDate.of(2026, 7, 1)
        val analytics = ListeningAnalytics(
            byDay = mapOf(
                today to 19 * 60_000L,
                today.minusDays(1) to 20 * 60_000L,
                today.minusDays(2) to 45 * 60_000L,
                today.minusDays(3) to 90 * 60_000L,
            ),
        )

        assertEquals(listOf(4, 3, 2, 1), heatmapDays(analytics, today).takeLast(4).map { it.second })
    }

    /**
     * The complaint this guards: clearing a backlog with "mark as played" reported
     * two dozen finished episodes nobody had listened to.
     */
    @Test
    fun `marking an episode played without listening does not count as finished`() {
        val listened = mapOf("barely" to 30_000L)

        val result = finishedByListening(
            progressWithDuration("barely", completed = true, durationMs = 30 * 60_000L),
            listened,
        )

        assertEquals(false, result)
    }

    @Test
    fun `an episode listened most of the way through counts as finished`() {
        val listened = mapOf("heard" to 20 * 60_000L)

        val result = finishedByListening(
            progressWithDuration("heard", completed = true, durationMs = 30 * 60_000L),
            listened,
        )

        assertEquals(true, result)
    }

    /** Skipping the outro must not cost the listener a finished episode. */
    @Test
    fun `finishing after skipping the last minutes still counts`() {
        val listened = mapOf("skipped" to 16 * 60_000L)

        val result = finishedByListening(
            progressWithDuration("skipped", completed = true, durationMs = 30 * 60_000L),
            listened,
        )

        assertEquals(true, result)
    }

    @Test
    fun `an unfinished episode never counts however long it was played`() {
        val listened = mapOf("partial" to 60 * 60_000L)

        val result = finishedByListening(
            progressWithDuration("partial", completed = false, durationMs = 30 * 60_000L),
            listened,
        )

        assertEquals(false, result)
    }

    /** Feeds that publish no duration fall back to an absolute floor. */
    @Test
    fun `without a known duration a real listen still counts`() {
        assertEquals(
            false,
            finishedByListening(progress("unknown", completed = true), mapOf("unknown" to 60_000L)),
        )
        assertEquals(
            true,
            finishedByListening(progress("unknown", completed = true), mapOf("unknown" to 10 * 60_000L)),
        )
    }

    private fun progressWithDuration(id: String, completed: Boolean, durationMs: Long) =
        progress(id, completed).copy(
            track = Track(
                episodeId = id,
                podcastId = "show",
                title = id,
                podcastTitle = "Show",
                artworkUrl = "",
                enclosureUrl = "",
                durationMs = durationMs,
            ),
        )

    private fun session(id: String, start: ZonedDateTime, wallMs: Long) = ListeningSession(
        id = id,
        episodeId = id,
        podcastId = "show",
        title = id,
        podcastTitle = "Show",
        categories = listOf("Technology"),
        startedAtMs = start.toInstant().toEpochMilli(),
        endedAtMs = start.plusNanos(wallMs * 1_000_000).toInstant().toEpochMilli(),
        wallClockMs = wallMs,
        audioListenedMs = wallMs,
        speedSavedMs = 1_000,
        silenceSavedMs = 0,
        manualSkippedMs = 0,
        introOutroSkippedMs = 0,
        speedWeightedMs = wallMs,
    )

    private fun progress(id: String, completed: Boolean) = PlaybackProgress(
        episodeId = id,
        podcastId = "show",
        positionMs = 0,
        completed = completed,
        progressPercent = if (completed) 100 else 0,
        lastPlayedAtMs = 0,
        track = null,
    )
}
