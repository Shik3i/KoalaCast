package net.koalastuff.koalacast.core.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import net.koalastuff.koalacast.core.model.DownloadRetention

class DownloadRepositoryTest {

    @Test
    fun `storage key is deterministic and safe as one path segment`() {
        val unsafeId = "../../publisher/episode?id=one"
        val key = DownloadRepository.storageKey(unsafeId, "user-a")

        assertEquals(key, DownloadRepository.storageKey(unsafeId, "user-a"))
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(key.contains('/'))
        assertFalse(key.contains(".."))
        assertNotEquals(key, DownloadRepository.storageKey("$unsafeId-2", "user-a"))
        assertNotEquals(key, DownloadRepository.storageKey(unsafeId, "user-b"))
    }

    @Test
    fun `retention policies remove only eligible completed downloads`() {
        val now = 40L * DAY

        assertFalse(shouldRemoveDownload(DownloadRetention.KEEP, true, 0, now))
        assertTrue(shouldRemoveDownload(DownloadRetention.WHEN_FINISHED, true, now, now))
        assertFalse(shouldRemoveDownload(DownloadRetention.WHEN_FINISHED, false, 0, now))
        assertTrue(shouldRemoveDownload(DownloadRetention.AFTER_7_DAYS, false, now - 7 * DAY, now))
        assertFalse(shouldRemoveDownload(DownloadRetention.AFTER_7_DAYS, true, now - 7 * DAY + 1, now))
        assertTrue(shouldRemoveDownload(DownloadRetention.AFTER_14_DAYS, false, now - 14 * DAY, now))
        assertFalse(shouldRemoveDownload(DownloadRetention.AFTER_14_DAYS, false, now - 14 * DAY + 1, now))
        assertTrue(shouldRemoveDownload(DownloadRetention.AFTER_30_DAYS, false, now - 30 * DAY, now))
        assertFalse(shouldRemoveDownload(DownloadRetention.AFTER_30_DAYS, false, now - 30 * DAY + 1, now))
    }

    @Test
    fun `content range parser validates resume offsets and complete partials`() {
        assertEquals(
            ParsedContentRange(start = 1024, total = 4096),
            parseContentRange("bytes 1024-4095/4096"),
        )
        assertEquals(
            ParsedContentRange(start = null, total = 4096),
            parseContentRange("bytes */4096"),
        )
        assertEquals(null, parseContentRange("bytes invalid"))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancelled limiter waiter does not leak a permit`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = launch {
            DownloadWorkerLimiter.withLimit(1) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val cancelled = launch {
            DownloadWorkerLimiter.withLimit(1) {
                throw AssertionError("cancelled waiter entered the critical section")
            }
        }
        runCurrent()
        cancelled.cancelAndJoin()
        release.complete(Unit)
        active.join()

        assertTrue(withTimeout(1_000) { DownloadWorkerLimiter.withLimit(1) { true } })
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1_000
    }
}
