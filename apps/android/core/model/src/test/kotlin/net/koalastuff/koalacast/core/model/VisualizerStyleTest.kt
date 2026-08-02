package net.koalastuff.koalacast.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualizerStyleTest {
    @Test
    fun `all persisted visualizer ids round trip`() {
        VisualizerStyle.entries.forEach { style ->
            assertEquals(style, VisualizerStyle.fromId(style.id))
        }
    }

    @Test
    fun `unknown visualizer ids stay safely disabled`() {
        assertEquals(VisualizerStyle.OFF, VisualizerStyle.fromId("unknown"))
        assertEquals(VisualizerStyle.OFF, VisualizerStyle.fromId(null))
    }

    @Test
    fun `only bar shaped visualizers request the spectrum`() {
        assertTrue(VisualizerStyle.WAVEFORM.needsSpectrum)
        assertTrue(VisualizerStyle.DOTS.needsSpectrum)
        assertTrue(VisualizerStyle.BARS.needsSpectrum)
        assertFalse(VisualizerStyle.PULSE.needsSpectrum)
        assertFalse(VisualizerStyle.LEVEL.needsSpectrum)
    }
}
