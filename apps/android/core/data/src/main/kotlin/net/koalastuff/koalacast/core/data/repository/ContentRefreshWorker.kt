package net.koalastuff.koalacast.core.data.repository

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.koalastuff.koalacast.core.data.R
import net.koalastuff.koalacast.core.data.db.PodcastSettingsDao
import net.koalastuff.koalacast.core.data.db.SubscriptionDao
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.mapper.toModel
import net.koalastuff.koalacast.core.data.mapper.toTrack
import net.koalastuff.koalacast.core.model.DataResult

/**
 * Warms every subscribed feed while the app is closed. Existing snapshots remain
 * visible forever; this worker only merges genuinely new episodes into them.
 */
@HiltWorker
class ContentRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val subscriptionDao: SubscriptionDao,
    private val settingsDao: PodcastSettingsDao,
    private val podcasts: PodcastRepository,
    private val queue: QueueRepository,
    private val accountStore: SecureAccountStore,
    private val appReadiness: AppReadiness,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        appReadiness.await()
        val ownerId = accountStore.activeOwnerId()
        val generation = accountStore.accountGeneration()
        val subscriptions = subscriptionDao.getAll().map { it.toModel() }
        if (subscriptions.isEmpty()) return@withContext Result.success()
        val settings = settingsDao.getAll().associateBy { it.podcastId }
        val semaphore = Semaphore(MAX_CONCURRENT_FEEDS)

        val updates = coroutineScope {
            subscriptions.map { subscription ->
                async {
                    semaphore.withPermit {
                        val cached = podcasts.cachedEpisodes(subscription.podcastId, FEED_LIMIT)
                            ?: run {
                                podcasts.refreshEpisodesIncrementally(
                                    subscription.podcastId,
                                    FEED_LIMIT,
                                )
                                return@withPermit emptyList()
                            }
                        val knownIds = cached.value.mapTo(hashSetOf()) { it.id }
                        val result = podcasts.refreshEpisodesIncrementally(
                            subscription.podcastId,
                            FEED_LIMIT,
                        )
                        if (result !is DataResult.Success) return@withPermit emptyList()
                        val newEpisodes = result.data.filterNot { it.id in knownIds }
                        if (settings[subscription.podcastId]?.autoQueueNew == true) {
                            newEpisodes.asReversed().forEach { episode ->
                                if (!isCurrentAccount(ownerId, generation)) return@withPermit emptyList()
                                if (episode.enclosureUrl.isNotBlank()) {
                                    queue.addToEnd(
                                        episode.toTrack(
                                            subscription.title,
                                            subscription.artworkUrl,
                                        ),
                                    )
                                }
                            }
                        }
                        if (settings[subscription.podcastId]?.notifyNewEpisodes == true) {
                            newEpisodes.map { subscription.title to it.title }
                        } else {
                            emptyList()
                        }
                    }
                }
            }.awaitAll().flatten()
        }

        if (updates.isNotEmpty() && isCurrentAccount(ownerId, generation)) {
            notifyNewEpisodes(updates)
        }
        Result.success()
    }

    private fun isCurrentAccount(ownerId: String, generation: Long): Boolean =
        accountStore.activeOwnerId() == ownerId &&
            accountStore.accountGeneration() == generation

    private fun notifyNewEpisodes(updates: List<Pair<String, String>>) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.new_episodes_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                0,
                it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        // Every one of these strings used to be an English literal, so the one
        // part of the app a listener sees without opening it was the one part
        // that ignored their language.
        val first = updates.first()
        val shows = updates.map { it.first }.distinct().size
        val text = if (updates.size == 1) {
            applicationContext.getString(R.string.new_episodes_single, first.first, first.second)
        } else {
            applicationContext.resources.getQuantityString(
                R.plurals.new_episodes_shows,
                shows,
                updates.size,
                shows,
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(applicationContext.getString(R.string.new_episodes_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val WORK_NAME = "content-refresh"
        private const val CHANNEL_ID = "new-episodes"
        private const val NOTIFICATION_ID = 4102
        private const val FEED_LIMIT = 30
        private const val MAX_CONCURRENT_FEEDS = 4

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ContentRefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
