package net.koalastuff.koalacast.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmplitudeTapTest {
    @Test
    fun `decoder burst is interpolated at display frame time`() {
        val tap = AmplitudeTap()
        tap.publish(0.15f)
        tap.publish(0.45f)
        tap.publish(0.75f)

        val start = 1_000_000_000L
        assertEquals(0.15f, tap.levelAt(start), 0.0001f)
        assertEquals(0.30f, tap.levelAt(start + ENVELOPE_FRAME_NANOS / 2), 0.0001f)
        assertEquals(0.45f, tap.levelAt(start + ENVELOPE_FRAME_NANOS), 0.0001f)
        assertEquals(0.75f, tap.levelAt(start + ENVELOPE_FRAME_NANOS * 2), 0.0001f)
        assertEquals(0.75f, tap.levelAt(start + ENVELOPE_FRAME_NANOS * 3), 0.0001f)
    }

    @Test
    fun `60 hertz display consumes two 120 hertz source frames`() {
        val tap = AmplitudeTap()
        tap.publish(0.1f)
        tap.publish(0.2f)
        tap.publish(0.3f)
        tap.publish(0.4f)

        val start = 1_000_000_000L
        assertEquals(0.1f, tap.levelAt(start), 0.0001f)
        assertEquals(0.3f, tap.levelAt(start + ENVELOPE_FRAME_NANOS * 2), 0.0001f)
    }

    @Test
    fun `overflow skips stale audio instead of increasing visual latency`() {
        val tap = AmplitudeTap()
        repeat(140) { tap.publish((it + 1).toFloat()) }

        val start = 1_000_000_000L
        assertEquals(13f, tap.levelAt(start), 0.0001f)
        assertEquals(14f, tap.levelAt(start + ENVELOPE_FRAME_NANOS), 0.0001f)
    }

    @Test
    fun `envelope window follows sample rate and channel count`() {
        assertEquals(1_468, envelopeWindowBytes(sampleRateHz = 44_100, channelCount = 2))
        assertEquals(800, envelopeWindowBytes(sampleRateHz = 48_000, channelCount = 1))
    }

    @Test
    fun `room noise stays visually silent`() {
        assertEquals(0f, nextAmplitude(previous = 0f, rms = 0.003f), 0.0001f)
    }

    @Test
    fun `spoken word reaches useful visual range quickly`() {
        var level = 0f
        repeat(5) { level = nextAmplitude(level, rms = 0.08f) }

        assertTrue(level in 0.25f..0.35f)
    }

    @Test
    fun `release bridges gaps between syllables without sticking`() {
        var level = 0f
        repeat(8) { level = nextAmplitude(level, rms = 0.25f) }
        val afterOneSilentWindow = nextAmplitude(level, rms = 0f)
        repeat(ENVELOPE_UPDATES_PER_SECOND * 2) { level = nextAmplitude(level, rms = 0f) }

        assertTrue(afterOneSilentWindow > 0.75f)
        assertTrue(level < 0.07f)
    }

    private companion object {
        const val ENVELOPE_FRAME_NANOS = 1_000_000_000L / ENVELOPE_UPDATES_PER_SECOND
    }
}
