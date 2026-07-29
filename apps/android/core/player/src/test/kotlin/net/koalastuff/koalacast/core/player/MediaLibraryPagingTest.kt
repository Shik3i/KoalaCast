package net.koalastuff.koalacast.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryPagingTest {
    @Test
    fun `returns requested page without overlap`() {
        val items = (1..7).toList()

        assertEquals(listOf(1, 2, 3), pagedItems(items, page = 0, pageSize = 3))
        assertEquals(listOf(4, 5, 6), pagedItems(items, page = 1, pageSize = 3))
        assertEquals(listOf(7), pagedItems(items, page = 2, pageSize = 3))
    }

    @Test
    fun `invalid and exhausted pages are empty`() {
        assertEquals(emptyList<Int>(), pagedItems(listOf(1), page = -1, pageSize = 1))
        assertEquals(emptyList<Int>(), pagedItems(listOf(1), page = 0, pageSize = 0))
        assertEquals(emptyList<Int>(), pagedItems(listOf(1), page = Int.MAX_VALUE, pageSize = 50))
    }
}
