package net.koalastuff.koalacast.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.model.VisualizerStyle
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import kotlin.math.max

/**
 * The player's progress bar, optionally alive.
 *
 * Whatever a style draws, it is still a progress bar: the played fraction is always
 * readable and the same number sits next to it as text. Decorative in the
 * accessibility sense — the time codes carry the meaning, so this stays silent for
 * TalkBack exactly as [ProgressTrack] does.
 *
 * Every style that reacts to audio draws across the **whole** width and keeps each
 * bar in a fixed place, changing only its height. Two earlier shapes are gone on
 * purpose: a nine-bar cluster pinned to the middle third of the track, which read
 * as a rendering fault on anything wider than a phone, and a scrolling waveform
 * whose values shifted one slot left per sample, so the shape crawled sideways
 * like a seismograph instead of standing still and answering for the sound.
 *
 * @param level current amplitude, 0..1, for the styles that draw one number
 * @param bands per-frequency-band energy, low first; the spectrum styles' input
 * @param peaks slow-falling peak per band, same length as [bands]
 * @param revision bumped by the caller whenever [bands] changes.
 *
 *   Load-bearing, and the reason the spectrum appeared to update a couple of
 *   times a second while the app was drawing at fifty. [bands] is one array
 *   mutated in place — it has to be, because allocating 48 floats per frame on
 *   the audio path is not acceptable — so from Compose's point of view the
 *   parameter never changes, the draw lambda captures nothing new, and
 *   `drawBehind` reuses its cached result. Capturing an integer that does change
 *   is what invalidates the draw.
 */
@Composable
fun VisualizerTrack(
    style: VisualizerStyle,
    fraction: Float,
    level: Float,
    bands: FloatArray,
    peaks: FloatArray = bands,
    revision: Int = 0,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val colors = KoalaTheme.colors
    val played = fraction.coerceIn(0f, 1f)
    val active = if (colors.isDark) colors.accentFill else colors.accentInk
    val inactive = colors.track

    when (style) {
        VisualizerStyle.OFF -> ProgressTrack(
            percent = (played * 100).toInt(),
            modifier = modifier,
            height = height,
        )

        VisualizerStyle.LEVEL -> Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(LEVEL_MAX_HEIGHT)
                .clearAndSetSemantics { },
        ) {
            // The bar keeps its shape and only swells, so it never stops looking
            // like the thing you drag.
            val thickness = size.height * (BASE_THICKNESS + (1f - BASE_THICKNESS) * level.coerceIn(0f, 1f))
            val top = (size.height - thickness) / 2f
            val radius = CornerRadius(thickness / 2f, thickness / 2f)
            drawRoundRect(
                color = inactive,
                topLeft = Offset(0f, top),
                size = Size(size.width, thickness),
                cornerRadius = radius,
            )
            if (played > 0f) {
                drawRoundRect(
                    color = active,
                    topLeft = Offset(0f, top),
                    size = Size(size.width * played, thickness),
                    cornerRadius = radius,
                )
            }
        }

        VisualizerStyle.WAVEFORM -> Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(AUDIO_TRACK_HEIGHT)
                .clearAndSetSemantics { },
        ) {
            revision.let { }
            // A wave, not bars. This drew the same rectangles as BARS with a
            // lower alpha, which made two of the five styles the same picture.
            drawWave(bands, active)
            drawProgressLine(played, inactive, active, size.height / 2f)
        }

        VisualizerStyle.BARS -> Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(AUDIO_TRACK_HEIGHT)
                .clearAndSetSemantics { },
        ) {
            revision.let { }
            drawSpectrum(
                bands = bands,
                peaks = peaks,
                colour = active.copy(alpha = BAR_ALPHA),
                peakColour = active.copy(alpha = PEAK_ALPHA),
            )
            drawProgressLine(played, inactive, active, size.height / 2f)
        }

        VisualizerStyle.PULSE -> Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(AUDIO_TRACK_HEIGHT)
                .clearAndSetSemantics { },
        ) {
            val centre = size.height / 2f
            val strength = level.coerceIn(0f, 1f)
            val playheadX = size.width * played

            // The line first, the rings over it — the same ordering mistake the
            // dots had. Filled discs drawn underneath were swallowed by the 8dp
            // bar, which is most of what a quiet passage produced.
            drawProgressLine(played, inactive, active, centre)
            if (played > 0f) {
                // Stroked rather than filled, so the bar reads through them and a
                // ring stays a ring rather than becoming a blob at the playhead.
                // The floor is deliberate: at conversational level the old opacity
                // curve bottomed out around 0.12 and the effect was invisible on
                // exactly the material this app plays.
                val outer = PULSE_BASE_RADIUS_PX + strength * PULSE_OUTER_GROWTH_PX
                drawCircle(
                    color = active.copy(alpha = PULSE_OUTER_ALPHA + strength * PULSE_OUTER_ALPHA_GROWTH),
                    radius = outer,
                    center = Offset(playheadX, centre),
                    style = Stroke(width = PULSE_STROKE_PX),
                )
                drawCircle(
                    color = active.copy(alpha = PULSE_INNER_ALPHA + strength * PULSE_INNER_ALPHA_GROWTH),
                    radius = PULSE_BASE_RADIUS_PX + strength * PULSE_INNER_GROWTH_PX,
                    center = Offset(playheadX, centre),
                    style = Stroke(width = PULSE_STROKE_PX),
                )
            }
        }

        VisualizerStyle.SPECTRUM -> Canvas(
            modifier = modifier.fillMaxWidth().height(AUDIO_TRACK_HEIGHT).clearAndSetSemantics { },
        ) {
            revision.let { }
            drawMirroredColumns(bands, active)
        }

        VisualizerStyle.RIBBON -> Canvas(
            modifier = modifier.fillMaxWidth().height(AUDIO_TRACK_HEIGHT).clearAndSetSemantics { },
        ) {
            revision.let { }
            drawRibbon(bands, active)
        }

        VisualizerStyle.VU -> Canvas(
            modifier = modifier.fillMaxWidth().height(AUDIO_TRACK_HEIGHT).clearAndSetSemantics { },
        ) {
            revision.let { }
            drawVuMeter(bands, active, inactive)
        }

        VisualizerStyle.CONSTELLATION -> Canvas(
            modifier = modifier.fillMaxWidth().height(AUDIO_TRACK_HEIGHT).clearAndSetSemantics { },
        ) {
            revision.let { }
            drawConstellation(bands, active)
        }

    }
}

