package net.koalastuff.koalacast.core.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import net.koalastuff.koalacast.core.data.db.EpisodeDownloadDao
import net.koalastuff.koalacast.core.model.DownloadState
import okhttp3.OkHttpClient
import okhttp3.Request

@HiltWorker
class EpisodeDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: EpisodeDownloadDao,
    private val client: OkHttpClient,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val episodeId = inputData.getString(KEY_EPISODE_ID) ?: return@withContext Result.failure()
        val row = dao.get(episodeId) ?: return@withContext Result.failure()
        val partial = DownloadRepository.partialFile(applicationContext, episodeId)
        val completed = DownloadRepository.completedFile(applicationContext, episodeId)
        val existing = partial.length()
        setForeground(createForeground(row.title, 0))
        update(episodeId, DownloadState.DOWNLOADING, existing, row.totalBytes)

        try {
            val request = Request.Builder()
                .url(row.enclosureUrl)
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val append = existing > 0 && response.code == 206
                val start = if (append) existing else 0L
                val total = response.body.contentLength()
                    .takeIf { it >= 0 }
                    ?.plus(start)
                    ?: row.totalBytes
                if (!append && partial.exists()) partial.delete()

                RandomAccessFile(partial, "rw").use { output ->
                    output.seek(start)
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = start
                        var lastUpdate = 0L
                        while (true) {
                            if (isStopped) return@withContext Result.retry()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastUpdate >= UPDATE_BYTES) {
                                lastUpdate = downloaded
                                update(episodeId, DownloadState.DOWNLOADING, downloaded, total)
                                setForeground(createForeground(row.title, percent(downloaded, total)))
                            }
                        }
                    }
                }
                if (completed.exists()) completed.delete()
                check(partial.renameTo(completed)) { "Could not finalize download" }
                update(
                    episodeId,
                    DownloadState.DONE,
                    completed.length(),
                    total.takeIf { it > 0 } ?: completed.length(),
                    completed.absolutePath,
                )
                Result.success()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            update(
                episodeId,
                DownloadState.FAILED,
                partial.length(),
                row.totalBytes,
                error = error.message ?: error::class.java.simpleName,
            )
            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForeground("Podcast episode", 0)

    private suspend fun update(
        id: String,
        state: DownloadState,
        bytes: Long,
        total: Long,
        path: String? = null,
        error: String? = null,
    ) {
        dao.updateProgress(id, state.name, bytes, total, path, error, System.currentTimeMillis())
    }

    private fun createForeground(title: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Episode downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (progress > 0) "$progress%" else "Downloading…")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID_BASE + id.hashCode().ushr(1) % 10_000,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun percent(bytes: Long, total: Long) =
        if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0

    companion object {
        const val KEY_EPISODE_ID = "episode_id"
        private const val CHANNEL_ID = "episode_downloads"
        private const val NOTIFICATION_ID_BASE = 20_000
        private const val UPDATE_BYTES = 256L * 1024L
    }
}
