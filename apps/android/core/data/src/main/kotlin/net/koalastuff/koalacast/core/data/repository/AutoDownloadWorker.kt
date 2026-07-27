package net.koalastuff.koalacast.core.data.repository

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.koalastuff.koalacast.core.data.db.EpisodeDownloadDao
import net.koalastuff.koalacast.core.data.db.PodcastSettingsDao
import net.koalastuff.koalacast.core.data.db.SubscriptionDao
import net.koalastuff.koalacast.core.data.mapper.toModel
import net.koalastuff.koalacast.core.data.mapper.toTrack
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.DownloadRetention
import net.koalastuff.koalacast.core.model.DownloadState
import java.util.concurrent.TimeUnit

/**
 * Keeps opted-in shows stocked and clears out what the retention rule says is
 * spent. Runs periodically rather than on feed arrival: there is no push channel,
 * and polling every subscription on a timer is what the Inbox already does.
 *
 * Deliberately conservative — it only ever enqueues episodes the listener would
 * see at the top of the show, and it only ever deletes *downloads*, never a
 * subscription, a queue entry or a playback position.
 */
@HiltWorker
class AutoDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settingsDao: PodcastSettingsDao,
    private val subscriptionDao: SubscriptionDao,
    private val downloadDao: EpisodeDownloadDao,
    private val downloads: DownloadRepository,
    private val podcasts: PodcastRepository,
    private val progress: ProgressRepository,
    private val preferences: PreferencesRepository,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = preferences.preferences.first()

        // Sweep first: freeing space before fetching means a full device is more
        // likely to have room for the new episodes.
        runCatching { applyRetention(prefs.downloadRetention) }

        val enabled = settingsDao.autoDownloadEnabled()
        if (enabled.isEmpty()) return@withContext Result.success()

        // Read once: this is a Room-backed flow, and collecting it per episode
        // would re-query the table for every candidate.
        val completed = progress.completedEpisodeIds.first()
        var anyFeedFailed = false
        for (settings in enabled) {
            val subscription = subscriptionDao.get(settings.podcastId)?.toModel() ?: continue
            when (val result = podcasts.episodes(settings.podcastId, limit = prefs.autoDownloadCount)) {
                is DataResult.Failure -> anyFeedFailed = true
                is DataResult.Success -> {
                    for (episode in result.data.take(prefs.autoDownloadCount)) {
                        if (episode.enclosureUrl.isBlank()) continue
                        // Never resurrect something the listener already deleted or
                        // already finished — that would fight them every cycle.
                        if (downloadDao.get(episode.id) != null) continue
                        if (episode.id in completed) continue
                        downloads.enqueue(
                            episode.toTrack(subscription.title, subscription.artworkUrl),
                            wifiOnly = prefs.downloadWifiOnly,
                        )
                    }
                }
            }
        }

        // A feed that was unreachable is worth another attempt; everything else
        // has already been done and should not repeat the whole sweep.
        if (anyFeedFailed) Result.retry() else Result.success()
    }

    private suspend fun applyRetention(retention: DownloadRetention) {
        if (retention == DownloadRetention.KEEP) return
        val completed = progress.completedEpisodeIds.first()
        val now = clock.nowMs()
        val maxAge = retention.maxAgeMs

        for (row in downloadDao.observeAll().first()) {
            if (row.state != DownloadState.DONE.name) continue
            val expired = when {
                retention == DownloadRetention.WHEN_FINISHED -> row.episodeId in completed
                maxAge != null -> now - row.updatedAt >= maxAge
                else -> false
            }
            if (expired) downloads.remove(row.episodeId)
        }
    }

    companion object {
        private const val WORK_NAME = "auto-download"

        /**
         * Every six hours, on unmetered network. WorkManager will not run a periodic
         * job more often than every 15 minutes anyway, and a podcast feed that
         * updates faster than six hours is not a thing worth burning battery on.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoDownloadWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP, so toggling a show does not reset the interval and starve
                // the job on a device that is rarely idle.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
