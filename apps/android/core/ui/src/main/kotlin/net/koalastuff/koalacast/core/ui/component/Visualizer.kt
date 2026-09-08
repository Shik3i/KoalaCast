package net.koalastuff.koalacast.core.ui.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.model.VisualizerStyle
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import kotlin.math.max
import kotlin.math.min

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
 * ## Why the audio-reactive parameters are lambdas
 *
 * [level] and [revision] are read **inside the draw lambda**, never during
 * composition. A snapshot state read inside a draw block subscribes only the draw
 * phase, so a value that changes every frame re-runs drawing and nothing else.
 * Passing the same values as plain parameters recomposed this composable and its
 * whole `when` sixty to a hundred and twenty times a second to produce identical
 * layout, which is the expensive way to arrive at the same pixels.
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
 *   contents never change and a cached draw result stays valid forever. Reading
 *   an integer that *does* change is what invalidates the drawing.
 */
/**
 * The height the track takes for [style].
 *
 * Public because the scrubber draws its drag thumb and chapter marks on a canvas
 * of its own, stacked over this one, and the two have to agree about where the
 * progress line is. See [visualizerProgressLineFraction].
 */
fun visualizerTrackHeight(style: VisualizerStyle): Dp = when (style) {
    VisualizerStyle.OFF -> PROGRESS_HEIGHT
    VisualizerStyle.LEVEL -> LEVEL_MAX_HEIGHT
    else -> AUDIO_TRACK_HEIGHT
}

/**
 * Where the progress line sits inside [visualizerTrackHeight], 0 at the top and 1
 * at the bottom.
 *
 * Not always the middle, and that is the whole point. A style whose shape is
 * mirrored about the centre wants the line through that centre, where it reads as
 * the axis the shape is built around. A style whose bars *stand* on something
 * wants the line under them, where it reads as the floor they stand on — run it
 * through their middle instead and it looks like a rule drawn across a finished
 * picture, which is exactly what it looked like.
 *
 * The scrubber's thumb has to be placed from the same number, or the thing you
 * drag stops sitting on the thing it is dragging along.
 */
fun visualizerProgressLineFraction(style: VisualizerStyle): Float = when (style) {
    // Bars stand on the line, so it sits at the foot of the track with its own
    // thickness inside it.
    VisualizerStyle.BARS -> BARS_BASELINE_FRACTION
    else -> 0.5f
}

