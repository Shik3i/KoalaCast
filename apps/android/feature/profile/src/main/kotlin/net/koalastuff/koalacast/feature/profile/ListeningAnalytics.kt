package net.koalastuff.koalacast.feature.profile

import net.koalastuff.koalacast.core.model.ListeningSession
import net.koalastuff.koalacast.core.model.PlaybackProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToLong

enum class StatsRange { YEAR, DAYS_90, ALL }

data class ShowTotal(
    val id: String,
    val title: String,
    val listeningMs: Long,
    val episodes: Int,
)

data class CategoryTotal(val label: String, val listeningMs: Long)

data class ListeningAnalytics(
    val totalWallMs: Long = 0,
    val baselineAudioMs: Long = 0,
    val totalSavedMs: Long = 0,
    val speedSavedMs: Long = 0,
    val silenceSavedMs: Long = 0,
    val manualSkippedMs: Long = 0,
    val introOutroSkippedMs: Long = 0,
    val averageSpeed: Double = 1.0,
    val activeDays: Int = 0,
    val longestStreak: Int = 0,
    val completedCount: Int = 0,
    val showTotals: List<ShowTotal> = emptyList(),
    val weekdayTotals: List<Long> = List(7) { 0L },
    val hourTotals: List<Long> = List(24) { 0L },
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val byDay: Map<LocalDate, Long> = emptyMap(),
)

