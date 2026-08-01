package net.koalastuff.koalacast.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * The play control for an episode row, ported from the web client's
 * `EpisodeProgressButton.svelte`: a circle whose rim fills clockwise with how far
 * into the episode the listener already is, so a half-heard episode is legible at
 * a glance without reading a single number.
 *
 * The episode currently playing shows a waveform instead of a play triangle,
 * which is what distinguishes "resume this" from "this is the one running".
 *
 * @param progressPercent 0–100. Values outside that range are clamped, so callers
 *   can pass a raw computed percentage without guarding it.
 */
@Composable
fun EpisodeProgressButton(
    progressPercent: Int,
    current: Boolean,
    contentDescription: String,
    playing: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val colors = KoalaTheme.colors
    val safeProgress = progressPercent.coerceIn(0, 100)
    // The web draws the ring as a 3px band regardless of button size; keeping it
    // constant is what makes a 1% sliver visible at all.
    val ringWidth = 3.dp
    val accent = if (colors.isDark) colors.accentFill else colors.accentInk
    val buttonDescription = contentDescription
    val touchSize = maxOf(size, KoalaSpacing.minTouchTarget)

    Box(
        modifier = modifier
            .size(touchSize)
            .clip(CircleShape)
            .semantics { this.contentDescription = buttonDescription }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val ring = ringWidth.toPx()
            val inset = ring / 2f
            val arcSize = Size(this.size.width - ring, this.size.height - ring)
            val topLeft = Offset(inset, inset)

            // Unfilled remainder first, then the played portion over it, starting
            // at twelve o'clock like a clock face rather than at three.
            drawArc(
                color = colors.track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = ring),
            )
            if (safeProgress > 0) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = safeProgress * 3.6f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = ring),
                )
            }
            // The hairline the web gets from `border: 1px solid var(--border-ui)`.
            drawCircle(
                color = colors.borderUi,
                radius = this.size.minDimension / 2f - 0.5f,
                style = Stroke(width = 1f),
            )
        }

        // A moving mark rather than a static waveform glyph: in a list, "this is
        // the current episode" and "and it is playing right now" are different
        // facts, and only motion carries the second one.
        if (current) {
            PlayingEqualizer(playing = playing, tint = accent, size = size * 0.34f)
        } else {
            PhosphorIcon(
                icon = PhosphorIcons.PlayFill,
                contentDescription = null,
                tint = colors.ink2,
                size = size * 0.38f,
            )
        }
    }
}