@Composable
fun VisualizerTrack(
    style: VisualizerStyle,
    fraction: Float,
    level: () -> Float,
    bands: FloatArray,
    peaks: FloatArray = bands,
    revision: () -> Int = { 0 },
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val colors = KoalaTheme.colors
    val played = fraction.coerceIn(0f, 1f)
    val active = if (colors.isDark) colors.accentFill else colors.accentInk
    val inactive = colors.track

    if (style == VisualizerStyle.OFF) {
        ProgressTrack(
            percent = (played * 100).toInt(),
            modifier = modifier,
            height = height,
        )
        return
    }

    val trackModifier = modifier
        .fillMaxWidth()
        .height(if (style == VisualizerStyle.LEVEL) LEVEL_MAX_HEIGHT else AUDIO_TRACK_HEIGHT)
        .clearAndSetSemantics { }

    when (style) {
        VisualizerStyle.OFF -> Unit

        VisualizerStyle.LEVEL -> Spacer(
            trackModifier.drawWithCache {
                onDrawBehind {
                    // The bar keeps its shape and only swells, so it never stops
                    // looking like the thing you drag.
                    val strength = level().coerceIn(0f, 1f)
                    val thickness = size.height * (BASE_THICKNESS + (1f - BASE_THICKNESS) * strength)
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
            },
        )

        VisualizerStyle.WAVEFORM -> Spacer(
            trackModifier.drawWithCache {
                val metrics = Metrics(this)
                // Paths are allocated once per size, not once per frame. A `Path`
                // wraps a native object; three of them sixty times a second is a
                // steady drip of allocation and finalisation underneath an
                // animation that has to hit a 8–16 ms budget, and it showed up as
                // exactly the stutter this display is judged on.
                val upper = Path()
                val lower = Path()
                val body = Path()
                val fill = Brush.verticalGradient(
                    0f to active.copy(alpha = 0.34f),
                    0.5f to active.copy(alpha = 0.14f),
                    1f to active.copy(alpha = 0.34f),
                )
                onDrawBehind {
                    revision()
                    drawWave(bands, active, fill, metrics, upper, lower, body)
                    drawProgressLine(played, inactive, active, size.height / 2f, metrics)
                }
            },
        )

        VisualizerStyle.BARS -> Spacer(
            trackModifier.drawWithCache {
                val metrics = Metrics(this)
                onDrawBehind {
                    revision()
                    val baseline = size.height * BARS_BASELINE_FRACTION
                    drawBars(bands, peaks, active, metrics, baseline - metrics.progressHeight / 2f)
                    drawProgressLine(played, inactive, active, baseline, metrics)
                }
            },
        )

        VisualizerStyle.PULSE -> Spacer(
            trackModifier.drawWithCache {
                val metrics = Metrics(this)
                onDrawBehind {
                    val centre = size.height / 2f
                    val strength = level().coerceIn(0f, 1f)
                    val playheadX = size.width * played

                    // The line first, the rings over it — the same ordering mistake
                    // the dots had. Filled discs drawn underneath were swallowed by
                    // the bar, which is most of what a quiet passage produced.
                    drawProgressLine(played, inactive, active, centre, metrics)
                    if (played > 0f) {
                        // Stroked rather than filled, so the bar reads through them
                        // and a ring stays a ring rather than becoming a blob at the
                        // playhead. Three rings at staggered radii read as something
                        // travelling outward; two read as a thick circle.
                        repeat(PULSE_RINGS) { index ->
                            val spread = index / (PULSE_RINGS - 1f)
                            val radius = metrics.pulseBase +
                                metrics.pulseGrowth * (spread + strength * (0.35f + spread))
                            drawCircle(
                                color = active.copy(
                                    alpha = (PULSE_ALPHA_NEAR + strength * PULSE_ALPHA_GROWTH) *
                                        (1f - spread * 0.62f),
                                ),
                                radius = radius,
                                center = Offset(playheadX, centre),
                                style = Stroke(width = metrics.pulseStroke),
                            )
                        }
                    }
                }
            },
        )

        VisualizerStyle.SPECTRUM -> Spacer(
            trackModifier.drawWithCache {
                val metrics = Metrics(this)
                val column = Brush.verticalGradient(
                    0f to active.copy(alpha = 0.26f),
                    0.5f to active,
                    1f to active.copy(alpha = 0.26f),
                )
                onDrawBehind {
                    revision()
                    drawMirroredColumns(bands, column, metrics)
                    drawProgressLine(played, inactive, active, size.height / 2f, metrics)
                }
            },
        )

        VisualizerStyle.RIBBON -> Spacer(
            trackModifier.drawWithCache {
                val metrics = Metrics(this)
                val top = Path()
                val bottom = Path()
                val body = Path()
                val fill = Brush.verticalGradient(
                    0f to active.copy(alpha = 0.05f),
                    0.5f to active.copy(alpha = 0.32f),
                    1f to active.copy(alpha = 0.05f),
                )
                onDrawBehind {
                    revision()
                    drawRibbon(bands, active, fill, metrics, top, bottom, body)
                    drawProgressLine(played, inactive, active, size.height / 2f, metrics)
                }
            },
        )

        VisualizerStyle.VU -> Spacer(
            trackModifier.drawWithCache {
                val metrics = Metrics(this)
                onDrawBehind {
                    revision()
                    drawVuMeter(bands, active, inactive, metrics)
                    drawProgressLine(played, inactive, active, size.height / 2f, metrics)
                }
            },
        )

        VisualizerStyle.CONSTELLATION -> Spacer(
            trackModifier.drawWithCache {
                val metrics = Metrics(this)
                val thread = Path()
                onDrawBehind {
                    revision()
                    drawConstellation(bands, active, metrics, thread)
                    drawProgressLine(played, inactive, active, size.height / 2f, metrics)
                }
            },
        )
    }
}

/**
 * Every dimension the styles draw with, in pixels, resolved once per size change.
 *
 * These used to be bare `Float` constants in pixels, which is a density bug rather
 * than a shortcut: `2f` is two thirds of a dp on a 3x phone. Every gap, stroke,
 * dot and meter lane in this file was being drawn at roughly a third of its
 * intended size on an ordinary modern handset and at full size only on a 1x
 * display nobody has — which is a large part of why the styles read as thin and
 * unfinished on a phone but fine in a preview.
 */
private class Metrics(density: Density) {
    val progressHeight = with(density) { PROGRESS_HEIGHT.toPx() }
    val minBar = with(density) { MIN_BAR.toPx() }
    val barGap = with(density) { BAR_GAP.toPx() }
    val minBarWidth = with(density) { MIN_BAR_WIDTH.toPx() }
    val peakHeight = with(density) { PEAK_HEIGHT.toPx() }
    val barCorner = with(density) { BAR_CORNER.toPx() }
    val waveStroke = with(density) { WAVE_STROKE.toPx() }
    val ribbonStroke = with(density) { RIBBON_STROKE.toPx() }
    val pulseBase = with(density) { PULSE_BASE_RADIUS.toPx() }
    val pulseGrowth = with(density) { PULSE_GROWTH.toPx() }
    val pulseStroke = with(density) { PULSE_STROKE.toPx() }
    val vuLaneHeight = with(density) { VU_LANE_HEIGHT.toPx() }
    val vuCorner = with(density) { VU_CORNER.toPx() }
    val vuGap = with(density) { VU_GAP.toPx() }
    val dotRadius = with(density) { DOT_RADIUS.toPx() }
    val dotGrowth = with(density) { DOT_GROWTH.toPx() }
}

/**
 * A continuous wave across the spectrum: low frequencies at the left, high at the
 * right, mirrored about the centre line and filled.
 *
 * Points are joined with a Catmull-Rom-style midpoint curve rather than straight
 * segments, because 48 straight joins over 300px read as a saw, not a wave.
 */
private fun DrawScope.drawWave(
    bands: FloatArray,
    colour: Color,
    fill: Brush,
    metrics: Metrics,
    upper: Path,
    lower: Path,
    body: Path,
) {
    if (bands.size < 2) return
    upper.rewind()
    lower.rewind()
    body.rewind()

    val centre = size.height / 2f
    val available = (size.height - metrics.progressHeight) / 2f
    val step = size.width / (bands.size - 1)

    fun heightAt(index: Int): Float =
        max(metrics.minBar / 2f, available * bands[index].coerceIn(0f, 1f))

    upper.moveTo(0f, centre - heightAt(0))
    lower.moveTo(0f, centre + heightAt(0))
    body.moveTo(0f, centre - heightAt(0))
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
        body.cubicTo(midX, centre - h, midX, centre - nextH, nextX, centre - nextH)
    }

    // Closed into a single band so the wave reads as one body rather than as two
    // unrelated lines.
    body.lineTo(size.width, centre + heightAt(bands.size - 1))
    for (index in bands.size - 1 downTo 1) {
        val x = index * step
        val previousX = (index - 1) * step
        val midX = (x + previousX) / 2f
        body.cubicTo(
            midX, centre + heightAt(index),
            midX, centre + heightAt(index - 1),
            previousX, centre + heightAt(index - 1),
        )
    }
    body.close()

    drawPath(path = body, brush = fill)
    drawPath(path = upper, color = colour.copy(alpha = WAVE_LINE_ALPHA), style = Stroke(width = metrics.waveStroke))
    drawPath(path = lower, color = colour.copy(alpha = WAVE_LINE_ALPHA * 0.5f), style = Stroke(width = metrics.waveStroke))
}

