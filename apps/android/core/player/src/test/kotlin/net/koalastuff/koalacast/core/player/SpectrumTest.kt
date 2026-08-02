package net.koalastuff.koalacast.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SpectrumTest {

    @Test
    fun `transform of a single bin tone concentrates in that bin`() {
        val size = 64
        val bin = 5
        val re = FloatArray(size) { sin(2.0 * PI * bin * it / size).toFloat() }
        val im = FloatArray(size)

        fftInPlace(re, im)

        val magnitudes = FloatArray(size / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        val loudest = magnitudes.indices.maxByOrNull { magnitudes[it] }
        assertEquals(bin, loudest)
        // Everything that is not the tone should be numerical dust, not leakage.
        magnitudes.forEachIndexed { index, magnitude ->
            if (index != bin) assertTrue("bin $index leaked $magnitude", magnitude < 0.01f)
        }
    }

    @Test
    fun `band edges rise and never leave a band empty`() {
        val edges = spectrumBandEdges(sampleRateHz = 44_100)

        assertEquals(SPECTRUM_BANDS + 1, edges.size)
        for (band in 0 until SPECTRUM_BANDS) {
            assertTrue("band $band is empty", edges[band + 1] > edges[band])
        }
        assertTrue(edges.last() <= SPECTRUM_FFT_SIZE / 2)
    }

    @Test
    fun `low bands stay narrow so speech is not crammed into one bar`() {
        val edges = spectrumBandEdges(sampleRateHz = 44_100)

        // Log spacing only earns its keep if the bottom of the range is finer than
        // the top. A linear split would make these two spans equal.
        val lowestSpan = edges[1] - edges[0]
        val highestSpan = edges[SPECTRUM_BANDS] - edges[SPECTRUM_BANDS - 1]
        assertTrue("low $lowestSpan should be finer than high $highestSpan", lowestSpan < highestSpan)
    }

    @Test
    fun `a mid tone lights its own band and leaves the far ones dark`() {
        val sampleRate = 44_100
        val toneHz = 1_000.0
        val window = hannWindow(SPECTRUM_FFT_SIZE)
        val re = FloatArray(SPECTRUM_FFT_SIZE) {
            (sin(2.0 * PI * toneHz * it / sampleRate) * window[it]).toFloat()
        }
        val im = FloatArray(SPECTRUM_FFT_SIZE)
        fftInPlace(re, im)

        val bands = FloatArray(SPECTRUM_BANDS)
        reduceToBands(re, im, spectrumBandEdges(sampleRate), bands)

        val loudest = bands.indices.maxByOrNull { bands[it] }!!
        // 1 kHz sits in the upper middle of a 60 Hz–12 kHz log sweep.
        assertTrue("loudest band was $loudest", loudest in 22..30)
        assertTrue(bands[loudest] > 0.6f)
        assertTrue("bottom band should be quiet", bands[0] < 0.2f)
        assertTrue("top band should be quiet", bands[SPECTRUM_BANDS - 1] < 0.2f)
    }

    @Test
    fun `silence reads as an empty spectrum rather than a floor of noise`() {
        val re = FloatArray(SPECTRUM_FFT_SIZE)
        val im = FloatArray(SPECTRUM_FFT_SIZE)
        fftInPlace(re, im)

        val bands = FloatArray(SPECTRUM_BANDS)
        reduceToBands(re, im, spectrumBandEdges(44_100), bands)

        assertTrue(bands.all { it == 0f })
    }

    @Test
    fun `bands rise fast and fall slowly`() {
        val tap = AmplitudeTap()
        val loud = FloatArray(SPECTRUM_BANDS) { 1f }
        val out = FloatArray(SPECTRUM_BANDS)
        val peaks = FloatArray(SPECTRUM_BANDS)

        // Eight steps is the per-frame ceiling, so drive it over several frames —
        // which is what a display doing 60 Hz against 86 spectra a second does.
        repeat(6) {
            repeat(8) { tap.publishBands(loud) }
            tap.copyBandsInto(out, peaks)
        }
        val risen = out[0]

        repeat(6) {
            repeat(8) { tap.publishBands(FloatArray(SPECTRUM_BANDS)) }
            tap.copyBandsInto(out, peaks)
        }

        assertTrue("should be most of the way up, got $risen", risen > 0.9f)
        assertTrue("should still be falling, got ${out[0]}", out[0] > 0.1f)
        // The peak marker outlives the bar it was set by; that is its whole job.
        assertTrue(peaks[0] > out[0])
    }

    @Test
    fun `every published spectrum advances the filter, not just the newest`() {
        // The bug this guards: a decoder hands over a burst, the audio thread
        // computes a dozen spectra from it, and the display used to see only the
        // last one — so the shape stepped once per burst instead of moving.
        val burst = AmplitudeTap()
        val single = AmplitudeTap()
        val loud = FloatArray(SPECTRUM_BANDS) { 1f }
        val out = FloatArray(SPECTRUM_BANDS)

        repeat(8) { burst.publishBands(loud) }
        burst.copyBandsInto(out)
        val afterBurst = out[0]

        single.publishBands(loud)
        single.copyBandsInto(out)
        val afterOne = out[0]

        assertTrue("burst $afterBurst should outrun single $afterOne", afterBurst > afterOne * 1.5f)
    }

    @Test
    fun `a long backlog is skipped rather than replayed`() {
        val tap = AmplitudeTap()
        val out = FloatArray(SPECTRUM_BANDS)
        // Far more than the ring holds: the renderer must not walk through audio
        // the listener heard a second ago just to catch up.
        repeat(500) { tap.publishBands(FloatArray(SPECTRUM_BANDS) { 1f }) }
        tap.copyBandsInto(out)

        assertTrue(out[0] > 0f)
        assertTrue("must not reach the top in one frame, got ${out[0]}", out[0] < 1f)
    }
}
