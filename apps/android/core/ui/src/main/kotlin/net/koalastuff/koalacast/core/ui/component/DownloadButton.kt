package net.koalastuff.koalacast.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.model.DownloadState
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

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
 * So the ring carries the answer. It fills clockwise with real progress, spins
 * while the download is queued but has no measurable progress yet, and settles
 * into a solid ring when the file is on the device.
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
    val ringWidth = 3.dp
    val active = state == DownloadState.DOWNLOADING || state == DownloadState.QUEUED

    // Animated rather than snapped: progress arrives in lumps as chunks land, and
    // a ring that jumps 12% at a time looks like a stutter rather than a download.
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
    // ring turns instead — motion that says "working" without claiming a figure.
    val spinner = rememberInfiniteTransition(label = "downloadSpin")
    val spin by spinner.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )
    val indeterminate = active && sweep <= 0.01f

    val iconTint = when (state) {
        DownloadState.DONE -> accent
        DownloadState.FAILED -> colors.ink2
        else -> if (active) accent else colors.ink3
    }
    // A gentle pulse in size while working, so the glyph is not the only static
    // thing inside a moving ring.
    val iconSize by animateDpAsState(
        targetValue = if (active) size * 0.34f else size * 0.38f,
        animationSpec = tween(durationMillis = 260),
        label = "downloadIcon",
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(size)
                .then(if (indeterminate) Modifier.rotate(spin) else Modifier),
        ) {
            val ring = ringWidth.toPx()
            val inset = ring / 2f
            val arcSize = Size(this.size.width - ring, this.size.height - ring)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = colors.track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = ring),
            )
            val drawnSweep = if (indeterminate) INDETERMINATE_SWEEP else sweep * 360f
            if (drawnSweep > 0f) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = drawnSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = ring),
                )
            }
            drawCircle(
                color = colors.borderUi,
                radius = this.size.minDimension / 2f - 0.5f,
                style = Stroke(width = 1f),
            )
        }

        PhosphorIcon(
            icon = when (state) {
                DownloadState.DONE -> PhosphorIcons.CheckCircleFill
                DownloadState.FAILED -> PhosphorIcons.ArrowClockwise
                else -> PhosphorIcons.DownloadSimple
            },
            contentDescription = contentDescription,
            tint = iconTint,
            size = iconSize,
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

/** Enough arc to read as a spinner rather than as a stalled 25%. */
private const val INDETERMINATE_SWEEP = 90f
