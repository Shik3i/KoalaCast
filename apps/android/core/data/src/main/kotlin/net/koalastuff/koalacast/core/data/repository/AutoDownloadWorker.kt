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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.koalastuff.koalacast.core.data.db.EpisodeDownloadDao
import net.koalastuff.koalacast.core.data.db.PodcastSettingsDao
import net.koalastuff.koalacast.core.data.db.SubscriptionDao
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
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
    private val accountStore: SecureAccountStore,
    private val appReadiness: AppReadiness,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        appReadiness.await()
        val ownerId = accountStore.activeOwnerId()
        val generation = accountStore.accountGeneration()
        val prefs = preferences.preferences.first()

        // Sweep first: freeing space before fetching means a full device is more
        // likely to have room for the new episodes.
        runCatching { applyRetention(ownerId, generation, prefs.downloadRetention) }
        if (!isCurrentAccount(ownerId, generation)) return@withContext Result.success()

        val enabled = settingsDao.autoDownloadEnabled()
        if (enabled.isEmpty()) return@withContext Result.success()

        // Read once: this is a Room-backed flow, and collecting it per episode
        // would re-query the table for every candidate.
        val completed = progress.completedEpisodeIds.first()
        val semaphore = Semaphore(MAX_CONCURRENT_FEEDS)
        coroutineScope {
            enabled.map { settings ->
                async {
                    semaphore.withPermit {
                        val subscription =
                            subscriptionDao.get(settings.podcastId)?.toModel()
                                ?: return@withPermit false
                        when (
                            val result = podcasts.episodes(
                                settings.podcastId,
                                limit = prefs.autoDownloadCount,
                            )
                        ) {
                            is DataResult.Failure -> true
                            is DataResult.Success -> {
                                for (episode in result.data.take(prefs.autoDownloadCount)) {
                                    if (!isCurrentAccount(ownerId, generation)) return@withPermit false
                                    if (episode.enclosureUrl.isBlank()) continue
                                    if (downloadDao.get(episode.id) != null) continue
                                    if (episode.id in completed) continue
                                    downloads.enqueue(
                                        episode.toTrack(subscription.title, subscription.artworkUrl),
                                        wifiOnly = prefs.downloadWifiOnly,
                                        concurrency = prefs.downloadConcurrency,
                                        storage = prefs.downloadStorage,
                                        treeUri = prefs.downloadTreeUri,
                                        budgetBytes = prefs.downloadBudgetBytes,
                                    )
                                }
                                false
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        // A failed feed is retried by the next periodic run. Retrying this worker
        // immediately would download every successful feed again as collateral work.
        Result.success()
    }

    private suspend fun applyRetention(
        ownerId: String,
        generation: Long,
        retention: DownloadRetention,
    ) {
        if (retention == DownloadRetention.KEEP) return
        val completed = progress.completedEpisodeIds.first()
        val now = clock.nowMs()

        for (row in downloadDao.observeAll().first()) {
            if (!isCurrentAccount(ownerId, generation)) return
            if (row.state != DownloadState.DONE.name) continue
            val expired = shouldRemoveDownload(
                retention = retention,
                completed = row.episodeId in completed,
                updatedAtMs = row.updatedAt,
                nowMs = now,
            )
            if (expired) downloads.remove(row.episodeId)
        }
    }

    private fun isCurrentAccount(ownerId: String, generation: Long): Boolean =
        accountStore.activeOwnerId() == ownerId &&
            accountStore.accountGeneration() == generation

    companion object {
        private const val WORK_NAME = "auto-download"
        private const val MAX_CONCURRENT_FEEDS = 4

        /**
         * Every six hours, on any connection. WorkManager will not run a periodic
         * job more often than every 15 minutes anyway, and a podcast feed that
         * updates faster than six hours is not a thing worth burning battery on.
         *
         * Deliberately not UNMETERED: this pass only reads feed metadata to decide
         * what is worth fetching. The audio itself is enqueued through
         * [DownloadRepository.enqueue], which is where "download over Wi-Fi only"
         * turns into an UNMETERED constraint on the transfer that actually spends
         * the listener's data.
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

internal fun shouldRemoveDownload(
    retention: DownloadRetention,
    completed: Boolean,
    updatedAtMs: Long,
    nowMs: Long,
): Boolean = when {
    retention == DownloadRetention.KEEP -> false
    retention == DownloadRetention.WHEN_FINISHED -> completed
    retention.maxAgeMs != null -> nowMs - updatedAtMs >= retention.maxAgeMs!!
    else -> false
}
