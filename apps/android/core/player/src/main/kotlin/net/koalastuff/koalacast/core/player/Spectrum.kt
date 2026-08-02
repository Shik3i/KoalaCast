package net.koalastuff.koalacast.core.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The frequency half of the visualiser signal.
 *
 * A single loudness envelope can only ever drive one shape that swells and
 * shrinks. Anything that looks like the bar meter people expect from a media
 * player — bars that stand still horizontally and only change height, each one
 * answering for a different part of the sound — needs the spectrum, so this
 * takes the same decoded PCM [AmplitudeTap] already sees and runs an FFT over it.
 *
 * Still no `RECORD_AUDIO`: this is our own audio, read on its way to the sink.
 */

/**
 * 2048 points is ~46 ms of audio at 44.1 kHz and ~21 Hz per bin.
 *
 * This is the whole trade. Shorter windows react faster but resolve worse, and
 * at 512 points the bins are 86 Hz apart — wider than the bottom third of a
 * log-spaced display, so those bands collapse onto each other and eighteen bars
 * end up drawing the same number. Longer windows fix that and start showing the
 * syllable rather than the consonant. 2048 leaves the bottom few bands sharing
 * bins, which is invisible, and keeps the display within a frame or two of the
 * sound. The frames overlap by three quarters, so a new spectrum still arrives
 * about every 12 ms.
 */
internal const val SPECTRUM_FFT_SIZE = 2048

/**
 * How many bars a spectrum style draws. Enough to fill a phone's width at a
 * readable bar width without turning each bar into a hairline.
 */
const val SPECTRUM_BANDS = 48

/** Below this is rumble, above it is hiss; neither says anything about speech. */
private const val SPECTRUM_MIN_HZ = 60.0
private const val SPECTRUM_MAX_HZ = 12_000.0

/** The visible window, in dBFS. Quieter than the floor reads as an empty band. */
private const val SPECTRUM_FLOOR_DB = -72f
private const val SPECTRUM_CEILING_DB = -14f

/** Recorded speech rolls off with frequency; without this the right half is dead. */
private const val SPECTRUM_TILT = 1.6f

/**
 * In-place radix-2 Cooley–Tukey FFT over [re] and [im], which must be the same
 * power-of-two length.
 *
 * Hand-written rather than pulled in from a DSP library because it runs on the
 * audio thread: it must allocate nothing, and a dependency that allocates one
 * `Complex[]` per call would drop buffers on cheap devices. Only the real input
 * case is used here, so the imaginary half arrives zeroed.
 */
internal fun fftInPlace(re: FloatArray, im: FloatArray) {
    val n = re.size

    // Bit-reversal permutation, computed by incrementing a reversed counter
    // rather than by reversing each index, so there is no per-element loop.
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }

    var length = 2
    while (length <= n) {
        val angle = -2.0 * PI / length
        val wRealStep = cos(angle).toFloat()
        val wImagStep = sin(angle).toFloat()
        var start = 0
        while (start < n) {
            var wReal = 1f
            var wImag = 0f
            for (offset in 0 until length / 2) {
                val a = start + offset
                val b = a + length / 2
                val tr = wReal * re[b] - wImag * im[b]
                val ti = wReal * im[b] + wImag * re[b]
                re[b] = re[a] - tr
                im[b] = im[a] - ti
                re[a] += tr
                im[a] += ti
                val nextReal = wReal * wRealStep - wImag * wImagStep
                wImag = wReal * wImagStep + wImag * wRealStep
                wReal = nextReal
            }
            start += length
        }
        length = length shl 1
    }
}

/**
 * The FFT bin index each band starts at, plus a final entry for the end of the
 * last band, so a band's bins are `edges[i] until edges[i + 1]`.
 *
 * Log-spaced, because linear spacing puts nine tenths of the display above 4 kHz
 * where speech has almost nothing, and the result is a row of bars in which only
 * the leftmost two ever move. Each band is guaranteed at least one bin so no
 * band renders as a permanent gap.
 */
internal fun spectrumBandEdges(
    sampleRateHz: Int,
    fftSize: Int = SPECTRUM_FFT_SIZE,
    bands: Int = SPECTRUM_BANDS,
): IntArray {
    val usableBins = fftSize / 2
    val nyquist = sampleRateHz.coerceAtLeast(1) / 2.0
    val logMin = ln(SPECTRUM_MIN_HZ)
    val logMax = ln(SPECTRUM_MAX_HZ.coerceAtMost(nyquist).coerceAtLeast(SPECTRUM_MIN_HZ * 2))
    val edges = IntArray(bands + 1)
    for (band in 0..bands) {
        val hz = exp(logMin + (logMax - logMin) * band / bands)
        edges[band] = ((hz / nyquist) * usableBins).toInt().coerceIn(0, usableBins)
    }
    // The bottom bands are narrower than one bin at this FFT size, so they would
    // otherwise collapse onto each other and render as a permanent gap. Pushing
    // each edge past the one before it costs a little accuracy at the very bottom
    // and guarantees every bar has something to report. Safe without an upper
    // clamp only because there are far more usable bins than bands.
    require(bands < usableBins) { "spectrum needs more FFT bins than bands" }
    for (band in 1..bands) {
        if (edges[band] <= edges[band - 1]) edges[band] = edges[band - 1] + 1
    }
    return edges
}

/** A Hann window of [size]. Rectangular windows smear a tone across five bands. */
internal fun hannWindow(size: Int): FloatArray =
    FloatArray(size) { 0.5f - 0.5f * cos(2.0 * PI * it / (size - 1)).toFloat() }

/**
 * Reduces one FFT result to [out] band energies, 0..1, low frequencies first.
 *
 * Peak within a band rather than mean: averaging across a band that spans several
 * kHz buries every transient, and transients are the part a listener recognises.
 */
internal fun reduceToBands(
    re: FloatArray,
    im: FloatArray,
    edges: IntArray,
    out: FloatArray,
) {
    val scale = 2f / re.size
    for (band in out.indices) {
        var peak = 0f
        for (bin in edges[band] until edges[band + 1]) {
            val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin]) * scale
            if (magnitude > peak) peak = magnitude
        }
        val db = if (peak > 1e-7f) 20f * log10(peak) else SPECTRUM_FLOOR_DB
        val normalised = (db - SPECTRUM_FLOOR_DB) / (SPECTRUM_CEILING_DB - SPECTRUM_FLOOR_DB)
        val tilt = 1f + SPECTRUM_TILT * band / (out.size - 1).coerceAtLeast(1)
        out[band] = (normalised * tilt).coerceIn(0f, 1f)
    }
}
