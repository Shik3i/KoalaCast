package net.koalastuff.koalacast.core.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

// Calibrated against spoken-word material: below -48 dBFS is effectively room
// noise, ordinary speech occupies the middle, and mastered peaks retain headroom.
private const val AMPLITUDE_NOISE_FLOOR = 0.004f
private const val AMPLITUDE_GAIN = 3.8f
// Calibrated for 120 envelope frames per second: equivalent to the previous
// 30 Hz attack/release timing, but with enough temporal detail for 120 Hz panels.
private const val AMPLITUDE_ATTACK = 0.3313f
private const val AMPLITUDE_RELEASE = 0.0342f
// Applied once per *analysed* spectrum — about 86 a second — not once per drawn
// frame, so the shape moves at the same speed whatever the display does. Roughly
// 40 ms to rise and 150 ms to fall.
private const val BAND_ATTACK = 0.45f
private const val BAND_RELEASE = 0.09f
/** Peak markers fall about a third of full scale per second. */
private const val BAND_PEAK_FALL = 0.004f
internal const val ENVELOPE_UPDATES_PER_SECOND = 120
private const val ENVELOPE_FRAME_NANOS = 1_000_000_000L / ENVELOPE_UPDATES_PER_SECOND
private const val MAX_RENDER_GAP_NANOS = 1_000_000_000L

/**
 * How loud the audio is right now, as a 0..1 envelope, for anything that wants to
 * draw it.
 *
 * The obvious API for this — `android.media.audiofx.Visualizer` — requires
 * `RECORD_AUDIO`, which an app whose onboarding promises that listening stays on
 * the device has no business asking for. (`LoudnessEnhancer`, attached elsewhere in
 * this service, needs no such permission; the asymmetry is easy to miss.) So the
 * signal is taken from the player's own decoded PCM instead, where no permission is
 * involved because it is already our audio.
 *
 * PlaybackService shares a process with the UI, so this is a plain singleton the UI
 * can read. If the service is ever given its own `android:process`, this becomes an
 * IPC problem and needs redesigning rather than patching.
 */
@Singleton
class AmplitudeTap @Inject constructor() {

    /** The most recent envelope frame consumed by the renderer. */
    @Volatile
    private var level: Float = 0f

    /**
     * Decoders hand PCM to the sink in bursts, often hundreds of milliseconds at
     * once. Publishing only the latest value makes the UI freeze between those
     * bursts. This lock-free single-producer/single-consumer ring preserves the
     * short envelope frames and lets the UI consume exactly one per render tick.
     */
    private val pending = FloatArray(PENDING_CAPACITY)

    @Volatile
    private var pendingWrite = 0L

    @Volatile
    private var pendingRead = 0L

    // Renderer-only interpolation state. The PCM producer never touches these;
    // [resetGeneration] tells the next display frame to discard them safely.
    private var interpolationStart = 0f
    private var interpolationEnd = 0f
    private var hasInterpolationEnd = false
    private var interpolationNanos = 0L
    private var lastRenderNanos = Long.MIN_VALUE
    private var observedResetGeneration = 0L

    @Volatile
    private var resetGeneration = 0L

    /**
     * Set while something is actually drawing. The processor stays in the chain
     * either way — inserting and removing it mid-playback would reconfigure the
     * audio sink — but it does no arithmetic for the listeners who never turn a
     * visualiser on, which is most of them.
     */
    @Volatile
    var listening: Boolean = false

