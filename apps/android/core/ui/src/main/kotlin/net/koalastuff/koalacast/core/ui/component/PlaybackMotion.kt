package net.koalastuff.koalacast.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/**
 * The "this one is playing" mark: bars that actually move.
 *
 * A static waveform glyph says "this is the current episode" but not "and it is
 * running right now", which is the thing a listener glancing at a list wants to
 * know. The bars stop when playback stops, so the animation carries the state
 * rather than decorating it.
 *
 * Decorative on purpose — the row already names the episode and its state, and a
 * screen reader has no use for three rectangles.
 */
@Composable
fun PlayingEqualizer(
    playing: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    // A paused row must not keep the list recomposing. Start the clock only while
    // motion is visible; one shared phase then drives every bar.
    val phase = if (playing) {
        val transition = rememberInfiniteTransition(label = "equalizer")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
        animated
    } else {
        0f
    }

    Canvas(modifier = modifier.size(size).clearAndSetSemantics { }) {
        val bars = BAR_PHASES.size
        val gap = this.size.width / (bars * 3f)
        val barWidth = (this.size.width - gap * (bars - 1)) / bars
        BAR_PHASES.forEachIndexed { index, offset ->
            // Triangle wave rather than a sine: cheaper, and the sharper turn at
            // the peaks reads better at 16dp than a smooth bounce.
            val wave = if (playing) 1f - 2f * abs(((phase + offset) % 1f) - 0.5f) else 0f
            val height = max(
                this.size.height * MIN_BAR,
                this.size.height * (MIN_BAR + (1f - MIN_BAR) * wave),
            )
            val x = index * (barWidth + gap)
            drawRoundRect(
                color = tint,
                topLeft = Offset(x, (this.size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** Offsets chosen so no two bars peak together. */
private val BAR_PHASES = floatArrayOf(0f, 0.33f, 0.66f)

/** Paused bars stay visible as a mark rather than collapsing to nothing. */
private const val MIN_BAR = 0.25f
