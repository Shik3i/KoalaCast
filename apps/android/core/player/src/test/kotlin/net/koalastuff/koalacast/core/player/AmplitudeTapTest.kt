package net.koalastuff.koalacast.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmplitudeTapTest {
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
