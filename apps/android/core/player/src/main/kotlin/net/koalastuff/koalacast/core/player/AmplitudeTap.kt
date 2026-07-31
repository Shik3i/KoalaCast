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

    /**
     * Written from the audio thread, read from the UI frame clock. Volatile rather
     * than locked: the audio thread must never wait for a renderer, and the worst a
     * racing read can see is one stale frame, which is invisible at 60 Hz.
     */
    @Volatile
    private var level: Float = 0f

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

    /** Latest smoothed amplitude, 0..1. Zero whenever nothing is being decoded. */
    fun level(): Float = level

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
        level = value
        val index = writeIndex
        history[index % HISTORY] = value
        writeIndex = (index + 1) % HISTORY
    }

    internal fun reset() {
        level = 0f
        history.fill(0f)
    }

    private companion object {
        /**
         * At roughly one sample per decoded buffer this covers a few seconds, which
         * is as much past as a bar the width of a phone can show honestly.
         */
        const val HISTORY = 96
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

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        // Any other encoding is left alone rather than misread as shorts and drawn
        // as noise. In practice every codec this app plays decodes to 16-bit.
        pcm16 = encoding == C.ENCODING_PCM_16BIT
        smoothed = 0f
        tap.reset()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!pcm16 || !tap.listening) {
            if (smoothed != 0f) {
                smoothed = 0f
                tap.reset()
            }
            return
        }

        val position = buffer.position()
        val limit = buffer.limit()
        val order = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // One value per buffer is far too coarse. The sink hands over a few hundred
        // milliseconds at a time, so a single RMS per call updates roughly four
        // times a second — which is exactly what "the animation only moves once a
        // second" looks like. Each buffer is split into short windows instead, so
        // the envelope has real temporal resolution and the history scrolls at a
        // believable rate.
        var windowStart = position
        while (windowStart + 1 < limit) {
            val windowEnd = minOf(windowStart + WINDOW_BYTES, limit)
            var sum = 0.0
            var samples = 0
            var index = windowStart
            // Stride within the window: an envelope does not get more truthful from
            // 100x the arithmetic on the audio thread.
            while (index + 1 < windowEnd) {
                val sample = buffer.getShort(index) / Short.MAX_VALUE.toFloat()
                sum += (sample * sample).toDouble()
                samples++
                index += SAMPLE_STRIDE_BYTES
            }
            if (samples > 0) {
                val rms = sqrt(sum / samples).toFloat()
                val normalised = (rms * GAIN).coerceIn(0f, 1f)
                // Fast attack, slower release: speech is mostly gaps, and an
                // envelope that falls as fast as it rises reads as flicker.
                smoothed = if (normalised > smoothed) {
                    smoothed + (normalised - smoothed) * ATTACK
                } else {
                    smoothed + (normalised - smoothed) * RELEASE
                }
                tap.publish(smoothed)
            }
            windowStart = windowEnd
        }

        buffer.order(order)
    }

    private companion object {
        /** Every 8th sample; at 44.1 kHz stereo that is still ~5 kHz of envelope. */
        const val SAMPLE_STRIDE_BYTES = 16

        /**
         * ~11 ms of 44.1 kHz stereo per published value, so the history advances
         * about 90 times a second rather than once per audio buffer.
         */
        const val WINDOW_BYTES = 2048

        /** Speech RMS sits well below full scale, so the envelope is lifted. */
        const val GAIN = 3.2f

        // Per window rather than per buffer now, so both are gentler than they look.
        const val ATTACK = 0.35f
        const val RELEASE = 0.06f
    }
}