/**
 * The classic equaliser: bars standing on the floor of the track, with peak
 * markers that fall back slowly and the progress line running through them.
 *
 * Three earlier versions of this were wrong in instructive ways. Mirrored about
 * the centre, it drew the same silhouette as SPECTRUM and two of the nine styles
 * were one style. Standing on a line through the middle of the track, it was
 * unmistakably an equaliser but had only half the height to grow in — sixteen dp
 * of travel against SPECTRUM's thirty-six — so ordinary speech never left the
 * bottom and the row read as a smear of beads. Standing on the floor of the track
 * with the line still through its middle was worse than either: the line stopped
 * being anything the bars related to and became a rule drawn across a picture.
 *
 * So the line moves. It sits at the foot of the track and the bars stand on it,
 * and [visualizerProgressLineFraction] tells the scrubber to put its drag thumb
 * there too.
 *
 * Bar width is derived from the width available rather than fixed, which is what
 * lets the same band count fill a phone and a tablet without this code knowing
 * which it is on.
 */
private fun DrawScope.drawBars(
    bands: FloatArray,
    peaks: FloatArray?,
    colour: Color,
    metrics: Metrics,
    floor: Float,
) {
    if (bands.isEmpty()) return
    val slot = size.width / bands.size
    val barWidth = max(metrics.minBarWidth, slot - metrics.barGap)
    val headroom = floor - metrics.peakHeight * 2f
    // Capped rather than half the bar width. At barWidth/2 a bar shorter than it is
    // wide becomes a circle, and quiet speech is almost entirely short bars — which
    // is why a row of them read as beads rather than as a meter.
    val corner = CornerRadius(min(barWidth / 2f, metrics.barCorner), min(barWidth / 2f, metrics.barCorner))

    for (index in bands.indices) {
        val energy = bands[index].coerceIn(0f, 1f)
        val x = index * slot + (slot - barWidth) / 2f
        // A floor rather than zero: silence should read as a quiet bar, not as a
        // hole in the track.
        val bar = max(metrics.minBar, headroom * energy)
        drawRoundRect(
            // Loud bars are drawn nearly solid and quiet ones stay well back, so a
            // busy passage has contrast within it rather than being one flat block
            // of colour at a single opacity.
            color = colour.copy(alpha = BAR_ALPHA_FLOOR + energy * BAR_ALPHA_GROWTH),
            topLeft = Offset(x, floor - bar),
            size = Size(barWidth, bar),
            cornerRadius = corner,
        )
        // A peak that falls back slowly is what makes a spectrum readable at a
        // glance rather than a blur of moving sticks — but only where it is
        // actually above the bar. Drawn unconditionally, the quiet top end of a
        // speech spectrum got a cap sitting a pixel over every stub, and forty
        // eight of those in a row read as a dotted rule ruled across the track.
        if (peaks != null && index < peaks.size &&
            peaks[index] - energy > PEAK_VISIBLE_MARGIN
        ) {
            val peak = max(bar + metrics.peakHeight * 1.5f, headroom * peaks[index].coerceIn(0f, 1f))
            drawRoundRect(
                color = colour.copy(alpha = PEAK_ALPHA),
                topLeft = Offset(x, floor - peak - metrics.peakHeight),
                size = Size(barWidth, metrics.peakHeight),
                cornerRadius = CornerRadius(metrics.peakHeight / 2f, metrics.peakHeight / 2f),
            )
        }
    }
}

