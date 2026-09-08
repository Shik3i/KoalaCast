package net.koalastuff.koalacast.core.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

// Calibrated against spoken-word material: below -48 dBFS is effectively room
// noise, ordinary speech occupies the middle, and mastered peaks retain headroom.
private const val AMPLITUDE_NOISE_FLOOR = 0.004f
private const val AMPLITUDE_GAIN = 3.8f
// Calibrated for 120 envelope frames per second: equivalent to the previous
// 30 Hz attack/release timing, but with enough temporal detail for 120 Hz panels.
private const val AMPLITUDE_ATTACK = 0.3313f
private const val AMPLITUDE_RELEASE = 0.0342f
// Time constants, in seconds, for the band envelope. Expressed as *time* rather
// than as a per-step blend on purpose: a fixed blend is only frame-rate
// independent if the number of steps per second is fixed, and it is not. The
// previous version folded one step per analysed spectrum and one extra step
// toward silence on every display frame that happened to find the queue empty,
// which made the shape a function of how the decoder happened to deliver PCM and
// of the panel's refresh rate. On a 120 Hz phone most frames found the queue
// empty — the analyser produces about 86 spectra a second — so most frames
// dragged the bars downward and the display pumped at the decoder's burst rate
// instead of moving with the audio. That is the "updates once or twice a second"
// symptom.
//
// Roughly 70 ms to rise and 390 ms to fall: quick enough to catch a consonant,
// slow enough that the shape reads as one thing moving.
private const val BAND_ATTACK_TAU_SECONDS = 0.07f
private const val BAND_RELEASE_TAU_SECONDS = 0.39f
/**
 * Peak markers fall a little under half of full scale per second.
 *
 * Slower than this and they stop reading as markers belonging to the bars: on
 * speech, where the loud moments are brief and far apart, a fifth per second left
 * the caps pinned near the top while the bars worked away underneath, so the row
 * read as two unrelated things rather than as a meter with a peak hold.
 */
