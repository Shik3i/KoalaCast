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
    fun `an automatic intro or outro jump is reported separately`() {
        recorder.start(track, nowMs = 0, speed = 1f)
        recorder.onIntroOutroSkip(45_000)
        recorder.onIntroOutroSkip(-1)

        val session = recorder.stop(nowMs = 60_000)!!
        assertEquals(45_000L, session.introOutroSkippedMs)
        assertEquals(0L, session.manualSkippedMs)
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
    fun `without a reported position, silence saving stays zero rather than guessed`() {
        recorder.start(track, nowMs = 0, speed = 2f)
        val session = recorder.stop(nowMs = 600_000)!!

        assertEquals(0L, session.silenceSavedMs)
    }

    @Test
    fun `a playhead that outran the clock is silence the trimmer removed`() {
        // Ten minutes at 1x, but the playhead covered twelve: the two minutes
        // the listener never spent are trimmed silence.
        recorder.start(track, nowMs = 0, speed = 1f, positionMs = 0)
        val session = recorder.stop(nowMs = 600_000, positionMs = 720_000)!!

        assertEquals(120_000L, session.silenceSavedMs)
        assertEquals(0L, session.speedSavedMs)
        // Audio consumed is what the clock bought plus what the trimmer skipped.
        assertEquals(720_000L, session.audioListenedMs)
    }

    @Test
    fun `speed and silence saving are reported separately, not conflated`() {
        // Ten minutes at 1.5x should consume fifteen; the playhead covered
        // seventeen, so two of those minutes came from trimming, not speed.
        recorder.start(track, nowMs = 0, speed = 1.5f, positionMs = 0)
        val session = recorder.stop(nowMs = 600_000, positionMs = 1_020_000)!!

        assertEquals(300_000L, session.speedSavedMs)
        assertEquals(120_000L, session.silenceSavedMs)
    }

    @Test
    fun `a skip is not mistaken for trimmed silence`() {
        // The playhead jumped 30s forward; that is skipped, not silence, so
        // nothing should be attributed to the trimmer.
        recorder.start(track, nowMs = 0, speed = 1f, positionMs = 0)
        recorder.onManualSkip(30_000)
        val session = recorder.stop(nowMs = 600_000, positionMs = 630_000)!!

        assertEquals(0L, session.silenceSavedMs)
        assertEquals(30_000L, session.manualSkippedMs)
    }

    @Test
    fun `a playhead behind the clock reports nothing rather than a negative`() {
        // Buffering or a backward seek: never report less than zero.
        recorder.start(track, nowMs = 0, speed = 1f, positionMs = 500_000)
        val session = recorder.stop(nowMs = 600_000, positionMs = 400_000)!!

        assertEquals(0L, session.silenceSavedMs)
    }
}
