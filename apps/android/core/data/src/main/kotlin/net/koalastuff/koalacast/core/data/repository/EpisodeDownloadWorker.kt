package net.koalastuff.koalacast.core.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.koalastuff.koalacast.core.data.R
import net.koalastuff.koalacast.core.data.db.EpisodeDownloadDao
import net.koalastuff.koalacast.core.data.db.EpisodeDownloadEntity
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.model.DownloadState
import net.koalastuff.koalacast.core.model.DownloadStorage
import okhttp3.OkHttpClient
import okhttp3.Request

@HiltWorker
class EpisodeDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: EpisodeDownloadDao,
    private val client: OkHttpClient,
    private val accountStore: SecureAccountStore,
    private val accountData: AccountDataNamespace,
    private val appReadiness: AppReadiness,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        appReadiness.await()
        val episodeId = inputData.getString(KEY_EPISODE_ID) ?: return@withContext Result.failure()
        val ownerId = inputData.getString(KEY_OWNER_ID) ?: return@withContext Result.failure()
        val generation = inputData.getLong(KEY_ACCOUNT_GENERATION, -1)
        if (!isCurrentAccount(ownerId, generation)) return@withContext Result.success()
        val row = accountData.withDataLock {
            if (isCurrentAccount(ownerId, generation)) dao.get(episodeId) else null
        } ?: return@withContext if (isCurrentAccount(ownerId, generation)) Result.failure() else Result.success()
        val concurrency = inputData.getInt(KEY_CONCURRENCY, 2).coerceIn(1, 4)
        return@withContext DownloadWorkerLimiter.withLimit(concurrency) {
            if (!isCurrentAccount(ownerId, generation)) {
                Result.success()
            } else {
                performDownload(ownerId, generation, episodeId, row)
            }
        }
    }

    private suspend fun performDownload(
        ownerId: String,
        generation: Long,
        episodeId: String,
        row: EpisodeDownloadEntity,
    ): Result {
        val storage = DownloadStorage.fromId(inputData.getString(KEY_STORAGE))
        val treeUri = inputData.getString(KEY_TREE_URI).orEmpty()
        val budgetBytes = inputData.getLong(
            KEY_BUDGET_BYTES,
            DownloadRepository.DEFAULT_BUDGET_BYTES,
        )
        val target = createTarget(ownerId, episodeId, storage, treeUri)
            ?: return permanentFailure(episodeId, row.totalBytes, "Storage folder unavailable")
        val existing = target.length()
        setForeground(createForeground(row.title, 0))
        update(episodeId, DownloadState.DOWNLOADING, existing, row.totalBytes, target.location)

        return try {
            val request = Request.Builder()
                .url(row.enclosureUrl)
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()
            client.newCall(request).execute().use { response ->
                if (!isCurrentAccount(ownerId, generation)) throw ObsoleteDownloadException()
                if (response.code == 416 && existing > 0) {
                    val remoteTotal = parseContentRange(response.header("Content-Range"))?.total
                    if (remoteTotal == existing) {
                        val completed = target.finalizeDownload()
                            ?: throw IOException("Could not finalize completed partial download")
                        update(
                            episodeId,
                            DownloadState.DONE,
                            completed.length,
                            remoteTotal,
                            completed.location,
                        )
                        enforceBudget(ownerId, generation, episodeId, budgetBytes)
                        return Result.success()
                    }
                    target.truncate()
                    throw IOException("Server rejected resume offset $existing")
                }
                if (!response.isSuccessful) {
                    val message = "HTTP ${response.code}"
                    if (response.code == 408 || response.code == 429 || response.code >= 500) {
                        throw IOException(message)
                    }
                    throw PermanentDownloadException(message)
                }
                val append = existing > 0 && response.code == 206
                val contentRange = parseContentRange(response.header("Content-Range"))
                if (append && contentRange?.start != existing) {
                    target.truncate()
                    throw IOException(
                        "Invalid Content-Range start ${contentRange?.start} for offset $existing",
                    )
                }
                val start = if (append) existing else 0L
                val responseLength = response.body.contentLength().takeIf { it >= 0 }
                val total = contentRange?.total ?: responseLength?.plus(start) ?: 0L
                val allowedBytes = reserveBudget(
                    ownerId,
                    generation,
                    episodeId,
                    total,
                    budgetBytes,
                )
                if (!append) target.truncate()

                var downloaded = start
                target.open(start).use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var lastUpdate = 0L
                        while (true) {
                            if (isStopped) throw CancellationException("Download stopped")
                            if (!isCurrentAccount(ownerId, generation)) {
                                throw ObsoleteDownloadException()
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (downloaded + read > allowedBytes) {
                                throw DownloadBudgetException()
                            }
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastUpdate >= UPDATE_BYTES) {
                                lastUpdate = downloaded
                                update(
                                    episodeId,
                                    DownloadState.DOWNLOADING,
                                    downloaded,
                                    total,
                                    target.location,
                                )
                                setForeground(createForeground(row.title, percent(downloaded, total)))
                            }
                        }
                    }
                }
                if (responseLength != null && downloaded != total) {
                    throw IOException("Incomplete response: received $downloaded of $total bytes")
                }
                val completed = target.finalizeDownload()
                    ?: throw IOException("Could not finalize download")
                update(
                    episodeId,
                    DownloadState.DONE,
                    completed.length,
                    total.takeIf { it > 0 } ?: completed.length,
                    completed.location,
                )
                enforceBudget(ownerId, generation, episodeId, budgetBytes)
                Result.success()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is ObsoleteDownloadException) return Result.success()
            if (error is DownloadBudgetException) target.truncate()
            val retry = error !is PermanentDownloadException && runAttemptCount < MAX_RETRY_ATTEMPTS
            update(
                episodeId,
                if (retry) DownloadState.QUEUED else DownloadState.FAILED,
                target.length(),
                row.totalBytes,
                target.location,
                error = error.message ?: error::class.java.simpleName,
            )
            if (retry) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForeground(applicationContext.getString(R.string.download_notification_title), 0)

    private suspend fun update(
        id: String,
        state: DownloadState,
        bytes: Long,
        total: Long,
        path: String? = null,
        error: String? = null,
    ) {
        val ownerId = inputData.getString(KEY_OWNER_ID) ?: return
        val generation = inputData.getLong(KEY_ACCOUNT_GENERATION, -1)
        accountData.withDataLock {
            if (!isCurrentAccount(ownerId, generation)) return@withDataLock
            dao.updateProgressFromWorker(
                id,
                state.name,
                bytes,
                total,
                path,
                error,
                System.currentTimeMillis(),
            )
        }
    }

    private fun createForeground(title: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(
                if (progress > 0) {
                    applicationContext.getString(R.string.download_notification_percent, progress)
                } else {
                    applicationContext.getString(R.string.download_notification_progress)
                },
            )
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()
        val notificationId = NOTIFICATION_ID_BASE + id.hashCode().ushr(1) % 10_000
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun percent(bytes: Long, total: Long) =
        if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0

    private suspend fun permanentFailure(
        episodeId: String,
        total: Long,
        message: String,
    ): Result {
        update(episodeId, DownloadState.FAILED, 0, total, error = message)
        return Result.failure()
    }

    private fun createTarget(
        ownerId: String,
        episodeId: String,
        storage: DownloadStorage,
        treeUri: String,
    ): DownloadTarget? {
        val key = DownloadRepository.storageKey(episodeId, ownerId)
        if (storage == DownloadStorage.SAF) {
            val tree = treeUri.takeIf(String::isNotBlank)
                ?.let(Uri::parse)
                ?.let { DocumentFile.fromTreeUri(applicationContext, it) }
                ?.takeIf { it.canWrite() }
                ?: return null
            val partial = tree.findFile("$key.part")
                ?: tree.createFile("application/octet-stream", "$key.part")
                ?: return null
            return DocumentTarget(applicationContext.contentResolver, tree, partial, "$key.audio")
        }
        val directory = if (storage == DownloadStorage.EXTERNAL) {
            DownloadRepository.externalDownloadDirectory(applicationContext)
        } else {
            DownloadRepository.downloadDirectory(applicationContext)
        }
        return FileTarget(File(directory, "$key.part"), File(directory, "$key.audio"))
    }

    private suspend fun enforceBudget(
        ownerId: String,
        generation: Long,
        currentEpisodeId: String,
        budgetBytes: Long,
    ) {
        if (budgetBytes <= 0) return
        accountData.withDataLock {
            if (!isCurrentAccount(ownerId, generation)) return@withDataLock
            val completed = dao.getAllOldestFirst()
                .filter { it.state == DownloadState.DONE.name && it.localPath != null }
            var used = completed.sumOf { it.bytesDownloaded }
            for (item in completed) {
                if (used <= budgetBytes) break
                if (item.episodeId == currentEpisodeId) continue
                deleteLocation(item.localPath!!)
                dao.delete(item.episodeId)
                used -= item.bytesDownloaded
            }
        }
    }

    private suspend fun reserveBudget(
        ownerId: String,
        generation: Long,
        currentEpisodeId: String,
        totalBytes: Long,
        budgetBytes: Long,
    ): Long {
        if (budgetBytes <= 0) return Long.MAX_VALUE
        if (totalBytes > budgetBytes) throw DownloadBudgetException()
        return accountData.withDataLock {
            if (!isCurrentAccount(ownerId, generation)) throw ObsoleteDownloadException()
            val allDownloads = dao.getAllOldestFirst()
            val current = allDownloads.firstOrNull { it.episodeId == currentEpisodeId }
                ?: throw ObsoleteDownloadException()
            val completed = allDownloads
                .filter {
                    it.episodeId != currentEpisodeId &&
                        it.state == DownloadState.DONE.name &&
                        it.localPath != null
                }
            val activeReservations = allDownloads
                .filter {
                    it.episodeId != currentEpisodeId &&
                        it.state == DownloadState.DOWNLOADING.name
                }
                .sumOf { maxOf(it.bytesDownloaded, it.totalBytes) }
            var completedBytes = completed.sumOf { it.bytesDownloaded }
            if (totalBytes > 0) {
                for (item in completed) {
                    if (completedBytes + activeReservations + totalBytes <= budgetBytes) break
                    deleteLocation(item.localPath!!)
                    dao.delete(item.episodeId)
                    completedBytes -= item.bytesDownloaded
                }
            }
            val available = (budgetBytes - completedBytes - activeReservations).coerceAtLeast(0)
            val reservation = totalBytes.takeIf { it > 0 } ?: available
            if (reservation <= 0 || reservation > available) throw DownloadBudgetException()
            dao.updateProgressFromWorker(
                currentEpisodeId,
                DownloadState.DOWNLOADING.name,
                current.bytesDownloaded,
                reservation,
                current.localPath,
                null,
                System.currentTimeMillis(),
            )
            reservation
        }
    }

    private fun deleteLocation(location: String) {
        if (location.startsWith("content://")) {
            runCatching { applicationContext.contentResolver.delete(Uri.parse(location), null, null) }
        } else {
            File(location).delete()
        }
    }

    private fun isCurrentAccount(ownerId: String, generation: Long): Boolean =
        accountStore.activeOwnerId() == ownerId &&
            accountStore.accountGeneration() == generation

    companion object {
        const val KEY_EPISODE_ID = "episode_id"
        const val KEY_STORAGE = "storage"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_BUDGET_BYTES = "budget_bytes"
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_OWNER_ID = "owner_id"
        const val KEY_ACCOUNT_GENERATION = "account_generation"
        private const val CHANNEL_ID = "episode_downloads"
        private const val NOTIFICATION_ID_BASE = 20_000
        private const val UPDATE_BYTES = 256L * 1024L
        private const val MAX_RETRY_ATTEMPTS = 4
    }
}

