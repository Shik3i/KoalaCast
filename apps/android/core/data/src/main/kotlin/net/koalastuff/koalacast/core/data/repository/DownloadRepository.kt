package net.koalastuff.koalacast.core.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.koalastuff.koalacast.core.model.DownloadStorage
import net.koalastuff.koalacast.core.data.db.EpisodeDownloadDao
import net.koalastuff.koalacast.core.data.db.EpisodeDownloadEntity
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.model.DownloadState
import net.koalastuff.koalacast.core.model.EpisodeDownload
import net.koalastuff.koalacast.core.model.Track

@Singleton
class DownloadRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: EpisodeDownloadDao,
    private val clock: Clock,
) {
    val downloads: Flow<List<EpisodeDownload>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    fun download(episodeId: String): Flow<EpisodeDownload?> =
        dao.observe(episodeId).map { it?.toModel() }

    suspend fun enqueue(
        track: Track,
        wifiOnly: Boolean = true,
        concurrency: Int = 2,
        storage: DownloadStorage = DownloadStorage.INTERNAL,
        treeUri: String = "",
        budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    ) {
        val now = clock.nowMs()
        val old = dao.get(track.episodeId)
        dao.upsert(
            EpisodeDownloadEntity(
                episodeId = track.episodeId,
                podcastId = track.podcastId,
                title = track.title,
                podcastTitle = track.podcastTitle,
                artworkUrl = track.artworkUrl,
                enclosureUrl = track.enclosureUrl,
                durationMs = track.durationMs,
                categories = track.categories,
                state = DownloadState.QUEUED.name,
                bytesDownloaded = old?.bytesDownloaded ?: 0,
                totalBytes = old?.totalBytes ?: 0,
                localPath = old?.localPath,
                error = null,
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
            .setInputData(
                workDataOf(
                    EpisodeDownloadWorker.KEY_EPISODE_ID to track.episodeId,
                    EpisodeDownloadWorker.KEY_STORAGE to storage.id,
                    EpisodeDownloadWorker.KEY_TREE_URI to treeUri,
                    EpisodeDownloadWorker.KEY_BUDGET_BYTES to budgetBytes,
                    EpisodeDownloadWorker.KEY_CONCURRENCY to concurrency.coerceIn(1, 4),
                ),
            )
            .setConstraints(constraints)
            .addTag(workName(track.episodeId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(track.episodeId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    suspend fun pause(episodeId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(workName(episodeId))
        val row = dao.get(episodeId) ?: return
        dao.updateProgress(
            episodeId,
            DownloadState.PAUSED.name,
            row.bytesDownloaded,
            row.totalBytes,
            row.localPath,
            null,
            clock.nowMs(),
        )
    }

    suspend fun remove(episodeId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(workName(episodeId))
        dao.get(episodeId)?.localPath?.let { location ->
            if (location.startsWith("content://")) {
                context.contentResolver.delete(android.net.Uri.parse(location), null, null)
            } else {
                File(location).takeIf(File::exists)?.delete()
            }
        }
        partialFile(context, episodeId).takeIf(File::exists)?.delete()
        dao.delete(episodeId)
    }

    suspend fun completedPath(episodeId: String): String? =
        dao.get(episodeId)
            ?.takeIf { it.state == DownloadState.DONE.name }
            ?.localPath
            ?.takeIf {
                if (it.startsWith("content://")) {
                    runCatching {
                        context.contentResolver.openFileDescriptor(android.net.Uri.parse(it), "r")
                            ?.use { descriptor -> descriptor.statSize >= 0 } == true
                    }.getOrDefault(false)
                } else {
                    File(it).exists()
                }
            }

    suspend fun cleanupToBudget(budgetBytes: Long) {
        if (budgetBytes <= 0) return
        val completed = dao.getAllOldestFirst()
            .filter { it.state == DownloadState.DONE.name && it.localPath != null }
        var used = completed.sumOf { it.bytesDownloaded }
        for (item in completed) {
            if (used <= budgetBytes) break
            item.localPath?.let { location ->
                if (location.startsWith("content://")) {
                    runCatching {
                        context.contentResolver.delete(android.net.Uri.parse(location), null, null)
                    }
                } else {
                    File(location).delete()
                }
            }
            dao.delete(item.episodeId)
            used -= item.bytesDownloaded
        }
    }

    private fun EpisodeDownloadEntity.toModel() = EpisodeDownload(
        episodeId = episodeId,
        track = Track(
            episodeId = episodeId,
            podcastId = podcastId,
            title = title,
            podcastTitle = podcastTitle,
            artworkUrl = artworkUrl,
            enclosureUrl = enclosureUrl,
            durationMs = durationMs,
            categories = categories,
        ),
        state = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.FAILED),
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        localPath = localPath,
        error = error,
        createdAtMs = createdAt,
        updatedAtMs = updatedAt,
    )

    companion object {
        internal fun storageKey(episodeId: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(episodeId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        internal fun workName(episodeId: String) = "episode-download-${storageKey(episodeId)}"
        internal fun downloadDirectory(context: Context) =
            File(context.filesDir, "episodes").apply { mkdirs() }
        internal fun partialFile(context: Context, episodeId: String) =
            File(downloadDirectory(context), "${storageKey(episodeId)}.part")
        internal fun completedFile(context: Context, episodeId: String) =
            File(downloadDirectory(context), "${storageKey(episodeId)}.audio")
        internal fun externalDownloadDirectory(context: Context) =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "episodes").apply { mkdirs() }
        const val DEFAULT_BUDGET_BYTES = 2L * 1024 * 1024 * 1024
    }
}
