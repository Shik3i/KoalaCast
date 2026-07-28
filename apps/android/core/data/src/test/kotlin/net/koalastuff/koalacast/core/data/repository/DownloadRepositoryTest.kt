package net.koalastuff.koalacast.core.data.repository

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
        val key = DownloadRepository.storageKey(unsafeId)

        assertEquals(key, DownloadRepository.storageKey(unsafeId))
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(key.contains('/'))
        assertFalse(key.contains(".."))
        assertNotEquals(key, DownloadRepository.storageKey("$unsafeId-2"))
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

    private companion object {
        const val DAY = 24L * 60 * 60 * 1_000
    }
}
