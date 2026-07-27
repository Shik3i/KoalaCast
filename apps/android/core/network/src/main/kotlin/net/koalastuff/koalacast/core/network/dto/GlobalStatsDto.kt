package net.koalastuff.koalacast.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GlobalLabeledTotalDto(val label: String = "", val ms: Long = 0)

@Serializable
data class GlobalDayTotalDto(val date: String = "", val ms: Long = 0)

@Serializable
data class GlobalPodcastRankDto(
    val rank: Int = 0,
    val id: String = "",
    val title: String = "",
    val ms: Long = 0,
    val episodes: Int = 0,
)

@Serializable
data class GlobalListenerRankDto(
    val rank: Int = 0,
    val username: String = "",
    val ms: Long = 0,
    @SerialName("active_days") val activeDays: Int = 0,
    val podcasts: Int = 0,
)

@Serializable
data class GlobalStatsDto(
    val participants: Int = 0,
    @SerialName("total_wall_ms") val totalWallMs: Long = 0,
    @SerialName("total_saved_ms") val totalSavedMs: Long = 0,
    @SerialName("speed_saved_ms") val speedSavedMs: Long = 0,
    @SerialName("silence_saved_ms") val silenceSavedMs: Long = 0,
    @SerialName("manual_skipped_ms") val manualSkippedMs: Long = 0,
    @SerialName("intro_outro_skipped_ms") val introOutroSkippedMs: Long = 0,
    @SerialName("average_speed") val averageSpeed: Double = 1.0,
    @SerialName("active_days") val activeDays: Int = 0,
    @SerialName("listening_sessions") val listeningSessions: Int = 0,
    val episodes: Int = 0,
    val podcasts: Int = 0,
    @SerialName("weekday_totals") val weekdayTotals: List<Long> = emptyList(),
    @SerialName("hour_totals") val hourTotals: List<Long> = emptyList(),
    @SerialName("day_totals") val dayTotals: List<GlobalDayTotalDto> = emptyList(),
    @SerialName("category_totals") val categoryTotals: List<GlobalLabeledTotalDto> = emptyList(),
    @SerialName("podcast_rankings") val podcastRankings: List<GlobalPodcastRankDto> = emptyList(),
    @SerialName("listener_leaderboard") val listenerLeaderboard: List<GlobalListenerRankDto> = emptyList(),
)