/**
 * Symmetric columns about the centre axis, each shaded bright in the middle and
 * fading at both ends, so the row reads as light coming through a slot rather
 * than as a fence.
 */
private fun DrawScope.drawMirroredColumns(bands: FloatArray, column: Brush, metrics: Metrics) {
    if (bands.isEmpty()) return
    val centre = size.height / 2f
    val slot = size.width / bands.size
    val width = max(metrics.minBarWidth, slot - metrics.barGap)
    for (index in bands.indices) {
        val energy = bands[index].coerceIn(0f, 1f)
        val columnHeight = max(metrics.minBar, energy * size.height * MIRROR_MAX_FRACTION)
        drawRoundRect(
            brush = column,
            topLeft = Offset(index * slot + (slot - width) / 2f, centre - columnHeight / 2f),
            size = Size(width, columnHeight),
            cornerRadius = CornerRadius(width / 2f, width / 2f),
            alpha = MIRROR_ALPHA_FLOOR + energy * MIRROR_ALPHA_GROWTH,
        )
    }
}

/**
 * A soft, layered contour: a filled body between two mirrored edges, the upper one
 * drawn firmly and the lower one as an echo.
 *
 * Straight segments here rather than the wave's curves, deliberately: with both
 * styles curved and filled they were the same drawing at two opacities.
 */
private fun DrawScope.drawRibbon(
    bands: FloatArray,
    colour: Color,
    fill: Brush,
    metrics: Metrics,
    top: Path,
    bottom: Path,
    body: Path,
) {
    if (bands.size < 2) return
    top.rewind()
    bottom.rewind()
    body.rewind()

    val centre = size.height / 2f
    val step = size.width / (bands.size - 1)
    fun extentAt(index: Int): Float =
        metrics.minBar + bands[index].coerceIn(0f, 1f) * size.height * RIBBON_MAX_FRACTION

    for (index in bands.indices) {
        val x = index * step
        val extent = extentAt(index)
        if (index == 0) {
            top.moveTo(x, centre - extent)
            bottom.moveTo(x, centre + extent)
            body.moveTo(x, centre - extent)
        } else {
            top.lineTo(x, centre - extent)
            bottom.lineTo(x, centre + extent)
            body.lineTo(x, centre - extent)
        }
    }
    for (index in bands.lastIndex downTo 0) {
        body.lineTo(index * step, centre + extentAt(index))
    }
    body.close()

    drawPath(body, fill)
    drawPath(top, colour.copy(alpha = 0.85f), style = Stroke(width = metrics.ribbonStroke))
    drawPath(bottom, colour.copy(alpha = 0.34f), style = Stroke(width = metrics.ribbonStroke * 0.8f))
}

