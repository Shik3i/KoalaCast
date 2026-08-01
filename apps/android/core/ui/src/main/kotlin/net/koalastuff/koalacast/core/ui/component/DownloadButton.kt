package net.koalastuff.koalacast.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.model.DownloadState
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.theme.reduceMotion

/**
 * The download control, built like [EpisodeProgressButton] rather than like a
 * toggle.
 *
 * The old control was a flat icon that changed colour once and then sat there for
 * the length of a download — no percentage, no motion, nothing to distinguish
 * "queued" from "downloading" from "stuck". It read as broken, and for a
 * forty-minute episode on a slow connection it effectively was: the listener had
 * no way to tell whether anything was happening.
 *
 * So the outline carries the answer. It fills around the same rounded square
 * used by the app's other non-play actions, moves while the download is queued
 * but has no measurable progress yet, and settles when the file is on device.
 *
 * @param progressPercent 0–100, clamped. Ignored unless [state] is downloading.
 */
@Composable
fun DownloadButton(
    state: DownloadState?,
    progressPercent: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val colors = KoalaTheme.colors
    val accent = if (colors.isDark) colors.accentFill else colors.accentInk
    val outlineWidth = 3.dp
    val active = state == DownloadState.DOWNLOADING || state == DownloadState.QUEUED
    val motionReduced = reduceMotion()
    val buttonDescription = contentDescription
    val touchSize = maxOf(size, KoalaSpacing.minTouchTarget)

    // Animated rather than snapped: progress arrives in lumps as chunks land, and
    // an outline that jumps 12% at a time looks like a stutter rather than a download.
    val target = when (state) {
        DownloadState.DONE -> 1f
        DownloadState.DOWNLOADING, DownloadState.QUEUED, DownloadState.PAUSED ->
            progressPercent.coerceIn(0, 100) / 100f
        else -> 0f
    }
    val sweep by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 400, easing = LinearEasing),
        label = "downloadSweep",
    )

    // Until the first bytes report a size there is nothing honest to fill, so the
    // outline segment moves instead — motion that says "working" without claiming a figure.
    val indeterminate = active && sweep <= 0.01f
    // Do not keep an infinite transition alive for every idle/completed episode
    // row. Long lists otherwise recompose forever even though nothing moves.
    val spin = if (indeterminate && !motionReduced) {
        val spinner = rememberInfiniteTransition(label = "downloadSpin")
        val animated by spinner.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "spin",
        )
        animated
    } else {
        0f
    }

    val iconTint = when (state) {
        DownloadState.DONE -> accent
        DownloadState.FAILED -> colors.ink2
        else -> if (active) accent else colors.ink3
    }
    // A gentle pulse while working, not a one-off resize. Start the infinite
    // transition only for active controls so a long episode list stays idle.
    val iconScale = if (active && !motionReduced) {
        val pulse = rememberInfiniteTransition(label = "downloadPulse")
        val animated by pulse.animateFloat(
            initialValue = 0.32f,
            targetValue = 0.40f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "downloadIconScale",
        )
        animated
    } else if (active) {
        0.36f
    } else {
        0.38f
    }

    Box(
        modifier = modifier
            .size(touchSize)
            .clip(KoalaShapes.chip)
            .semantics { this.contentDescription = buttonDescription }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(size),
        ) {
            val strokeWidth = outlineWidth.toPx()
            val inset = strokeWidth / 2f
            val radius = DOWNLOAD_CORNER_RADIUS.toPx()
            val outline = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = inset,
                        top = inset,
                        right = this@Canvas.size.width - inset,
                        bottom = this@Canvas.size.height - inset,
                        cornerRadius = CornerRadius(radius, radius),
                    ),
                )
            }
            val measure = PathMeasure().apply { setPath(outline, forceClosed = true) }
            val pathStyle = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            drawPath(path = outline, color = colors.borderUi, style = pathStyle)

            val drawnFraction = if (indeterminate) INDETERMINATE_FRACTION else sweep
            if (drawnFraction > 0f) {
                val start = if (indeterminate) measure.length * (spin / 360f) else 0f
                val end = start + measure.length * drawnFraction
                val progressPath = Path()
                if (end <= measure.length) {
                    measure.getSegment(start, end, progressPath, startWithMoveTo = true)
                } else {
                    measure.getSegment(start, measure.length, progressPath, startWithMoveTo = true)
                    measure.getSegment(0f, end - measure.length, progressPath, startWithMoveTo = true)
                }
                drawPath(path = progressPath, color = accent, style = pathStyle)
            }
        }

        PhosphorIcon(
            icon = when (state) {
                DownloadState.DONE -> PhosphorIcons.Check
                DownloadState.FAILED -> PhosphorIcons.ArrowClockwise
                else -> PhosphorIcons.DownloadSimple
            },
            contentDescription = null,
            tint = iconTint,
            size = size * iconScale,
        )
    }
}

/**
 * [DownloadButton] with its name underneath, for the episode screen's action row
 * where it sits beside Queue, Save and Played.
 */
@Composable
fun LabelledDownloadAction(
    state: DownloadState?,
    progressPercent: Int,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KoalaTheme.colors
    val active = state == DownloadState.DOWNLOADING || state == DownloadState.QUEUED
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DownloadButton(
            state = state,
            progressPercent = progressPercent,
            contentDescription = contentDescription,
            onClick = onClick,
            size = 40.dp,
        )
        MonoText(
            text = label,
            color = if (active || state == DownloadState.DONE) {
                if (colors.isDark) colors.accentFill else colors.accentInk
            } else {
                colors.ink3
            },
            style = KoalaTheme.type.monoSmall,
            maxLines = 1,
        )
    }
}

/** Enough outline to read as movement rather than as a stalled 25%. */
private const val INDETERMINATE_FRACTION = 0.25f
private val DOWNLOAD_CORNER_RADIUS = 4.dp