/**
 * A continuous wave across the spectrum: low frequencies at the left, high at the
 * right, mirrored about the centre line and filled.
 *
 * Points are joined with a Catmull-Rom-style midpoint curve rather than straight
 * segments, because 48 straight joins over 300px read as a saw, not a wave.
 */
private fun DrawScope.drawWave(bands: FloatArray, colour: Color) {
    if (bands.size < 2) return
    val centre = size.height / 2f
    val available = (size.height - PROGRESS_HEIGHT_PX) / 2f
    val step = size.width / (bands.size - 1)

    fun heightAt(index: Int): Float =
        max(MIN_BAR_PX / 2f, available * bands[index].coerceIn(0f, 1f))

    val upper = Path()
    val lower = Path()
    upper.moveTo(0f, centre - heightAt(0))
    lower.moveTo(0f, centre + heightAt(0))
    for (index in 0 until bands.size - 1) {
        val x = index * step
        val nextX = (index + 1) * step
        val midX = (x + nextX) / 2f
        val h = heightAt(index)
        val nextH = heightAt(index + 1)
        // Horizontal control points at the midpoint keep the curve monotone
        // between samples, so a loud band cannot make the line overshoot below
        // the axis and cross its own mirror.
        upper.cubicTo(midX, centre - h, midX, centre - nextH, nextX, centre - nextH)
        lower.cubicTo(midX, centre + h, midX, centre + nextH, nextX, centre + nextH)
    }

    // Closed into a single band so the wave reads as one body rather than as two
    // unrelated lines.
    val body = Path().apply {
        addPath(upper)
        lineTo(size.width, centre + heightAt(bands.size - 1))
        // Walk the lower edge backwards to close the shape.
        for (index in bands.size - 1 downTo 1) {
            val x = index * step
            val previousX = (index - 1) * step
            val midX = (x + previousX) / 2f
            cubicTo(midX, centre + heightAt(index), midX, centre + heightAt(index - 1), previousX, centre + heightAt(index - 1))
        }
        close()
    }
    drawPath(path = body, color = colour.copy(alpha = WAVE_FILL_ALPHA))
    drawPath(path = upper, color = colour.copy(alpha = WAVE_LINE_ALPHA), style = Stroke(width = WAVE_STROKE_PX))
    drawPath(path = lower, color = colour.copy(alpha = WAVE_LINE_ALPHA), style = Stroke(width = WAVE_STROKE_PX))
}

/**
 * Bars across the full width, mirrored around the centre line so the progress
 * track can run through the middle of them.
 *
 * Bar width is derived from the width available rather than fixed, which is what
 * lets the same band count fill a phone and a tablet without this code knowing
 * which it is on.
 */