/**
 * Two segmented meters, low half of the spectrum over high half, with the top few
 * segments of each lane held brighter the way a hardware meter marks its headroom.
 *
 * The averages are computed with a plain loop rather than `take`/`drop`/`average`,
 * which allocated three lists and a boxed `Double` on every frame of an animation
 * that runs while the screen is on.
 */
private fun DrawScope.drawVuMeter(
    bands: FloatArray,
    colour: Color,
    inactive: Color,
    metrics: Metrics,
) {
    if (bands.isEmpty()) return
    val half = max(1, bands.size / 2)
    var lowSum = 0f
    for (index in 0 until half) lowSum += bands[index]
    var highSum = 0f
    val highCount = max(1, bands.size - half)
    for (index in half until bands.size) highSum += bands[index]
    val low = min(1f, lowSum / half * VU_LOW_GAIN)
    val high = min(1f, highSum / highCount * VU_HIGH_GAIN)

    val segmentWidth = (size.width - metrics.vuGap * (VU_SEGMENTS - 1)) / VU_SEGMENTS
    val centre = size.height / 2f
    // One lane either side of the progress line. Two corrections from seeing this
    // on a device: the lanes were a fixed five *raw pixels*, which on a 3x phone is
    // a pair of hairlines lost in an otherwise empty 40dp box; and then, filling
    // the space instead, they became thirteen-dp slabs that read as one solid
    // block rather than as a meter. A meter's segments have to be small enough
    // that you count them.
    val clearance = metrics.progressHeight / 2f + metrics.vuGap * 2f
    val laneHeight = min(metrics.vuLaneHeight, max(1f, centre - clearance - metrics.vuGap))
    val corner = CornerRadius(metrics.vuCorner, metrics.vuCorner)

    for (lane in 0..1) {
        val strength = if (lane == 0) high else low
        val y = if (lane == 0) centre - clearance - laneHeight else centre + clearance
        for (index in 0 until VU_SEGMENTS) {
            val threshold = (index + 1f) / VU_SEGMENTS
            val lit = threshold <= strength
            // The last few segments are the headroom marks; lighting them has to
            // look like a different event from lighting the body of the meter.
            val alpha = when {
                lit && index >= VU_SEGMENTS - VU_HEADROOM_SEGMENTS -> 1f
                lit -> 0.8f
                else -> 0.45f
            }
            drawRoundRect(
                color = (if (lit) colour else inactive).copy(alpha = alpha),
                topLeft = Offset(index * (segmentWidth + metrics.vuGap), y),
                size = Size(segmentWidth, laneHeight),
                cornerRadius = corner,
            )
        }
    }
}

/**
 * Energy points spread across the width, threaded together.
 *
 * The alternation is by thirds rather than strictly up/down: strict alternation
 * produced a zigzag of constant shape whose only variable was its amplitude, which
 * is not what the name promises and not interesting to look at.
 */
private fun DrawScope.drawConstellation(
    bands: FloatArray,
    colour: Color,
    metrics: Metrics,
    thread: Path,
) {
    if (bands.isEmpty()) return
    thread.rewind()
    val centre = size.height / 2f
    val reach = size.height * CONSTELLATION_REACH

    fun pointAt(index: Int): Offset {
        val bandIndex = (index * (bands.size - 1) / (CONSTELLATION_POINTS - 1f)).toInt()
        val energy = bands[bandIndex].coerceIn(0f, 1f)
        val side = CONSTELLATION_SIDES[index % CONSTELLATION_SIDES.size]
        return Offset(
            x = size.width * index / (CONSTELLATION_POINTS - 1f),
            y = centre + side * energy * reach,
        )
    }

    for (index in 0 until CONSTELLATION_POINTS) {
        val point = pointAt(index)
        if (index == 0) thread.moveTo(point.x, point.y) else thread.lineTo(point.x, point.y)
    }
    drawPath(thread, colour.copy(alpha = 0.3f), style = Stroke(width = metrics.ribbonStroke * 0.7f))

    for (index in 0 until CONSTELLATION_POINTS) {
        val bandIndex = (index * (bands.size - 1) / (CONSTELLATION_POINTS - 1f)).toInt()
        val energy = bands[bandIndex].coerceIn(0f, 1f)
        val point = pointAt(index)
        // A wide, faint disc under a small solid one. One flat dot at a fixed
        // radius disappears against a busy background; the halo is what makes the
        // point read as a light rather than as a speck of paint.
        drawCircle(
            colour.copy(alpha = 0.12f + energy * 0.16f),
            radius = metrics.dotRadius + energy * metrics.dotGrowth * 2.4f,
            center = point,
        )
        drawCircle(
            colour.copy(alpha = 0.7f + energy * 0.3f),
            radius = metrics.dotRadius + energy * metrics.dotGrowth,
            center = point,
        )
    }
}