private const val BAND_PEAK_FALL_PER_SECOND = 0.45f
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
     * How much audio has gone through the tee, and how much wall-clock time was
     * spent doing it, since the last flush.
     *
     * Together these measure the thing this display could not otherwise know: the
     * tee sits at the *input* of the audio sink, ahead of the AudioTrack buffer,
     * which Media3 sizes at 250 ms or more for PCM. Everything the visualiser sees
     * is therefore audio the listener has not heard yet, and drawing it
     * immediately puts the picture a quarter of a second ahead of the sound.
     *
     * Written on the audio thread, read on the renderer's. They are read
     * separately rather than as one atomic pair; a torn read is off by at most one
     * decoder buffer, and the estimate is smoothed over hundreds of frames anyway.
     */
    @Volatile
    private var flowFrames = 0L

    @Volatile
    private var flowWallNanos = 0L

    @Volatile
    private var flowSampleRate = 0

    /** Audio thread only. */
    private var lastFlowNanos = 0L

    /**
     * The current playback speed, which the UI keeps up to date.
     *
     * Needed because the tee runs *before* Sonic: at 1.5x the sink pulls 1.5
     * seconds of audio out of the tee for every second of wall clock, so media
     * time and wall time no longer advance together and the lead below would grow
     * without bound if this were assumed to be 1.
     */
    @Volatile
    var playbackSpeed: Float = 1f

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
     * A ring rather than a single "newest spectrum" slot, for the reason the note
     * above [pending] gives: decoders hand over PCM in bursts of hundreds of
     * milliseconds, so a whole burst's worth of spectra would be computed and
     * overwritten and the display would see exactly one per burst.
     *
     * The ring exists so no transient is *lost*. It is deliberately not a queue
     * the renderer walks one entry per frame — that made the animation's speed a
     * function of the arrival pattern. [copyBandsInto] takes the peak across
     * whatever arrived and lets elapsed time set the pace.
     */
    private val pendingBands = Array(PENDING_SPECTRA) { FloatArray(SPECTRUM_BANDS) }

    @Volatile
    private var bandsWrite = 0L

    private var bandsRead = 0L

    // Renderer-only filter state, advanced by elapsed time. The PCM producer
    // never touches these.
    private val smoothedBands = FloatArray(SPECTRUM_BANDS)
    private val peakBands = FloatArray(SPECTRUM_BANDS)

    /**
     * What the envelope is currently heading for: the loudest thing the analyser
     * has reported since the last display frame, held until something newer
     * arrives.
     *
     * Holding is the point. The queue being empty on a given frame says only that
     * the display is faster than the analyser, which at 120 Hz against 86 spectra
     * a second is true of most frames; it does not say the audio went quiet.
     */
    private val targetBands = FloatArray(SPECTRUM_BANDS)
    private var lastBandFrameNanos = Long.MIN_VALUE
    private var dryNanos = 0L
    private var observedBandResetGeneration = 0L

    /**
     * How far behind the newest spectrum the display deliberately reads, in
     * seconds, smoothed. See [rawLeadSeconds] for what it measures.
     */
    private var smoothedLead = -1f

    /**
     * Where the display is reading in the ring, in spectra since the last flush.
     *
     * A fractional cursor advanced by elapsed time rather than an index chasing
     * the writer. Negative until the first frame after a reset.
     */
    private var playCursor = -1.0

    /** The lead the display is currently applying, for tests and diagnostics. */
    val visualDelaySeconds: Float
        get() = if (smoothedLead < 0f) 0f else smoothedLead

    /**
     * How many spectra the playout cursor crossed on the last display frame.
     *
     * Exposed so a test can assert the thing that actually matters here: that most
     * frames carry new information, rather than a burst's worth arriving on one
     * frame and nothing on the next fifteen.
     */
    var consumedLastFrame: Int = 0
        private set

    /**
     * Fills [out] with the current band heights and [peaks], if given, with the
     * slow-falling peak markers. Both must be [SPECTRUM_BANDS] long.
     *
     * [frameTimeNanos] is the display frame's timestamp — the same one
     * [levelAt] takes. Everything below is a function of the time between
     * frames, never of the number of frames, so the shape rises and falls at the
     * same *speed* on a 60 Hz phone, a 120 Hz one and a stuttering emulator.
     *
     * Neither parameter carries a default. A caller who forgot the frame time
     * would get a display that never advanced, which is a silent failure of
     * exactly the kind this rewrite exists to remove.
     */
    fun copyBandsInto(out: FloatArray, peaks: FloatArray?, frameTimeNanos: Long) {
        syncBandsAfterReset()

        val elapsedNanos = if (lastBandFrameNanos == Long.MIN_VALUE) {
            0L
        } else {
            (frameTimeNanos - lastBandFrameNanos).coerceIn(0L, MAX_RENDER_GAP_NANOS)
        }
        lastBandFrameNanos = frameTimeNanos
        val dt = elapsedNanos.toFloat() / 1_000_000_000f

        // Hold the display this far behind the newest spectrum, so what is drawn is
        // what is coming out of the speaker rather than what is on its way into the
        // sink. Seeded with the first measurement rather than ramped up from zero:
        // the very first decoder buffers already fill the AudioTrack, so the number
        // is right immediately and easing into it would only mean starting out of
        // sync on purpose.
        val rawLead = rawLeadSeconds().coerceIn(0f, MAX_VISUAL_LEAD_SECONDS)
        smoothedLead = if (smoothedLead < 0f) {
            rawLead
        } else {
            // Slow, because the quantity really is constant during steady playback
            // and every wobble in it would show up as the picture sliding against
            // the sound.
            smoothedLead + (rawLead - smoothedLead) * (1f - exp(-dt / LEAD_TAU_SECONDS))
        }
        val holdBack = (smoothedLead * SPECTRA_PER_SECOND).toLong()
            .coerceIn(0L, (PENDING_SPECTRA - MIN_READABLE_SPECTRA).toLong())

        // Played out at a constant rate off the wall clock, NOT drained as it
        // arrives. This is the difference between a jitter buffer and a queue, and
        // getting it wrong is what made the display step.
        //
        // The audio thread does not hand over spectra evenly. The sink is fed in
        // bursts — measured on an API 36 emulator: about ten buffers 2 ms apart,
        // then a wait of around 260 ms — so `bandsWrite` jumps by twenty-odd
        // spectra and then stands still for a quarter of a second. Consuming
        // "everything available" therefore moved the target once per burst and
        // held it in between, and a filter chasing a target that changes four
        // times a second produces four stills a second however often it is drawn.
        //
        // Each spectrum covers a fixed 11.6 ms of audio, so the cursor is advanced
        // by elapsed time instead: a fresh spectrum every 11.6 ms whatever the
        // arrival pattern. The hold-back measured above is what makes this
        // possible — it keeps roughly forty spectra in hand, so the cursor never
        // runs dry between bursts.
        val write = bandsWrite
        val playoutRate = SPECTRA_PER_SECOND * playbackSpeed.coerceIn(0.25f, 4f)
        val targetCursor = (write - holdBack).toDouble()

        if (playCursor < 0.0) {
            playCursor = targetCursor.coerceAtLeast(0.0)
            bandsRead = playCursor.toLong()
        } else {
            playCursor += dt * playoutRate
            // Only a seek, a stall or a badly wrong clock can put the cursor this
            // far from where the buffer says it should be. Anything smaller is the
            // burst pattern itself, and correcting for that would hand the display
            // straight back to it.
            if (abs(targetCursor - playCursor) > RESYNC_SPECTRA) playCursor = targetCursor
        }

        val oldest = maxOf(0.0, (write - PENDING_SPECTRA + 1).toDouble())
        playCursor = playCursor.coerceIn(oldest, write.toDouble())
        if (bandsRead < oldest.toLong()) bandsRead = oldest.toLong()

        // The peak across whatever the cursor passed over this frame, so a
        // transient falling between two display frames is still shown.
        val upTo = playCursor.toLong()
        var received = 0
        while (bandsRead < upTo) {
            val spectrum = pendingBands[(bandsRead % PENDING_SPECTRA).toInt()]
            if (received == 0) {
                spectrum.copyInto(targetBands, endIndex = minOf(spectrum.size, targetBands.size))
            } else {
                for (band in targetBands.indices) {
                    val value = spectrum.getOrElse(band) { 0f }
                    if (value > targetBands[band]) targetBands[band] = value
                }
            }
            bandsRead++
            received++
        }
        consumedLastFrame = received

        if (received > 0) {
            dryNanos = 0L
        } else {
            // Nothing new for long enough that playback has actually stopped or
            // stalled — as opposed to the display simply outrunning the analyser.
            // Only now may the target fall, so a pause empties the display instead
            // of freezing it mid-air.
            dryNanos += elapsedNanos
            if (dryNanos >= BAND_STALL_NANOS) targetBands.fill(0f)
        }

        // 1 - e^(-dt/tau) is the frame-rate-independent form of a one-pole filter:
        // the same fraction of the remaining distance per unit of *time*, whatever
        // the frame interval. A fixed per-frame blend is the same thing only if
        // the frame interval never changes, which is the assumption that broke.
        val attack = 1f - exp(-dt / BAND_ATTACK_TAU_SECONDS)
        val release = 1f - exp(-dt / BAND_RELEASE_TAU_SECONDS)
        val peakDrop = BAND_PEAK_FALL_PER_SECOND * dt

        for (band in smoothedBands.indices) {
            val target = targetBands[band]
            val previous = smoothedBands[band]
            val value = previous + (target - previous) * (if (target > previous) attack else release)
            smoothedBands[band] = value
            peakBands[band] = maxOf(value, peakBands[band] - peakDrop)
            if (band < out.size) out[band] = value
            if (peaks != null && band < peaks.size) peaks[band] = peakBands[band]
        }
    }

    private fun syncBandsAfterReset() {
        val generation = resetGeneration
        if (observedBandResetGeneration == generation) return
        observedBandResetGeneration = generation
        targetBands.fill(0f)
        lastBandFrameNanos = Long.MIN_VALUE
        dryNanos = 0L
        playCursor = -1.0
    }

    /**
     * Records one decoder buffer passing the tee. Audio thread; allocates nothing.
     *
     * Wall time is accumulated only across *short* gaps. A pause stops the sink
     * pulling audio without flushing it, so the buffer stays full and the lead
     * stays what it was; counting the pause as elapsed time would read as the
     * buffer having drained and collapse the estimate to nothing.
     */
    internal fun publishFlow(frames: Int, nowNanos: Long) {
        val last = lastFlowNanos
        lastFlowNanos = nowNanos
        if (last != 0L) {
            val gap = nowNanos - last
            if (gap in 0..MAX_FLOW_GAP_NANOS) flowWallNanos += gap
        }
        flowFrames += frames
    }

    internal fun publishSampleRate(sampleRateHz: Int) {
        flowSampleRate = sampleRateHz
    }

    /**
     * Seconds of audio written into the sink but not yet heard.
     *
     * Media time through the tee, converted to wall time, minus the wall time that
     * actually elapsed while it was flowing. At the start of a track the sink
     * fills its buffer as fast as it can, so this jumps straight to the buffer's
     * depth and then holds there for as long as playback is steady — which is
     * exactly the quantity the display has to be delayed by.
     */
    private fun rawLeadSeconds(): Float {
        val rate = flowSampleRate
        if (rate <= 0) return 0f
        val speed = playbackSpeed.coerceIn(0.25f, 4f)
        val mediaSeconds = flowFrames.toFloat() / rate
        val wallSeconds = flowWallNanos.toFloat() / 1_000_000_000f
        return mediaSeconds / speed - wallSeconds
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
        smoothedLead = -1f
        playCursor = -1.0
        flowFrames = 0L
        flowWallNanos = 0L
        lastFlowNanos = 0L
        // Bumped last: it is what tells the renderer thread to drop its own
        // interpolation and filter state on its next frame.
        resetGeneration++
    }

    private companion object {
        const val PENDING_CAPACITY = 128

        /**
         * The ring is now a delay line as well as a buffer, so it has to hold the
         * whole visual lead plus enough fresh entries to keep drawing. A second
         * and a half of spectra at the analysis rate; 48 floats apiece, so 24 kB.
         */
        const val PENDING_SPECTRA = 128

        /** Spectra a second the analyser produces: a 2048 window hopped by 512. */
        const val SPECTRA_PER_SECOND = 86f

        /**
         * The most the display will hold itself back.
         *
         * A backstop against a bad measurement — a stall, a torn read — not a
         * working value: delaying the picture by a second would be worse than not
         * compensating at all. Media3 sizes the AudioTrack buffer at 250 ms or more
         * for PCM and the device's own output path adds to that; measured on an
         * API 36 emulator the total came to a steady 430 ms, so 500 ms would have
         * been close enough to clip on hardware with a deeper buffer.
         */
        const val MAX_VISUAL_LEAD_SECONDS = 0.8f

        /** Never hold back so far that there is nothing left to draw. */
        const val MIN_READABLE_SPECTRA = 8

        /** The lead is constant in steady playback, so it is filtered hard. */
        const val LEAD_TAU_SECONDS = 2f

        /**
         * How far the playout cursor may drift before it is snapped back.
         *
         * Deliberately larger than one decoder burst. The gap between where the
         * cursor is and where the buffer's fill says it should be swings by a whole
         * burst every burst; correcting for that swing would re-couple the display
         * to the arrival pattern, which is the entire thing this cursor exists to
         * escape. Only a seek or a real stall moves it further than this.
         */
        const val RESYNC_SPECTRA = 60.0

        /**
         * The longest gap between decoder buffers that still counts as playback.
         *
         * Measured rather than assumed, because the first guess at it — 200 ms, on
         * the reasoning that the playback thread tops the sink up every 10 ms or so
         * — was wrong in a way that silently destroyed the estimate. The sink is
         * actually fed in bursts: about ten buffers 2 ms apart, then a wait of
         * around 260 ms, repeating. A 200 ms ceiling rejected every one of those
         * waits, so eighty seconds of playback accumulated six seconds of "elapsed"
         * time and the lead came out as seventy-four seconds.
         *
         * A second is comfortably above the observed 272 ms peak and comfortably
         * below any pause or rebuffer worth excluding.
         */
        const val MAX_FLOW_GAP_NANOS = 1_000_000_000L

        /**
         * How long the analyser may go quiet before the display treats it as
         * silence rather than as the panel outrunning it.
         *
         * Comfortably longer than one analysis hop (~12 ms) and than the gap a
         * decoder leaves between buffers, short enough that a pause empties the
         * bars without a visible hang.
         */
        val BAND_STALL_NANOS = 250_000_000L
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
    private val autoGain = AutoGain()

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        // Any other encoding is left alone rather than misread as shorts and drawn
        // as noise. In practice every codec this app plays decodes to 16-bit.
        pcm16 = encoding == C.ENCODING_PCM_16BIT
        smoothed = 0f
        windowBytes = envelopeWindowBytes(sampleRateHz, channelCount)
        strideBytes = channelCount.coerceAtLeast(1) * 2
        bandEdges = spectrumBandEdges(sampleRateHz)
        autoGain.reset()
        tap.publishSampleRate(sampleRateHz)
        fftFill = 0
        bytesInWindow = 0
        sumInWindow = 0.0
        samplesInWindow = 0
        tap.reset()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!pcm16) return

        // Counted whether or not anything is drawing. The lead this measures is
        // anchored at the last flush, and a listener who opens the player halfway
        // through an episode would otherwise turn the visualiser on to an
        // unanchored estimate with no way left to take one — the sink's buffer is
        // full by then, so media time and wall time have long since started
        // advancing together. Two additions per decoder buffer is not a cost.
        tap.publishFlow((buffer.limit() - buffer.position()) / strideBytes.coerceAtLeast(1), System.nanoTime())

        if (!tap.listening) {
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
        // Normalise against what this display has been hearing rather than
        // against full scale, so a quietly mastered episode fills the same
        // height as a loud one. Silence is left alone.
        autoGain.apply(bandScratch)
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
