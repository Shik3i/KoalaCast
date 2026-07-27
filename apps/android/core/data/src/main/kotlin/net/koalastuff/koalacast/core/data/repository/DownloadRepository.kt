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

    suspend fun enqueue(track: Track, wifiOnly: Boolean = true) {
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
            .setInputData(workDataOf(EpisodeDownloadWorker.KEY_EPISODE_ID to track.episodeId))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(track.episodeId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    suspend fun pause(episodeId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(episodeId))
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
        WorkManager.getInstance(context).cancelUniqueWork(workName(episodeId))
        dao.get(episodeId)?.localPath?.let(::File)?.takeIf(File::exists)?.delete()
        partialFile(context, episodeId).takeIf(File::exists)?.delete()
        dao.delete(episodeId)
    }

    suspend fun completedPath(episodeId: String): String? =
        dao.get(episodeId)
            ?.takeIf { it.state == DownloadState.DONE.name }
            ?.localPath
            ?.takeIf { File(it).exists() }

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
    }
}
