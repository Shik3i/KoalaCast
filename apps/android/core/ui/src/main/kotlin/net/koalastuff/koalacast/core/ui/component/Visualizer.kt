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
 */
@Composable
fun VisualizerTrack(
    style: VisualizerStyle,
    fraction: Float,
    level: Float,
    bands: FloatArray,
    peaks: FloatArray = bands,
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
            drawSpectrum(
                bands = bands,
                peaks = null,
                colour = active.copy(alpha = WAVE_ALPHA),
                peakColour = null,
            )
            drawProgressLine(played, inactive, active, size.height / 2f)
        }

        VisualizerStyle.BARS -> Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(AUDIO_TRACK_HEIGHT)
                .clearAndSetSemantics { },
        ) {
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

        VisualizerStyle.DOTS -> Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(AUDIO_TRACK_HEIGHT)
                .clearAndSetSemantics { },
        ) {
            val centre = size.height / 2f
            // The same signal on a coarser grid, so the row stays a row of dots
            // rather than becoming a dotted line.
            val count = max(2, bands.size / DOT_BAND_STRIDE)
            val available = size.height - PROGRESS_HEIGHT_PX
            val spacing = size.width / (count - 1)
            // Bounded by the gap as well as by the row height. Height alone let a
            // loud passage grow every dot until neighbours overlapped and the
            // whole left half fused into one blob.
            val maxRadius = minOf(available / 2f, spacing * DOT_MAX_SPACING_FRACTION)
            // Never thinner than the track it sits on. A dot smaller than 8px was
            // simply inside the progress bar, so quiet passages had no dots at all
            // and the row looked like it stopped rendering partway across.
            val minRadius = minOf(PROGRESS_HEIGHT_PX / 2f + DOT_CLEARANCE_PX, maxRadius)

            // The line first, the dots over it. Drawn the other way round, the bar
            // paints out every dot it is wider than — which is most of them.
            drawProgressLine(played, inactive, active, centre)
            for (index in 0 until count) {
                val band = (index * DOT_BAND_STRIDE).coerceAtMost(bands.size - 1)
                val energy = if (bands.isEmpty()) level else bands[band].coerceIn(0f, 1f)
                val x = if (count == 1) size.width / 2f else spacing * index
                val radius = minRadius + energy * (maxRadius - minRadius).coerceAtLeast(0f)
                val reached = x <= size.width * played
                drawCircle(
                    // Both halves are the accent, only at different strengths. The
                    // unplayed dots used to be drawn in the track colour, on the
                    // track, which made them invisible even where the bar did not
                    // cover them.
                    color = active.copy(alpha = if (reached) DOT_PLAYED_ALPHA else DOT_AHEAD_ALPHA),
                    // Inset at the ends so the outermost dots are not clipped in half.
                    radius = radius,
                    center = Offset(x.coerceIn(radius, size.width - radius), centre),
                )
            }
        }
    }
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

/** Level swells within a fixed band so the row never reflows as audio plays. */
private val LEVEL_MAX_HEIGHT = 10.dp

/**
 * One height for every audio-reactive style. They used to be 26–30dp apiece, so
 * changing style in Settings nudged the whole transport row up or down.
 */
private val AUDIO_TRACK_HEIGHT = 30.dp
private const val BASE_THICKNESS = 0.4f
private const val MIN_BAR_PX = 2f
private const val MIN_BAR_WIDTH_PX = 1.5f
private const val BAR_GAP_PX = 2f
private const val PROGRESS_HEIGHT_PX = 8f
private const val PEAK_HEIGHT_PX = 2f

/** The wave is context around the bar, not a competing element. */
private const val WAVE_ALPHA = 0.45f
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
private const val DOT_BAND_STRIDE = 4
/** How far a silent dot still stands clear of the track's edge. */
private const val DOT_CLEARANCE_PX = 2f
/** A dot at full energy leaves about a third of the gap clear on each side. */
private const val DOT_MAX_SPACING_FRACTION = 0.34f
private const val DOT_PLAYED_ALPHA = 0.9f
private const val DOT_AHEAD_ALPHA = 0.45f