    /**
     * Returns the amplitude for this display frame. PCM is sampled at 120 Hz and
     * consumed according to elapsed frame time, so 60 Hz displays advance two
     * source samples, 90 Hz displays alternate one and two, and 120 Hz displays
     * advance one. Faster panels interpolate instead of repeating a hard step.
     */
    fun levelAt(frameTimeNanos: Long): Float {
        syncRendererAfterReset()

        if (lastRenderNanos == Long.MIN_VALUE) {
            lastRenderNanos = frameTimeNanos
            dequeue()?.let { first ->
                interpolationStart = first
                level = first
            }
            dequeue()?.let { next ->
                interpolationEnd = next
                hasInterpolationEnd = true
            }
            return level
        }

        val elapsed = (frameTimeNanos - lastRenderNanos).coerceIn(0L, MAX_RENDER_GAP_NANOS)
        lastRenderNanos = frameTimeNanos

        if (!hasInterpolationEnd) {
            dequeue()?.let { next ->
                interpolationEnd = next
                hasInterpolationEnd = true
                interpolationNanos = 0L
            }
        }

        interpolationNanos += elapsed
        while (hasInterpolationEnd && interpolationNanos >= ENVELOPE_FRAME_NANOS) {
            interpolationStart = interpolationEnd
            level = interpolationStart
            interpolationNanos -= ENVELOPE_FRAME_NANOS

            val next = dequeue()
            if (next == null) {
                hasInterpolationEnd = false
                interpolationNanos = 0L
            } else {
                interpolationEnd = next
            }
        }

        if (hasInterpolationEnd) {
            val fraction = interpolationNanos.toFloat() / ENVELOPE_FRAME_NANOS.toFloat()
            level = interpolationStart + (interpolationEnd - interpolationStart) * fraction
        }
        return level
    }

    private fun dequeue(): Float? {
        val end = pendingWrite
        var read = pendingRead
        if (read >= end) return null

        if (end - read > PENDING_CAPACITY.toLong()) {
            read = end - PENDING_CAPACITY
        }
        val value = pending[(read % PENDING_CAPACITY).toInt()]
        pendingRead = read + 1
        return value
    }

    private fun syncRendererAfterReset() {
        val generation = resetGeneration
        if (observedResetGeneration == generation) return
        observedResetGeneration = generation
        interpolationStart = 0f
        interpolationEnd = 0f
        hasInterpolationEnd = false
        interpolationNanos = 0L
        lastRenderNanos = Long.MIN_VALUE
    }

    /**
     * Band energies the audio thread produced, low frequencies first, as a ring
     * of whole spectra.
     *
     * This was a single flat array holding "the newest spectrum", on the
     * reasoning that a frame the display never showed is of no interest. That
     * reasoning is wrong for the same reason it was wrong for the envelope, and
     * the note above [pending] says why: decoders hand over PCM in bursts of
     * hundreds of milliseconds, so an entire burst's worth of spectra was
     * computed and then overwritten, and the display saw exactly one per burst.
     * That is the two-updates-per-second crawl — the level meter looked fine
     * next to it only because it had this ring and the spectrum did not.
     */
    private val pendingBands = Array(PENDING_SPECTRA) { FloatArray(SPECTRUM_BANDS) }

    @Volatile
    private var bandsWrite = 0L

    private var bandsRead = 0L

    // Renderer-only filter state, advanced once per analysed spectrum.
    private val smoothedBands = FloatArray(SPECTRUM_BANDS)
    private val peakBands = FloatArray(SPECTRUM_BANDS)

    /**
     * Fills [out] with the current band heights and [peaks], if given, with the
     * slow-falling peak markers. Both must be [SPECTRUM_BANDS] long.
     *
     * Fast up, slow down — the standard bar-meter asymmetry. A symmetric filter
     * either misses transients or leaves every bar twitching.
     */
    fun copyBandsInto(out: FloatArray, peaks: FloatArray? = null) {
        // Every spectrum the audio thread produced since the last display frame
        // is folded through the filter, not just the newest. That makes the
        // attack and release run at the rate the audio is analysed — about 86
        // steps a second — rather than at whatever rate the display happens to
        // redraw, so the shape moves identically on a 60 Hz phone, a 120 Hz one
        // and a slow emulator.
        var steps = 0
        while (bandsRead < bandsWrite) {
            if (bandsWrite - bandsRead > PENDING_SPECTRA) {
                // Fell far behind: skip the stale ones rather than animate through
                // audio the listener heard a second ago.
                bandsRead = bandsWrite - PENDING_SPECTRA
            }
            advanceBands(pendingBands[(bandsRead % PENDING_SPECTRA).toInt()])
            bandsRead++
            steps++
            if (steps >= MAX_BAND_STEPS_PER_FRAME) break
        }
        // Nothing new — still let the release and the peak markers fall, or a
        // pause would freeze the bars mid-air.
        if (steps == 0) advanceBands(null)

        for (band in smoothedBands.indices) {
            if (band < out.size) out[band] = smoothedBands[band]
            if (peaks != null && band < peaks.size) peaks[band] = peakBands[band]
        }
    }