fun summarizeListening(
    sessions: List<ListeningSession>,
    states: List<PlaybackProgress>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ListeningAnalytics {
    val listenedByEpisode = sessions
        .groupBy(ListeningSession::episodeId)
        .mapValues { (_, episodeSessions) ->
            episodeSessions.sumOf(ListeningSession::audioListenedMs)
        }
    val byDay = mutableMapOf<LocalDate, Double>()
    val weekdayTotals = DoubleArray(7)
    val hourTotals = DoubleArray(24)
    val shows = mutableMapOf<String, MutableShow>()
    val categories = mutableMapOf<String, Long>()
    var totalWallMs = 0L
    var speedSavedMs = 0L
    var silenceSavedMs = 0L
    var manualSkippedMs = 0L
    var introOutroSkippedMs = 0L
    var speedWeightedMs = 0L

    sessions.forEach { session ->
        val wall = max(0, session.wallClockMs)
        totalWallMs += wall
        speedSavedMs += max(0, session.speedSavedMs)
        silenceSavedMs += max(0, session.silenceSavedMs)
        manualSkippedMs += max(0, session.manualSkippedMs)
        introOutroSkippedMs += max(0, session.introOutroSkippedMs)
        speedWeightedMs += max(0, session.speedWeightedMs)

        distributeAcrossHours(session.startedAtMs, session.endedAtMs, wall, zoneId) { time, portion ->
            val day = time.toLocalDate()
            byDay[day] = (byDay[day] ?: 0.0) + portion
            weekdayTotals[time.dayOfWeek.value % 7] += portion
            hourTotals[time.hour] += portion
        }

        val show = shows.getOrPut(session.podcastId) {
            MutableShow(
                id = session.podcastId,
                title = session.podcastTitle.ifBlank { "Unknown show" },
            )
        }
        show.listeningMs += wall
        show.episodeIds += session.episodeId

        val category = session.categories.firstOrNull(String::isNotBlank) ?: "Uncategorised"
        categories[category] = (categories[category] ?: 0L) + wall
    }

    val totalSavedMs =
        speedSavedMs + silenceSavedMs + manualSkippedMs + introOutroSkippedMs
    val dayTotals = byDay.mapValues { it.value.roundToLong() }
    return ListeningAnalytics(
        totalWallMs = totalWallMs,
        baselineAudioMs = totalWallMs + totalSavedMs,
        totalSavedMs = totalSavedMs,
        speedSavedMs = speedSavedMs,
        silenceSavedMs = silenceSavedMs,
        manualSkippedMs = manualSkippedMs,
        introOutroSkippedMs = introOutroSkippedMs,
        averageSpeed = if (totalWallMs > 0) speedWeightedMs.toDouble() / totalWallMs else 1.0,
        activeDays = dayTotals.size,
        longestStreak = longestStreak(dayTotals.keys),
        completedCount = states.count { finishedByListening(it, listenedByEpisode) },
        showTotals = shows.values
            .map { ShowTotal(it.id, it.title, it.listeningMs, it.episodeIds.size) }
            .sortedByDescending(ShowTotal::listeningMs)
            .take(10),
        weekdayTotals = weekdayTotals.map(Double::roundToLong),
        hourTotals = hourTotals.map(Double::roundToLong),
        categoryTotals = categories
            .map { CategoryTotal(it.key, it.value) }
            .sortedByDescending(CategoryTotal::listeningMs)
            .take(5),
        byDay = dayTotals,
    )
}

/**
 * Whether an episode counts as *finished* in the statistics.
 *
 * "Mark as played" is a library gesture: it clears an episode out of New, and it
 * has to keep doing that whether or not a second of it was heard. But it also sets
 * `completed`, and counting that as a finished episode inflated the figure with
 * episodes nobody listened to — twenty-odd "completed" for someone who had cleared
 * a backlog.
 *
 * So the statistic asks the listening record instead of the flag. Half the episode
 * rather than all of it, because finishing normally involves skipping an outro, a
 * sponsor read, or the last thirty seconds of goodbyes — demanding 100% would swap
 * one wrong number for another. Episodes whose length never made it into the
 * database fall back to an absolute minimum.
 */
internal fun finishedByListening(
    state: PlaybackProgress,
    listenedByEpisode: Map<String, Long>,
): Boolean {
    if (!state.completed) return false
    val listenedMs = listenedByEpisode[state.episodeId] ?: 0L
    val durationMs = state.track?.durationMs ?: 0L
    return if (durationMs > 0) {
        listenedMs >= durationMs * MIN_FINISHED_FRACTION
    } else {
        listenedMs >= MIN_FINISHED_MS
    }
}

private const val MIN_FINISHED_FRACTION = 0.5
private const val MIN_FINISHED_MS = 5L * 60 * 1000

fun rangeFloor(range: StatsRange, now: ZonedDateTime): Long =
    when (range) {
        StatsRange.YEAR -> now.withDayOfYear(1).toLocalDate().atStartOfDay(now.zone)
            .toInstant().toEpochMilli()
        StatsRange.DAYS_90 -> now.minusDays(90).toInstant().toEpochMilli()
        StatsRange.ALL -> 0
    }

fun heatmapDays(
    analytics: ListeningAnalytics,
    today: LocalDate = LocalDate.now(),
): List<Pair<LocalDate, Int>> =
    (181L downTo 0L).map { offset ->
        val date = today.minusDays(offset)
        val minutes = (analytics.byDay[date] ?: 0L) / 60_000.0
        date to when {
            minutes == 0.0 -> 0
            minutes < 20 -> 1
            minutes < 45 -> 2
            minutes < 90 -> 3
            else -> 4
        }
    }

private fun distributeAcrossHours(
    startedAtMs: Long,
    endedAtMs: Long,
    wallMs: Long,
    zoneId: ZoneId,
    consume: (ZonedDateTime, Double) -> Unit,
) {
    val start = Instant.ofEpochMilli(startedAtMs).atZone(zoneId)
    val end = Instant.ofEpochMilli(max(startedAtMs, endedAtMs)).atZone(zoneId)
    val span = ChronoUnit.MILLIS.between(start, end)
    if (span <= 0 || wallMs <= 0) {
        if (wallMs > 0) consume(start, wallMs.toDouble())
        return
    }
    var cursor = start
    while (cursor < end) {
        val boundary = minOf(end, cursor.truncatedTo(ChronoUnit.HOURS).plusHours(1))
        val portion = wallMs * (ChronoUnit.MILLIS.between(cursor, boundary).toDouble() / span)
        consume(cursor, portion)
        cursor = boundary
    }
}

private fun longestStreak(days: Set<LocalDate>): Int {
    var longest = 0
    var current = 0
    var previous: LocalDate? = null
    days.sorted().forEach { day ->
        current = if (previous?.plusDays(1) == day) current + 1 else 1
        longest = max(longest, current)
        previous = day
    }
    return longest
}

private data class MutableShow(
    val id: String,
    val title: String,
    var listeningMs: Long = 0,
    val episodeIds: MutableSet<String> = mutableSetOf(),
)
