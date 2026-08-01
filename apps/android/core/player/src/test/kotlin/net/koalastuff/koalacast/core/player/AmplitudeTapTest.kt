package net.koalastuff.koalacast.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmplitudeTapTest {
    @Test
    fun `decoder burst is consumed one visual frame at a time`() {
        val tap = AmplitudeTap()
        tap.publish(0.15f)
        tap.publish(0.45f)
        tap.publish(0.75f)

        assertEquals(0.15f, tap.level(), 0.0001f)
        assertEquals(0.45f, tap.level(), 0.0001f)
        assertEquals(0.75f, tap.level(), 0.0001f)
        assertEquals(0.75f, tap.level(), 0.0001f)
    }

    @Test
    fun `history advances with rendered frames instead of decoder burst`() {
        val tap = AmplitudeTap()
        val history = FloatArray(3)
        tap.publish(0.2f)
        tap.publish(0.4f)

        tap.copyHistoryInto(history)
        assertTrue(history.all { it == 0f })

        tap.level()
        tap.copyHistoryInto(history)
        assertEquals(listOf(0f, 0f, 0.2f), history.toList())
    }

    @Test
    fun `overflow skips stale audio instead of increasing visual latency`() {
        val tap = AmplitudeTap()
        repeat(70) { tap.publish((it + 1).toFloat()) }

        assertEquals(7f, tap.level(), 0.0001f)
        assertEquals(8f, tap.level(), 0.0001f)
    }

    @Test
    fun `envelope window follows sample rate and channel count`() {
        assertEquals(5_880, envelopeWindowBytes(sampleRateHz = 44_100, channelCount = 2))
        assertEquals(3_200, envelopeWindowBytes(sampleRateHz = 48_000, channelCount = 1))
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
        repeat(60) { level = nextAmplitude(level, rms = 0f) }

        assertTrue(afterOneSilentWindow > 0.75f)
        assertTrue(level < 0.07f)
    }
}