    /** One filter step toward [target], or toward silence when it is null. */
    private fun advanceBands(target: FloatArray?) {
        for (band in smoothedBands.indices) {
            val next = target?.getOrElse(band) { 0f } ?: 0f
            val previous = smoothedBands[band]
            val blend = if (next > previous) BAND_ATTACK else BAND_RELEASE
            val value = previous + (next - previous) * blend
            smoothedBands[band] = value
            peakBands[band] = maxOf(value, peakBands[band] - BAND_PEAK_FALL)
        }
    }

    internal fun publishBands(source: FloatArray) {
        val index = bandsWrite
        val slot = pendingBands[(index % PENDING_SPECTRA).toInt()]
        source.copyInto(slot, endIndex = minOf(source.size, slot.size))
        // Volatile publication happens after the array write.
        bandsWrite = index + 1
    }

    internal fun publish(value: Float) {
        val index = pendingWrite
        pending[(index % PENDING_CAPACITY).toInt()] = value
        // Volatile publication happens after the array write.
        pendingWrite = index + 1
    }

    internal fun reset() {
        level = 0f
        smoothedBands.fill(0f)
        peakBands.fill(0f)
        bandsRead = bandsWrite
        pendingRead = pendingWrite
        resetGeneration++
    }

    private companion object {
        const val PENDING_CAPACITY = 128

        /** About a third of a second of spectra at the analysis rate. */
        const val PENDING_SPECTRA = 32

        /**
         * A ceiling on catch-up work per display frame. Without it, resuming from
         * a long stall would run the filter over a full backlog inside one frame
         * and drop the next one.
         */
        const val MAX_BAND_STEPS_PER_FRAME = 8
    }
}

/**
 * Turns the PCM going past into an RMS envelope on [tap].
 *
 * Deliberately a [TeeAudioProcessor] sink rather than a hand-written
 * `BaseAudioProcessor`: the pass-through half of an observing processor is fiddly
 * to get right — a naive `replaceOutputBuffer(n).put(inputBuffer)` can be handed
 * its own buffer and dies with "The source buffer is this buffer" — and Media3
 * already ships that half, correctly, for exactly this purpose. This code only
 * reads; it is structurally incapable of changing what the listener hears.
 *
 * [handleBuffer] runs on the audio thread, so it allocates nothing and blocks on
 * nothing.
 */
