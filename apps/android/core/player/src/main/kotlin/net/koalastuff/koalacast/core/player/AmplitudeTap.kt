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
// At 30 envelope frames per second: a quick attack keeps consonants lively while
// the slower release bridges the short gaps between syllables.
private const val AMPLITUDE_ATTACK = 0.80f
private const val AMPLITUDE_RELEASE = 0.13f
private const val TARGET_UPDATES_PER_SECOND = 30

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

    /**
     * Set while something is actually drawing. The processor stays in the chain
     * either way — inserting and removing it mid-playback would reconfigure the
     * audio sink — but it does no arithmetic for the listeners who never turn a
     * visualiser on, which is most of them.
     */
    @Volatile
    var listening: Boolean = false

    /**
     * Recent history, oldest first, for styles that draw a shape rather than a
     * level. A fixed array written round-robin: the audio thread must not allocate,
     * and a reader that catches a half-updated slot sees one wrong bar for one
     * frame, which nobody can perceive.
     */
    private val history = FloatArray(HISTORY)

    @Volatile
    private var writeIndex = 0

    /**
     * Consumes one queued smoothed amplitude, 0..1. If the decoder gets more than
     * two seconds ahead, stale values are skipped instead of making the animation
     * visibly lag behind the audio.
     */
    fun level(): Float {
        val end = pendingWrite
        var read = pendingRead
        if (read >= end) return level

        if (end - read > PENDING_CAPACITY.toLong()) {
            read = end - PENDING_CAPACITY
        }
        val value = pending[(read % PENDING_CAPACITY).toInt()]
        pendingRead = read + 1
        level = value

        val index = writeIndex
        history[index % HISTORY] = value
        writeIndex = (index + 1) % HISTORY
        return value
    }

    /**
     * Copies the history into [out], oldest first, so the caller can render without
     * allocating per frame. [out] may be any length; it is filled from the most
     * recent samples backwards.
     */
    fun copyHistoryInto(out: FloatArray) {
        val end = writeIndex
        for (i in out.indices) {
            // out[last] is the newest sample, walking backwards from the write head.
            val age = out.size - 1 - i
            val index = ((end - 1 - age) % HISTORY + HISTORY) % HISTORY
            out[i] = history[index]
        }
    }

    internal fun publish(value: Float) {
        val index = pendingWrite
        pending[(index % PENDING_CAPACITY).toInt()] = value
        // Volatile publication happens after the array write.
        pendingWrite = index + 1
    }

    internal fun reset() {
        level = 0f
        history.fill(0f)
        writeIndex = 0
        pendingRead = pendingWrite
    }

    private companion object {
        /**
         * At roughly one sample per decoded buffer this covers a few seconds, which
         * is as much past as a bar the width of a phone can show honestly.
         */
        const val HISTORY = 96
        const val PENDING_CAPACITY = 64
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

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        // Any other encoding is left alone rather than misread as shorts and drawn
        // as noise. In practice every codec this app plays decodes to 16-bit.
        pcm16 = encoding == C.ENCODING_PCM_16BIT
        smoothed = 0f
        windowBytes = envelopeWindowBytes(sampleRateHz, channelCount)
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

            val representedBytes = minOf(SAMPLE_STRIDE_BYTES, limit - index)
            bytesInWindow += representedBytes
            if (bytesInWindow >= windowBytes) {
                val rms = sqrt(sumInWindow / samplesInWindow).toFloat()
                smoothed = nextAmplitude(smoothed, rms)
                tap.publish(smoothed)
                bytesInWindow = 0
                sumInWindow = 0.0
                samplesInWindow = 0
            }
            index += SAMPLE_STRIDE_BYTES
        }

        buffer.order(order)
    }

    private companion object {
        /** Every 8th sample; at 44.1 kHz stereo that is still ~5 kHz of envelope. */
        const val SAMPLE_STRIDE_BYTES = 16

    }
}

internal fun envelopeWindowBytes(sampleRateHz: Int, channelCount: Int): Int {
    val frameBytes = channelCount.coerceAtLeast(1) * 2
    val raw = sampleRateHz.coerceAtLeast(1) * frameBytes / TARGET_UPDATES_PER_SECOND
    return (raw / frameBytes).coerceAtLeast(1) * frameBytes
}

internal fun nextAmplitude(previous: Float, rms: Float): Float {
    val normalised = ((rms - AMPLITUDE_NOISE_FLOOR) * AMPLITUDE_GAIN).coerceIn(0f, 1f)
    val blend = if (normalised > previous) AMPLITUDE_ATTACK else AMPLITUDE_RELEASE
    return previous + (normalised - previous) * blend
}
