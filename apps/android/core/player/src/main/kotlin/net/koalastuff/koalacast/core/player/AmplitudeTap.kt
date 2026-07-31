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

    /** Latest smoothed amplitude, 0..1. Zero whenever nothing is being decoded. */
    fun level(): Float = level

    internal fun publish(value: Float) {
        level = value
    }

    internal fun reset() {
        level = 0f
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

        var sum = 0.0
        var samples = 0
        // Stride over samples rather than reading every one: an envelope does not
        // get more truthful from 100x the arithmetic on the audio thread.
        var index = position
        while (index + 1 < limit) {
            val sample = buffer.getShort(index) / Short.MAX_VALUE.toFloat()
            sum += (sample * sample).toDouble()
            samples++
            index += SAMPLE_STRIDE_BYTES
        }

        buffer.order(order)
        if (samples == 0) return

        val rms = sqrt(sum / samples).toFloat()
        val normalised = (rms * GAIN).coerceIn(0f, 1f)
        // Fast attack, slow release: speech is mostly gaps, and an envelope that
        // falls as fast as it rises reads as flicker rather than as level.
        smoothed = if (normalised > smoothed) {
            smoothed + (normalised - smoothed) * ATTACK
        } else {
            smoothed + (normalised - smoothed) * RELEASE
        }
        tap.publish(smoothed)
    }

    private companion object {
        /** Every 8th sample; at 44.1 kHz stereo that is still ~5 kHz of envelope. */
        const val SAMPLE_STRIDE_BYTES = 16

        /** Speech RMS sits well below full scale, so the envelope is lifted. */
        const val GAIN = 3.2f
        const val ATTACK = 0.55f
        const val RELEASE = 0.12f
    }
}