@OptIn(UnstableApi::class)
internal class AmplitudeBufferSink(
    private val tap: AmplitudeTap,
) : TeeAudioProcessor.AudioBufferSink {

    private var smoothed = 0f
    private var pcm16 = false
    private var windowBytes = 0
    private var bytesInWindow = 0
    private var sumInWindow = 0.0
    private var samplesInWindow = 0

    /**
     * One sample per audio frame — the first channel — rather than every eighth
     * sample as before. The envelope was happy with a decimated signal; an FFT is
     * not, because decimating without a low-pass filter folds everything above the
     * new Nyquist back down and paints energy into bands that hold none.
     */
    private var strideBytes = 2

    // Everything below is preallocated: [handleBuffer] runs on the audio thread.
    private val fftReal = FloatArray(SPECTRUM_FFT_SIZE)
    private val fftImaginary = FloatArray(SPECTRUM_FFT_SIZE)
    private val fftWindow = hannWindow(SPECTRUM_FFT_SIZE)
    private val fftFrame = FloatArray(SPECTRUM_FFT_SIZE)
    private var fftFill = 0
    private val bandScratch = FloatArray(SPECTRUM_BANDS)
    private var bandEdges = spectrumBandEdges(DEFAULT_SAMPLE_RATE)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        // Any other encoding is left alone rather than misread as shorts and drawn
        // as noise. In practice every codec this app plays decodes to 16-bit.
        pcm16 = encoding == C.ENCODING_PCM_16BIT
        smoothed = 0f
        windowBytes = envelopeWindowBytes(sampleRateHz, channelCount)
        strideBytes = channelCount.coerceAtLeast(1) * 2
        bandEdges = spectrumBandEdges(sampleRateHz)
        fftFill = 0
        bytesInWindow = 0
        sumInWindow = 0.0
        samplesInWindow = 0
        tap.reset()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!pcm16 || !tap.listening) {
            if (smoothed != 0f || bytesInWindow != 0) {
                smoothed = 0f
                bytesInWindow = 0
                sumInWindow = 0.0
                samplesInWindow = 0
                fftFill = 0
                tap.reset()
            }
            return
        }

        val position = buffer.position()
        val limit = buffer.limit()
        val order = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // Keep windows continuous across ByteBuffer boundaries. Finishing a partial
        // window at every decoder callback couples animation speed to buffer size
        // and causes precisely the visible one-second stepping this tap avoids.
        var index = position
        while (index + 1 < limit) {
            val sample = buffer.getShort(index) / Short.MAX_VALUE.toFloat()
            sumInWindow += (sample * sample).toDouble()
            samplesInWindow++

            val representedBytes = minOf(strideBytes, limit - index)
            bytesInWindow += representedBytes
            if (bytesInWindow >= windowBytes) {
                val rms = sqrt(sumInWindow / samplesInWindow).toFloat()
                smoothed = nextAmplitude(smoothed, rms)
                tap.publish(smoothed)
                bytesInWindow = 0
                sumInWindow = 0.0
                samplesInWindow = 0
            }

            fftFrame[fftFill++] = sample
            if (fftFill == SPECTRUM_FFT_SIZE) {
                publishSpectrum()
                // Overlapping frames by three quarters: a hop of a whole 2048
                // window is only 21 spectra per second, which visibly steps. A
                // quarter-window hop puts a fresh spectrum on screen roughly every
                // 12 ms for four times the arithmetic on a few thousand floats.
                val hop = SPECTRUM_FFT_SIZE / 4
                fftFrame.copyInto(fftFrame, 0, hop, SPECTRUM_FFT_SIZE)
                fftFill = SPECTRUM_FFT_SIZE - hop
            }

            index += strideBytes
        }

        buffer.order(order)
    }

    private fun publishSpectrum() {
        for (i in 0 until SPECTRUM_FFT_SIZE) {
            fftReal[i] = fftFrame[i] * fftWindow[i]
            fftImaginary[i] = 0f
        }
        fftInPlace(fftReal, fftImaginary)
        reduceToBands(fftReal, fftImaginary, bandEdges, bandScratch)
        tap.publishBands(bandScratch)
    }

    private companion object {
        /** Only used before the first [flush] tells us the real rate. */
        const val DEFAULT_SAMPLE_RATE = 44_100
    }
}

internal fun envelopeWindowBytes(sampleRateHz: Int, channelCount: Int): Int {
    val frameBytes = channelCount.coerceAtLeast(1) * 2
    val raw = sampleRateHz.coerceAtLeast(1) * frameBytes / ENVELOPE_UPDATES_PER_SECOND
    return (raw / frameBytes).coerceAtLeast(1) * frameBytes
}

internal fun nextAmplitude(previous: Float, rms: Float): Float {
    val normalised = ((rms - AMPLITUDE_NOISE_FLOOR) * AMPLITUDE_GAIN).coerceIn(0f, 1f)
    val blend = if (normalised > previous) AMPLITUDE_ATTACK else AMPLITUDE_RELEASE
    return previous + (normalised - previous) * blend
}
