package net.koalastuff.koalacast.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visualiser sits at the *input* of the audio sink, ahead of an AudioTrack
 * buffer that Media3 sizes at 250 ms or more. Without compensation everything it
 * draws is audio the listener has not heard yet — measured at 430–530 ms on an
 * API 36 emulator, which is far past the point where a picture stops looking like
 * it belongs to the sound.
 *
 * These cover the measurement and the delay line it drives.
 */
class VisualDelayTest {

    @Test
    fun `the buffer the sink fills at startup is what the display holds back`() {
        val tap = tapPlaying(bufferSeconds = 0.3f)
        val out = FloatArray(SPECTRUM_BANDS)
        tap.copyBandsInto(out, null, FRAME)

        assertEquals(0.3f, tap.visualDelaySeconds, 0.02f)
    }

    @Test
    fun `a pause is not mistaken for the buffer draining`() {
        // Playback stops without the sink being flushed, so the buffer stays as
        // full as it was. Counting the pause as elapsed time would read as the
        // audio having caught up and would collapse the compensation to nothing.
        val tap = tapPlaying(bufferSeconds = 0.3f)
        val out = FloatArray(SPECTRUM_BANDS)
        tap.copyBandsInto(out, null, FRAME)

        // Ten seconds of silence from the sink, then playback resumes.
        var now = STEADY_STATE_END_NANOS + 10_000_000_000L
        repeat(50) {
            now += 10_000_000L
            tap.publishFlow(441, now)
        }
        tap.copyBandsInto(out, null, FRAME * 2)

        assertEquals(0.3f, tap.visualDelaySeconds, 0.03f)
    }

    @Test
    fun `playback speed is taken out of the measurement`() {
        // The tap runs before Sonic, so at 2x the sink pulls two seconds of media
        // for every second of wall clock. Left uncorrected the lead would grow
        // without bound.
        val tap = tapPlaying(bufferSeconds = 0.3f, speed = 2f)
        val out = FloatArray(SPECTRUM_BANDS)
        tap.copyBandsInto(out, null, FRAME)

        assertTrue(
            "lead must stay bounded at 2x, got ${tap.visualDelaySeconds}",
            tap.visualDelaySeconds < 0.5f,
        )
    }

    @Test
    fun `spectra are drawn only once the audio they describe has been heard`() {
        val tap = tapPlaying(bufferSeconds = 0.3f)
        val out = FloatArray(SPECTRUM_BANDS)
        val silent = FloatArray(SPECTRUM_BANDS)
        val loud = FloatArray(SPECTRUM_BANDS) { 1f }

        // Establish the lead, then hand over a burst of silence followed by a
        // loud passage. The loud part is still inside the sink's buffer.
        tap.copyBandsInto(out, null, FRAME)
        repeat(26) { tap.publishBands(silent) }
        repeat(10) { tap.publishBands(loud) }
        var now = FRAME
        repeat(10) {
            now += FRAME
            tap.copyBandsInto(out, null, now)
        }
        val whileStillBuffered = out[0]

        // Enough further audio to push that loud passage out of the buffer.
        repeat(40) { tap.publishBands(silent) }
        repeat(20) {
            now += FRAME
            tap.copyBandsInto(out, null, now)
        }

        assertTrue("must not draw unheard audio, got $whileStillBuffered", whileStillBuffered < 0.2f)
        assertTrue("must draw it once heard, got ${out[0]}", out[0] > 0.4f)
    }

    @Test
    fun `a flush drops the measurement rather than carrying it across a seek`() {
        val tap = tapPlaying(bufferSeconds = 0.3f)
        val out = FloatArray(SPECTRUM_BANDS)
        tap.copyBandsInto(out, null, FRAME)
        assertTrue(tap.visualDelaySeconds > 0.2f)

        tap.reset()
        tap.copyBandsInto(out, null, FRAME * 2)

        assertEquals(0f, tap.visualDelaySeconds, 0.0001f)
    }

    /**
     * A tap that has seen [bufferSeconds] of audio dumped into the sink at once —
     * which is what happens when playback starts — and then half a second of
     * steady real-time delivery on top.
     */
    private fun tapPlaying(bufferSeconds: Float, speed: Float = 1f): AmplitudeTap {
        val tap = AmplitudeTap()
        tap.listening = true
        tap.playbackSpeed = speed
        tap.publishSampleRate(SAMPLE_RATE)
        tap.publishFlow((SAMPLE_RATE * bufferSeconds).toInt(), 0L)
        var now = 0L
        repeat(50) {
            now += 10_000_000L
            tap.publishFlow((SAMPLE_RATE / 100f * speed).toInt(), now)
        }
        return tap
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val FRAME = 1_000_000_000L / 60
        const val STEADY_STATE_END_NANOS = 500_000_000L
    }
}
