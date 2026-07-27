package net.koalastuff.koalacast.feature.player

import net.koalastuff.koalacast.core.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterStateTest {

    private val chapters = listOf(
        chapter(0, "Cold open"),
        chapter(120_000, "The case"),
        chapter(600_000, "Listener mail"),
    )

    private fun chapter(startMs: Long, title: String) =
        Chapter(startMs = startMs, title = title, imageUrl = "", linkUrl = "")

    @Test
    fun `the current chapter is the last one that has started`() {
        assertEquals("Cold open", ChapterState.current(chapters, 60_000)?.title)
        assertEquals("The case", ChapterState.current(chapters, 120_000)?.title)
        assertEquals("Listener mail", ChapterState.current(chapters, 900_000)?.title)
    }

    @Test
    fun `a position before the first chapter has no current chapter`() {
        val late = listOf(chapter(30_000, "Starts late"))
        assertNull(ChapterState.current(late, 10_000))
        assertEquals(-1, ChapterState.currentIndex(late, 10_000))
    }

    @Test
    fun `next goes to the following start and is null at the last chapter`() {
        assertEquals(120_000L, ChapterState.nextStartMs(chapters, 60_000))
        assertNull(ChapterState.nextStartMs(chapters, 900_000))
    }

    @Test
    fun `previous restarts the current chapter when well into it`() {
        // Four minutes into "The case" — the useful move is back to its start.
        assertEquals(120_000L, ChapterState.previousStartMs(chapters, 360_000))
    }

    @Test
    fun `previous steps back when already near the start`() {
        // Two seconds in: the listener means the chapter before this one.
        assertEquals(0L, ChapterState.previousStartMs(chapters, 122_000))
    }

    @Test
    fun `previous from the first chapter has nowhere to go`() {
        assertNull(ChapterState.previousStartMs(chapters, 1_000))
    }

    @Test
    fun `markers are fractions inside the track, never at either end`() {
        val marks = ChapterState.markerFractions(chapters, durationMs = 1_200_000)
        // The chapter at 0 would sit under the track's own start cap.
        assertEquals(listOf(0.1f, 0.5f), marks)
    }

    @Test
    fun `an unknown duration yields no markers rather than nonsense`() {
        assertEquals(emptyList<Float>(), ChapterState.markerFractions(chapters, durationMs = 0))
    }
}
