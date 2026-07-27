package net.koalastuff.koalacast.core.data.repository

import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.GlobalDayTotal
import net.koalastuff.koalacast.core.model.GlobalLabeledTotal
import net.koalastuff.koalacast.core.model.GlobalListenerRank
import net.koalastuff.koalacast.core.model.GlobalPodcastRank
import net.koalastuff.koalacast.core.model.GlobalStats
import net.koalastuff.koalacast.core.model.map
import net.koalastuff.koalacast.core.network.KoalaCastApi
import net.koalastuff.koalacast.core.network.apiCall
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalStatsRepository @Inject constructor(
    private val api: KoalaCastApi,
) {
    suspend fun load(range: String): DataResult<GlobalStats> =
        apiCall { api.globalStats(range) }.map { dto ->
            GlobalStats(
                participants = dto.participants,
                totalWallMs = dto.totalWallMs,
                totalSavedMs = dto.totalSavedMs,
                speedSavedMs = dto.speedSavedMs,
                silenceSavedMs = dto.silenceSavedMs,
                manualSkippedMs = dto.manualSkippedMs,
                introOutroSkippedMs = dto.introOutroSkippedMs,
                averageSpeed = dto.averageSpeed,
                activeDays = dto.activeDays,
                listeningSessions = dto.listeningSessions,
                episodes = dto.episodes,
                podcasts = dto.podcasts,
                weekdayTotals = dto.weekdayTotals,
                hourTotals = dto.hourTotals,
                dayTotals = dto.dayTotals.map { GlobalDayTotal(it.date, it.ms) },
                categoryTotals = dto.categoryTotals.map { GlobalLabeledTotal(it.label, it.ms) },
                podcastRankings = dto.podcastRankings.map {
                    GlobalPodcastRank(it.rank, it.id, it.title, it.ms, it.episodes)
                },
                listenerLeaderboard = dto.listenerLeaderboard.map {
                    GlobalListenerRank(it.rank, it.username, it.ms, it.activeDays, it.podcasts)
                },
            )
        }
}
