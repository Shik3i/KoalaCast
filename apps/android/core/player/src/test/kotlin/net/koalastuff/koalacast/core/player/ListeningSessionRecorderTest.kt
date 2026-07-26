package net.koalastuff.koalacast.core.player

import net.koalastuff.koalacast.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningSessionRecorderTest {

    private val recorder = ListeningSessionRecorder()

    private val track = Track(
        episodeId = "e1",
        podcastId = "p1",
        title = "Quiet rooms, loud data",
        podcastTitle = "Northbound Signal",
        artworkUrl = "",
        enclosureUrl = "https://cdn.example/e1.mp3",
        durationMs = 2_947_000,
        categories = listOf("Science"),
    )

    @Test
    fun `a ten-minute listen at 1x saves nothing`() {
        recorder.start(track, nowMs = 0, speed = 1f)
        val session = recorder.stop(nowMs = 600_000)!!

        assertEquals(600_000L, session.wallClockMs)
        assertEquals(600_000L, session.audioListenedMs)
        assertEquals(0L, session.speedSavedMs)
    }

    @Test
    fun `ten minutes at 1_4x consumes fourteen minutes of audio and saves four`() {
        recorder.start(track, nowMs = 0, speed = 1.4f)
        val session = recorder.stop(nowMs = 600_000)!!

        assertEquals(600_000L, session.wallClockMs)
        assertEquals(840_000L, session.audioListenedMs)
        assertEquals(240_000L, session.speedSavedMs)
    }

    @Test
    fun `a speed change splits the segment so neither speed is misattributed`() {
        recorder.start(track, nowMs = 0, speed = 1f)
        val first = recorder.onSpeedChanged(newSpeed = 2f, nowMs = 300_000)!!

        assertEquals(300_000L, first.wallClockMs)
        assertEquals(0L, first.speedSavedMs)
        assertTrue(recorder.isRecording)

        val second = recorder.stop(nowMs = 600_000)!!
        assertEquals(300_000L, second.wallClockMs)
        assertEquals(600_000L, second.audioListenedMs)
        assertEquals(300_000L, second.speedSavedMs)
    }

    @Test
    fun `setting the same speed again does not split anything`() {
        recorder.start(track, nowMs = 0, speed = 1.5f)
        assertNull(recorder.onSpeedChanged(newSpeed = 1.5f, nowMs = 10_000))
        assertTrue(recorder.isRecording)
    }

    @Test
    fun `a forward skip is counted as skipped, not as listened`() {
        recorder.start(track, nowMs = 0, speed = 1f)
        recorder.onManualSkip(30_000)
        recorder.onManualSkip(30_000)
        // A backward jump is not "skipping ahead" and must not count.
        recorder.onManualSkip(-15_000)

        val session = recorder.stop(nowMs = 60_000)!!
        assertEquals(60_000L, session.manualSkippedMs)
        assertEquals(60_000L, session.wallClockMs)
    }

    @Test
    fun `a segment with no elapsed time is not recorded`() {
        recorder.start(track, nowMs = 1_000, speed = 1f)
        assertNull(recorder.stop(nowMs = 1_000))
    }

    @Test
    fun `stopping without starting is a no-op`() {
        assertNull(recorder.stop(nowMs = 5_000))
        assertFalse(recorder.isRecording)
    }

    @Test
    fun `starting twice keeps the first segment's clock`() {
        recorder.start(track, nowMs = 0, speed = 1f)
        recorder.start(track, nowMs = 500_000, speed = 3f)

        val session = recorder.stop(nowMs = 600_000)!!
        assertEquals(600_000L, session.wallClockMs)
        assertEquals(600_000L, session.audioListenedMs)
    }

    @Test
    fun `the episode and show travel with the session for the profile screen`() {
        recorder.start(track, nowMs = 0, speed = 1f)
        val session = recorder.stop(nowMs = 60_000)!!

        assertEquals("e1", session.episodeId)
        assertEquals("p1", session.podcastId)
        assertEquals("Northbound Signal", session.podcastTitle)
        assertEquals(listOf("Science"), session.categories)
    }

    @Test
    fun `silence trimming reports zero, because it is not implemented`() {
        recorder.start(track, nowMs = 0, speed = 2f)
        val session = recorder.stop(nowMs = 600_000)!!

        // Reporting a number here would be inventing one.
        assertEquals(0L, session.silenceSavedMs)
        assertEquals(0L, session.introOutroSkippedMs)
    }
}
