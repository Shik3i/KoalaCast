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

        var now = 0L
        repeat(18) {
            tap.publishBands(loud)
            now += FRAME_NANOS
            tap.copyBandsInto(out, peaks, now)
        }
        val risen = out[0]

        val silent = FloatArray(SPECTRUM_BANDS)
        repeat(18) {
            tap.publishBands(silent)
            now += FRAME_NANOS
            tap.copyBandsInto(out, peaks, now)
        }

        assertTrue("should be most of the way up, got $risen", risen > 0.9f)
        assertTrue("should still be falling, got ${out[0]}", out[0] > 0.1f)
        // The peak marker outlives the bar it was set by; that is its whole job.
        assertTrue(peaks[0] > out[0])
    }

    @Test
    fun `the display advances at the same speed whatever the refresh rate is`() {
        // The bug this guards, and the one behind "it updates once or twice a
        // second": the envelope used to take one step per queued spectrum plus one
        // step toward silence per display frame that found the queue empty, so a
        // 120 Hz panel — which outruns the 86 Hz analyser on most frames — spent
        // most of its frames dragging the bars down, and the shape pumped at
        // whatever rate the decoder happened to hand over PCM.
        val slow = AmplitudeTap()
        val fast = AmplitudeTap()
        val loud = FloatArray(SPECTRUM_BANDS) { 1f }
        val out = FloatArray(SPECTRUM_BANDS)

        // A quarter of a second of the same audio, drawn at 60 Hz and at 240 Hz.
        drive(slow, loud, frames = 15, frameNanos = 1_000_000_000L / 60, seconds = 0.25f)
        val atSixty = out.also { slow.copyBandsInto(it, null, 250_000_000L) }[0]
        drive(fast, loud, frames = 60, frameNanos = 1_000_000_000L / 240, seconds = 0.25f)
        val atTwoForty = FloatArray(SPECTRUM_BANDS).also { fast.copyBandsInto(it, null, 250_000_000L) }[0]

        assertEquals("refresh rate must not change how fast the shape moves", atSixty, atTwoForty, 0.02f)
    }

    @Test
    fun `an empty queue holds the shape rather than pulling it toward silence`() {
        // The analyser produces about 86 spectra a second, so a 120 Hz display
        // finds nothing new on most frames. That says the panel is fast, not that
        // the audio stopped.
        val tap = AmplitudeTap()
        val loud = FloatArray(SPECTRUM_BANDS) { 1f }
        val out = FloatArray(SPECTRUM_BANDS)

        var now = 0L
        repeat(20) {
            tap.publishBands(loud)
            now += FRAME_NANOS
            tap.copyBandsInto(out, null, now)
        }
        val settled = out[0]

        // Two frames with nothing new, which is the ordinary case at 120 Hz.
        now += FRAME_NANOS
        tap.copyBandsInto(out, null, now)
        now += FRAME_NANOS
        tap.copyBandsInto(out, null, now)

        assertTrue("held $settled, dropped to ${out[0]}", out[0] > settled * 0.97f)
    }

    @Test
    fun `a genuine stall empties the display`() {
        // The other half of the same rule: if nothing arrives for long enough that
        // playback has actually stopped, the bars must fall rather than hang.
        val tap = AmplitudeTap()
        val out = FloatArray(SPECTRUM_BANDS)

        var now = 0L
        repeat(20) {
            tap.publishBands(FloatArray(SPECTRUM_BANDS) { 1f })
            now += FRAME_NANOS
            tap.copyBandsInto(out, null, now)
        }

        repeat(120) {
            now += FRAME_NANOS
            tap.copyBandsInto(out, null, now)
        }

        assertTrue("should have emptied, got ${out[0]}", out[0] < 0.1f)
    }

    @Test
    fun `a transient the cursor passes over between frames is not lost`() {
        // The peak across everything the playout cursor walked over, not the one
        // spectrum it happened to land on: a consonant between two display frames
        // is exactly the detail a listener recognises.
        val tap = AmplitudeTap()
        val out = FloatArray(SPECTRUM_BANDS)
        // The first call places the cursor; spectra published before it are behind
        // the playhead by definition.
        tap.copyBandsInto(out, null, 0L)

        repeat(4) { tap.publishBands(FloatArray(SPECTRUM_BANDS)) }
        tap.publishBands(FloatArray(SPECTRUM_BANDS) { 1f })
        repeat(4) { tap.publishBands(FloatArray(SPECTRUM_BANDS)) }

        // One slow frame, long enough for the cursor to cross all nine.
        tap.copyBandsInto(out, null, 200_000_000L)

        assertTrue("the loud spectrum must still show, got ${out[0]}", out[0] > 0.1f)
    }

    @Test
    fun `the display gets fresh spectra between decoder bursts`() {
        // The bug this guards, and the one that made it look like a few stills a
        // second: the sink is fed in bursts about 260 ms apart, so consuming
        // "everything available" moved the target four times a second and held it
        // in between. Played out on the wall clock instead, a burst's worth of
        // spectra is spread across the frames that follow it.
        val tap = AmplitudeTap()
        val out = FloatArray(SPECTRUM_BANDS)
        var now = 0L
        tap.copyBandsInto(out, null, now)

        var framesWithNewData = 0
        var frames = 0
        // Three bursts of a quarter second of audio, delivered all at once.
        repeat(3) {
            repeat(22) { tap.publishBands(FloatArray(SPECTRUM_BANDS) { 0.5f }) }
            repeat(15) {
                now += FRAME_NANOS
                val before = tap.visualDelaySeconds
                tap.copyBandsInto(out, null, now)
                frames++
                if (tap.consumedLastFrame > 0) framesWithNewData++
                check(before >= 0f)
            }
        }

        // At 60 Hz against 86 spectra a second, most frames should carry new
        // information. Draining on arrival gave three.
        assertTrue(
            "only $framesWithNewData of $frames frames had new data",
            framesWithNewData > frames / 2,
        )
    }

    @Test
    fun `a long backlog is skipped rather than replayed`() {
        val tap = AmplitudeTap()
        val out = FloatArray(SPECTRUM_BANDS)
        // Far more than the ring holds: the renderer must not walk through audio
        // the listener heard a second ago just to catch up.
        repeat(500) { tap.publishBands(FloatArray(SPECTRUM_BANDS) { 1f }) }
        tap.copyBandsInto(out, null, FRAME_NANOS)

        assertTrue(out[0] >= 0f)
        assertTrue("must not reach the top in one frame, got ${out[0]}", out[0] < 1f)
    }

    /** Publishes [seconds] of [spectrum] spread over [frames] display frames. */
    private fun drive(tap: AmplitudeTap, spectrum: FloatArray, frames: Int, frameNanos: Long, seconds: Float) {
        val scratch = FloatArray(SPECTRUM_BANDS)
        val perFrame = (ANALYSIS_RATE * seconds / frames).toInt().coerceAtLeast(1)
        var now = 0L
        repeat(frames) {
            repeat(perFrame) { tap.publishBands(spectrum) }
            now += frameNanos
            tap.copyBandsInto(scratch, null, now)
        }
    }

    private companion object {
        const val FRAME_NANOS = 1_000_000_000L / 60
        /** Spectra a second the analyser produces: a 2048 window hopped by 512. */
        const val ANALYSIS_RATE = 86f
    }
}