private fun DrawScope.drawSpectrum(
    bands: FloatArray,
    peaks: FloatArray?,
    colour: Color,
    peakColour: Color?,
) {
    if (bands.isEmpty()) return
    val centre = size.height / 2f
    val slot = size.width / bands.size
    val barWidth = max(MIN_BAR_WIDTH_PX, slot - BAR_GAP_PX)
    val available = size.height - PROGRESS_HEIGHT_PX
    val corner = CornerRadius(barWidth / 2f, barWidth / 2f)

    bands.forEachIndexed { index, amplitude ->
        val x = index * slot + (slot - barWidth) / 2f
        // A floor rather than zero: silence should read as a quiet bar, not as a
        // hole in the track.
        val bar = max(MIN_BAR_PX, available * amplitude.coerceIn(0f, 1f))
        drawRoundRect(
            color = colour,
            topLeft = Offset(x, centre - bar / 2f),
            size = Size(barWidth, bar),
            cornerRadius = corner,
        )
        // A peak that falls back slowly is what makes a spectrum readable at a
        // glance rather than a blur of moving sticks.
        if (peaks != null && peakColour != null && index < peaks.size) {
            val peak = max(bar, available * peaks[index].coerceIn(0f, 1f))
            drawRoundRect(
                color = peakColour,
                topLeft = Offset(x, centre - peak / 2f),
                size = Size(barWidth, PEAK_HEIGHT_PX),
                cornerRadius = CornerRadius(PEAK_HEIGHT_PX / 2f, PEAK_HEIGHT_PX / 2f),
            )
        }
    }
}

private fun DrawScope.drawMirroredColumns(bands: FloatArray, colour: Color) {
    if (bands.isEmpty()) return
    val centre = size.height / 2f
    val slot = size.width / bands.size
    val width = max(1.5f, slot - 2f)
    bands.forEachIndexed { index, value ->
        val columnHeight = max(2f, value.coerceIn(0f, 1f) * size.height * 0.9f)
        drawRoundRect(
            color = colour.copy(alpha = 0.82f),
            topLeft = Offset(index * slot + (slot - width) / 2f, centre - columnHeight / 2f),
            size = Size(width, columnHeight),
            cornerRadius = CornerRadius(width / 2f, width / 2f),
        )
    }
}

private fun DrawScope.drawRibbon(bands: FloatArray, colour: Color) {
    if (bands.size < 2) return
    val centre = size.height / 2f
    val step = size.width / (bands.size - 1)
    val top = Path()
    val bottom = Path()
    bands.forEachIndexed { index, value ->
        val x = index * step
        val extent = 2f + value.coerceIn(0f, 1f) * size.height * 0.4f
        if (index == 0) {
            top.moveTo(x, centre - extent)
            bottom.moveTo(x, centre + extent)
        } else {
            top.lineTo(x, centre - extent)
            bottom.lineTo(x, centre + extent)
        }
    }
    val body = Path().apply {
        addPath(top)
        for (index in bands.lastIndex downTo 0) {
            val extent = 2f + bands[index].coerceIn(0f, 1f) * size.height * 0.4f
            lineTo(index * step, centre + extent)
        }
        close()
    }
    drawPath(body, colour.copy(alpha = 0.16f))
    drawPath(top, colour.copy(alpha = 0.78f), style = Stroke(width = 1.5f))
    drawPath(bottom, colour.copy(alpha = 0.3f), style = Stroke(width = 1.2f))
}

private fun DrawScope.drawVuMeter(bands: FloatArray, colour: Color, inactive: Color) {
    if (bands.isEmpty()) return
    val half = (bands.size / 2).coerceAtLeast(1)
    val low = bands.take(half).average().toFloat().coerceIn(0f, 1f) * 1.35f
    val high = bands.drop(half).average().toFloat().coerceIn(0f, 1f) * 1.8f
    val segments = 20
    val gap = 2f
    val segmentWidth = (size.width - gap * (segments - 1)) / segments
    val laneHeight = 5f
    listOf(low, high).forEachIndexed { lane, strength ->
        val y = size.height / 2f + (lane - 1) * (laneHeight + 2f)
        repeat(segments) { index ->
            drawRoundRect(
                color = if ((index + 1f) / segments <= strength) colour.copy(alpha = 0.82f) else inactive.copy(alpha = 0.55f),
                topLeft = Offset(index * (segmentWidth + gap), y),
                size = Size(segmentWidth, laneHeight),
                cornerRadius = CornerRadius(1f, 1f),
            )
        }
    }
}

