package net.koalastuff.koalacast.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * @param level current amplitude, 0..1
 * @param history recent amplitudes, oldest first; only read by [VisualizerStyle.WAVEFORM]
 */
@Composable
fun VisualizerTrack(
    style: VisualizerStyle,
    fraction: Float,
    level: Float,
    history: FloatArray,
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
                .height(WAVEFORM_HEIGHT)
                .clearAndSetSemantics { },
        ) {
            if (history.isEmpty()) return@Canvas
            // Two different axes share this row: the wave runs on *recent time* and
            // every bar in it has just been heard, while progress runs on *episode
            // position*. Colouring the wave by progress would claim that audio
            // played two seconds ago is still ahead of the listener, so they stay
            // visually separate — but both centre on the same line.
            //
            // That shared centre is not cosmetic. Material centres the slider's
            // thumb in the track slot's height, so a progress bar drawn anywhere
            // else leaves the thumb floating off it.
            val centre = size.height / 2f
            val gap = GAP_PX
            val barWidth = max(1f, (size.width - gap * (history.size - 1)) / history.size)
            val waveHeight = size.height - PROGRESS_HEIGHT_PX

            // Wave first, mirrored around the centre line, so the progress bar can
            // sit on top of it and stay the most legible thing in the row.
            history.forEachIndexed { index, amplitude ->
                val x = index * (barWidth + gap)
                // A floor rather than zero: silence should read as a quiet bar, not
                // as a hole in the track.
                val bar = max(MIN_BAR_PX, waveHeight * amplitude.coerceIn(0f, 1f))
                drawRoundRect(
                    color = active.copy(alpha = WAVE_ALPHA),
                    topLeft = Offset(x, centre - bar / 2f),
                    size = Size(barWidth, bar),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }

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
        history = PREVIEW_HISTORY,
        modifier = modifier,
    )
}

/** A canned shape that looks like speech: peaks, dips, and a couple of pauses. */
private val PREVIEW_HISTORY = FloatArray(48) { index ->
    val t = index / 47f
    when {
        index % 17 == 0 -> 0.06f
        else -> (0.25f + 0.55f * kotlin.math.abs(kotlin.math.sin(t * 11f)) *
            (0.6f + 0.4f * kotlin.math.cos(t * 3f))).coerceIn(0.06f, 1f)
    }
}

/** Level swells within a fixed band so the row never reflows as audio plays. */
private val LEVEL_MAX_HEIGHT = 10.dp
private val WAVEFORM_HEIGHT = 30.dp
private const val BASE_THICKNESS = 0.4f
private const val GAP_PX = 2f
private const val MIN_BAR_PX = 2f
private const val PROGRESS_HEIGHT_PX = 8f

/** The wave is context around the bar, not a competing element. */
private const val WAVE_ALPHA = 0.45f
