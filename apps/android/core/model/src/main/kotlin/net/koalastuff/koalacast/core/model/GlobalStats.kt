package net.koalastuff.koalacast.core.model

data class GlobalLabeledTotal(val label: String, val ms: Long)
data class GlobalDayTotal(val date: String, val ms: Long)
data class GlobalPodcastRank(
    val rank: Int,
    val id: String,
    val title: String,
    val ms: Long,
    val episodes: Int,
)
data class GlobalListenerRank(
    val rank: Int,
    val username: String,
    val ms: Long,
    val activeDays: Int,
    val podcasts: Int,
)
data class GlobalStats(
    val participants: Int,
    val totalWallMs: Long,
    val totalSavedMs: Long,
    val speedSavedMs: Long,
    val silenceSavedMs: Long,
    val manualSkippedMs: Long,
    val introOutroSkippedMs: Long,
    val averageSpeed: Double,
    val activeDays: Int,
    val listeningSessions: Int,
    val episodes: Int,
    val podcasts: Int,
    val weekdayTotals: List<Long>,
    val hourTotals: List<Long>,
    val dayTotals: List<GlobalDayTotal>,
    val categoryTotals: List<GlobalLabeledTotal>,
    val podcastRankings: List<GlobalPodcastRank>,
    val listenerLeaderboard: List<GlobalListenerRank>,
)