private fun DrawScope.drawConstellation(bands: FloatArray, colour: Color) {
    if (bands.isEmpty()) return
    val points = 18
    val path = Path()
    repeat(points) { index ->
        val bandIndex = (index * (bands.size - 1) / (points - 1f)).toInt()
        val energy = bands[bandIndex].coerceIn(0f, 1f)
        val x = size.width * index / (points - 1f)
        val y = size.height / 2f + (if (index % 2 == 0) -1f else 1f) * energy * size.height * 0.34f
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, colour.copy(alpha = 0.24f), style = Stroke(width = 1f))
    repeat(points) { index ->
        val bandIndex = (index * (bands.size - 1) / (points - 1f)).toInt()
        val energy = bands[bandIndex].coerceIn(0f, 1f)
        val x = size.width * index / (points - 1f)
        val y = size.height / 2f + (if (index % 2 == 0) -1f else 1f) * energy * size.height * 0.34f
        drawCircle(colour.copy(alpha = 0.84f), radius = 1.5f + energy * 2.5f, center = Offset(x, y))
    }
}

private fun DrawScope.drawProgressLine(
    played: Float,
    inactive: Color,
    active: Color,
    centre: Float,
) {
    val trackTop = centre - PROGRESS_HEIGHT_PX / 2f
    val radius = CornerRadius(PROGRESS_HEIGHT_PX / 2f, PROGRESS_HEIGHT_PX / 2f)
    drawRoundRect(
        color = inactive,
        topLeft = Offset(0f, trackTop),
        size = Size(size.width, PROGRESS_HEIGHT_PX),
        cornerRadius = radius,
    )
    if (played > 0f) {
        drawRoundRect(
            color = active,
            topLeft = Offset(0f, trackTop),
            size = Size(size.width * played, PROGRESS_HEIGHT_PX),
            cornerRadius = radius,
        )
    }
}

/** The preview used in Settings, where there is no audio to react to. */
@Composable
fun VisualizerPreview(
    style: VisualizerStyle,
    modifier: Modifier = Modifier,
) {
    VisualizerTrack(
        style = style,
        fraction = 0.55f,
        level = 0.7f,
        bands = PREVIEW_BANDS,
        peaks = PREVIEW_PEAKS,
        modifier = modifier,
    )
}

/** A canned spectrum that looks like speech: loud low end, rolling off upwards. */
private val PREVIEW_BANDS = FloatArray(48) { index ->
    val t = index / 47f
    ((1f - t * 0.8f) * (0.55f + 0.45f * kotlin.math.sin(index * 1.7f))).coerceIn(0.12f, 1f)
}
private val PREVIEW_PEAKS = FloatArray(48) { (PREVIEW_BANDS[it] + 0.12f).coerceAtMost(1f) }

/**
 * Level swells within a fixed band so the row never reflows as audio plays. It
 * used to top out at 10dp, which is a progress bar with a slight wobble — not
 * something a listener notices. It now shares the height of the other styles and
 * swells across most of it.
 */
private val LEVEL_MAX_HEIGHT = 22.dp

/**
 * One height for every audio-reactive style. They used to be 26–30dp apiece, so
 * changing style in Settings nudged the whole transport row up or down.
 *
 * 30dp left ±11px of travel once the progress bar had taken its share, which is
 * not enough for a loud passage to look different from a quiet one. The scrubber
 * row reserves 48dp, so this can take 40 without moving anything else.
 */
private val AUDIO_TRACK_HEIGHT = 40.dp
private const val BASE_THICKNESS = 0.22f
private const val MIN_BAR_PX = 2f
private const val MIN_BAR_WIDTH_PX = 1.5f
private const val BAR_GAP_PX = 2f
private const val PROGRESS_HEIGHT_PX = 8f
private const val PEAK_HEIGHT_PX = 2f

/** The wave is context around the bar, not a competing element. */
private const val WAVE_FILL_ALPHA = 0.3f
private const val WAVE_LINE_ALPHA = 0.85f
private const val WAVE_STROKE_PX = 2f
private const val BAR_ALPHA = 0.72f
private const val PEAK_ALPHA = 0.5f
/** Big enough at silence that the playhead always carries a visible ring. */
private const val PULSE_BASE_RADIUS_PX = 6f
private const val PULSE_INNER_GROWTH_PX = 5f
private const val PULSE_OUTER_GROWTH_PX = 11f
private const val PULSE_STROKE_PX = 1.5f
private const val PULSE_OUTER_ALPHA = 0.28f
private const val PULSE_OUTER_ALPHA_GROWTH = 0.32f
private const val PULSE_INNER_ALPHA = 0.5f
private const val PULSE_INNER_ALPHA_GROWTH = 0.4f