private fun DrawScope.drawProgressLine(
    played: Float,
    inactive: Color,
    active: Color,
    centre: Float,
    metrics: Metrics,
) {
    val trackTop = centre - metrics.progressHeight / 2f
    val radius = CornerRadius(metrics.progressHeight / 2f, metrics.progressHeight / 2f)
    drawRoundRect(
        color = inactive,
        topLeft = Offset(0f, trackTop),
        size = Size(size.width, metrics.progressHeight),
        cornerRadius = radius,
    )
    if (played > 0f) {
        drawRoundRect(
            color = active,
            topLeft = Offset(0f, trackTop),
            size = Size(size.width * played, metrics.progressHeight),
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
        level = { 0.7f },
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

/**
 * The foot of the bar track, as a fraction of its height: low enough that the line
 * is the floor rather than a rule through the middle, high enough that the line's
 * own thickness stays inside the track.
 */
private const val BARS_BASELINE_FRACTION = 0.95f
private const val BASE_THICKNESS = 0.22f

// Dimensions, in dp. Everything here used to be a raw pixel count, which on a 3x
// phone drew each of these at a third of the size the numbers suggest.
private val MIN_BAR = 2.dp
private val MIN_BAR_WIDTH = 1.5.dp
private val BAR_GAP = 1.5.dp
private val PROGRESS_HEIGHT = 4.dp
private val PEAK_HEIGHT = 2.dp
private val BAR_CORNER = 1.5.dp
private val WAVE_STROKE = 1.5.dp
private val RIBBON_STROKE = 1.5.dp
private val PULSE_BASE_RADIUS = 4.dp
private val PULSE_GROWTH = 5.dp
private val PULSE_STROKE = 1.dp
private val VU_LANE_HEIGHT = 7.dp
private val VU_CORNER = 1.5.dp
private val VU_GAP = 1.5.dp
private val DOT_RADIUS = 2.dp
private val DOT_GROWTH = 2.5.dp

/** The wave is context around the bar, not a competing element. */
private const val WAVE_LINE_ALPHA = 0.9f
private const val BAR_ALPHA_FLOOR = 0.42f
private const val BAR_ALPHA_GROWTH = 0.5f
private const val PEAK_ALPHA = 0.62f
/** Below this the cap would sit on top of its own bar and draw a dotted rule. */
private const val PEAK_VISIBLE_MARGIN = 0.05f
private const val MIRROR_MAX_FRACTION = 0.92f
private const val MIRROR_ALPHA_FLOOR = 0.5f
private const val MIRROR_ALPHA_GROWTH = 0.5f
private const val RIBBON_MAX_FRACTION = 0.4f
private const val VU_SEGMENTS = 24
private const val VU_HEADROOM_SEGMENTS = 4
/**
 * The band averages already come through the auto-gain, so they sit well up the
 * scale on their own. The old 1.35/1.8 lift pushed both lanes to the top and held
 * them there, which is a meter that has stopped measuring.
 */
private const val VU_LOW_GAIN = 0.95f
private const val VU_HIGH_GAIN = 1.25f
private const val CONSTELLATION_POINTS = 18
private const val CONSTELLATION_REACH = 0.36f
/** Thirds rather than a strict up/down flip; see [drawConstellation]. */
private val CONSTELLATION_SIDES = floatArrayOf(-1f, 0.45f, 1f, -0.45f)
private const val PULSE_RINGS = 3
/** Big enough at silence that the playhead always carries a visible ring. */
private const val PULSE_ALPHA_NEAR = 0.4f
private const val PULSE_ALPHA_GROWTH = 0.42f
