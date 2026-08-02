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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
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
import net.koalastuff.koalacast.core.ui.theme.KoalaIconButton
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
 * So the outline carries the answer — but only while there is an answer to carry.
 * At rest the control is exactly [IconButtonSquare]: the same rounded square, the
 * same 1dp `border-ui` hairline, no more. The outline thickens to a progress ring
 * when a download actually starts and thins back when it finishes. A permanently
 * heavy ring made every idle episode row look like it was mid-download, which is
 * both louder than its neighbours and simply untrue.
 *
 * @param progressPercent 0–100, clamped. Ignored unless [state] is downloading.
 * @param bordered whether the resting hairline is drawn. False where the control
 *   sits beside unbordered actions — the episode screen's Queue/Save/Played row —
 *   so it does not become the one outlined item in a row of bare icons. The
 *   progress ring still appears there once a download is running.
 */
@Composable
fun DownloadButton(
    state: DownloadState?,
    progressPercent: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    bordered: Boolean = true,
) {
    val buttonDescription = contentDescription
    Box(
        modifier = modifier
            .size(maxOf(size, KoalaSpacing.minTouchTarget))
            .clip(KoalaShapes.chip)
            .semantics { this.contentDescription = buttonDescription }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DownloadGlyph(
            state = state,
            progressPercent = progressPercent,
            size = size,
            bordered = bordered,
        )
    }
}

/**
 * The drawing half of [DownloadButton], without a touch target of its own, so a
 * caller that already owns the click — [LabelledDownloadAction], whose whole
 * icon-and-word column is the button — does not nest two clickables.
 */
@Composable
private fun DownloadGlyph(
    state: DownloadState?,
    progressPercent: Int,
    size: Dp,
    bordered: Boolean,
    iconSize: Dp = size * ICON_FRACTION,
) {
    val colors = KoalaTheme.colors
    val accent = if (colors.isDark) colors.accentFill else colors.accentInk
    val active = state == DownloadState.DOWNLOADING || state == DownloadState.QUEUED
    val motionReduced = reduceMotion()

    // Animated rather than snapped: progress arrives in lumps as chunks land, and
    // an outline that jumps 12% at a time looks like a stutter rather than a download.
    // DONE is deliberately not 1f: a finished download is reported by the tick and
    // the accent tint, not by a permanent ring that outshouts every other control.
    val target = when (state) {
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

    // The whole point of the ring is that it is temporary. It grows out of the
    // ordinary hairline when a download starts and shrinks back into it when the
    // download ends, so the resting control weighs exactly what its neighbours do.
    val showsProgress = active || (state == DownloadState.PAUSED && sweep > 0.01f)
    val outlineWidth by animateDpAsState(
        targetValue = if (showsProgress) ACTIVE_OUTLINE else RESTING_OUTLINE,
        animationSpec = tween(durationMillis = 220),
        label = "downloadOutlineWidth",
    )
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
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "downloadIconScale",
        )
        animated
    } else {
        1f
    }

    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (bordered || outlineWidth > RESTING_OUTLINE) {
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
                // The unbordered variant still needs something for the ring to run
                // on while a download is live, but it fades in and out with the
                // ring rather than sitting there for the row's whole life.
                val trackAlpha = if (bordered) 1f else ((outlineWidth - RESTING_OUTLINE) / (ACTIVE_OUTLINE - RESTING_OUTLINE))
                    .coerceIn(0f, 1f)
                if (trackAlpha > 0f) {
                    drawPath(path = outline, color = colors.borderUi.copy(alpha = trackAlpha), style = pathStyle)
                }

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
        }

        PhosphorIcon(
            icon = when (state) {
                DownloadState.DONE -> PhosphorIcons.Check
                DownloadState.FAILED -> PhosphorIcons.ArrowClockwise
                else -> PhosphorIcons.DownloadSimple
            },
            contentDescription = null,
            tint = iconTint,
            size = iconSize * iconScale,
        )
    }
}

/**
 * [DownloadButton] with its name underneath, for the episode screen's action row
 * where it sits beside Queue, Save and Played.
 *
 * Geometrically this is [LabelledIconAction] — same 20dp glyph, same gap, same
 * padding, same touch minimum, same unbordered rest — so the four controls in that
 * row read as one set. The only thing it adds is the progress ring, and only while
 * there is progress to show.
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
    val actionDescription = contentDescription
    Column(
        modifier = modifier
            .clip(KoalaShapes.chip)
            .semantics { this.contentDescription = actionDescription }
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minWidth = KoalaSpacing.minTouchTarget, minHeight = KoalaSpacing.minTouchTarget)
            .padding(horizontal = KoalaSpacing.gapSmall, vertical = KoalaSpacing.gapSmall),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny),
    ) {
        DownloadGlyph(
            state = state,
            progressPercent = progressPercent,
            // The same fixed slot [LabelledIconAction] reserves, so the ring has
            // somewhere to go and the label still lines up with its neighbours'.
            size = KoalaIconButton.labelledBox,
            bordered = false,
            iconSize = KoalaIconButton.labelledIcon,
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

/** The hairline every other bordered control in the app draws. */
private val RESTING_OUTLINE = 1.dp

/** Thick enough that a 1% sliver of progress is visible at 30dp. */
private val ACTIVE_OUTLINE = 3.dp

/** [IconButtonSquare] puts a 16dp glyph in a 30dp box; match it. */
private const val ICON_FRACTION = 16f / 30f

