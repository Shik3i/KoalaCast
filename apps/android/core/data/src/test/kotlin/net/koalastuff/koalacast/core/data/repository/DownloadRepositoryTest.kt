package net.koalastuff.koalacast.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