/**
 * One global cap on simultaneous transfers.
 *
 * This used to keep a `Semaphore` per limit value in a map, so changing "parallel
 * downloads" from 2 to 4 while downloads were running produced two independent
 * semaphores and up to six concurrent transfers. A single counter cannot be
 * fooled that way, and it also lets a lowered limit take effect for the episodes
 * that are still waiting rather than only for the next batch.
 */
internal object DownloadWorkerLimiter {
    private val mutex = Mutex()
    private var active = 0
    private data class Waiter(val signal: CompletableDeferred<Unit>, val limit: Int)
    private val waiting = ArrayDeque<Waiter>()

    suspend fun <T> withLimit(concurrency: Int, block: suspend () -> T): T {
        val limit = concurrency.coerceIn(1, 4)
        acquire(limit)
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(limit: Int) {
        val waiter = mutex.withLock {
            if (active < limit) {
                active++
                null
            } else {
                Waiter(CompletableDeferred<Unit>(), limit).also { waiting.addLast(it) }
            }
        }
        if (waiter == null) return
        try {
            waiter.signal.await()
        } catch (error: CancellationException) {
            mutex.withLock {
                if (!waiting.remove(waiter)) {
                    active--
                    grantWaiters()
                }
            }
            throw error
        }
    }

    private suspend fun release() {
        mutex.withLock {
            active--
            grantWaiters()
        }
    }

    private fun grantWaiters() {
        while (true) {
            val index = waiting.indexOfFirst { active < it.limit }
            if (index < 0) return
            val waiter = waiting.removeAt(index)
            active++
            waiter.signal.complete(Unit)
        }
    }
}

private open class PermanentDownloadException(message: String) : Exception(message)
private class ObsoleteDownloadException : Exception()
private class DownloadBudgetException : PermanentDownloadException("Download budget exceeded")

internal data class ParsedContentRange(
    val start: Long?,
    val total: Long?,
)

internal fun parseContentRange(value: String?): ParsedContentRange? {
    val match = Regex("""^bytes (?:(\d+)-\d+|\*)/(\d+|\*)$""")
        .matchEntire(value?.trim().orEmpty())
        ?: return null
    return ParsedContentRange(
        start = match.groupValues[1].takeIf(String::isNotBlank)?.toLongOrNull(),
        total = match.groupValues[2].takeIf { it != "*" }?.toLongOrNull(),
    )
}

private data class CompletedTarget(val location: String, val length: Long)

private interface DownloadTarget {
    val location: String
    fun length(): Long
    fun truncate()
    fun open(position: Long): OutputStream
    fun finalizeDownload(): CompletedTarget?
}

private class FileTarget(
    private val partial: File,
    private val completed: File,
) : DownloadTarget {
    override val location: String get() = partial.absolutePath
    override fun length(): Long = partial.length()
    override fun truncate() {
        RandomAccessFile(partial, "rw").use { it.setLength(0) }
    }
    override fun open(position: Long): OutputStream =
        object : OutputStream() {
            private val file = RandomAccessFile(partial, "rw").apply { seek(position) }
            override fun write(value: Int) = file.write(value)
            override fun write(buffer: ByteArray, offset: Int, length: Int) =
                file.write(buffer, offset, length)
            override fun close() = file.close()
        }
    override fun finalizeDownload(): CompletedTarget? {
        if (completed.exists() && !completed.delete()) return null
        if (!partial.renameTo(completed)) return null
        return CompletedTarget(completed.absolutePath, completed.length())
    }
}

private class DocumentTarget(
    private val resolver: android.content.ContentResolver,
    private val directory: DocumentFile,
    private var partial: DocumentFile,
    private val completedName: String,
) : DownloadTarget {
    override val location: String get() = partial.uri.toString()
    override fun length(): Long = partial.length()
    override fun truncate() {
        // "rwt" truncates the existing document without replacing its stable URI.
        resolver.openOutputStream(partial.uri, "rwt")?.close()
            ?: throw IOException("Could not truncate storage document")
    }
    override fun open(position: Long): OutputStream {
        val descriptor = resolver.openFileDescriptor(partial.uri, "rw")
            ?: throw IOException("Could not open storage document")
        val output = FileOutputStream(descriptor.fileDescriptor)
        output.channel.position(position)
        return object : OutputStream() {
            override fun write(value: Int) = output.write(value)
            override fun write(buffer: ByteArray, offset: Int, length: Int) =
                output.write(buffer, offset, length)
            override fun close() {
                output.close()
                descriptor.close()
            }
        }
    }
    override fun finalizeDownload(): CompletedTarget? {
        directory.findFile(completedName)?.delete()
        if (!partial.renameTo(completedName)) return null
        return CompletedTarget(partial.uri.toString(), partial.length())
    }
}
