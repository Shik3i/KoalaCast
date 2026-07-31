package net.koalastuff.koalacast.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.player.PlaybackUiState
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.PhosphorIcon
import net.koalastuff.koalacast.core.ui.component.ProgressTrack
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.util.Format

/**
 * The mobile spec's mini player: cover, title, remaining time and speed, +30 s and
 * a 38dp play button, sitting on a 3px progress bar above the tab bar. Tapping it
 * expands to the full player.
 *
 * Renders nothing when there is no track, so the tab bar keeps its place.
 */
@Composable
fun MiniPlayer(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.isActive) return

    MiniPlayerContent(
        state = state,
        onExpand = onExpand,
        onTogglePlayPause = viewModel::togglePlayPause,
        onRetry = viewModel::retry,
        onSeekForward = viewModel::seekForward,
        modifier = modifier,
    )
}

@Composable
internal fun MiniPlayerContent(
    state: PlaybackUiState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onRetry: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KoalaTheme.colors
    val context = LocalContext.current
    val track = state.track ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgTransport),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = KoalaSpacing.gap, vertical = KoalaSpacing.gapSmall),
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(
                url = track.artworkUrl,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                sizeHint = 42.dp,
            )
            val upNext = if (state.upNextCount > 0) {
                stringResource(R.string.player_up_next_count, state.upNextCount)
            } else {
                ""
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = track.title,
                    style = KoalaTheme.type.listTitle,
                    color = colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MonoText(
                    // Time is shown both ways where it matters: what is left, and
                    // at what speed it is being spent.
                    text = buildString {
                        append("-")
                        append(Format.duration(context, state.remainingMs))
                        append(" · ")
                        append(Format.speed(state.speed))
                        if (upNext.isNotEmpty()) {
                            append(" · ")
                            append(upNext)
                        }
                    },
                    color = colors.ink4,
                    style = KoalaTheme.type.monoSmall,
                )
            }

            IconButtonPlain(
                icon = PhosphorIcons.FastForward,
                contentDescription = stringResource(R.string.player_forward_30),
                onClick = onSeekForward,
            )

            PlayButton(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onClick = onTogglePlayPause,
                size = 38.dp,
            )
        }

        state.playbackError?.let { error ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bgPanel)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = KoalaSpacing.gap, vertical = KoalaSpacing.gapTiny),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MonoText(
                    text = stringResource(R.string.player_error_short, error),
                    color = colors.ink3,
                    style = KoalaTheme.type.monoSmall,
                )
                MonoText(
                    text = stringResource(R.string.player_retry),
                    color = colors.accentInk,
                    style = KoalaTheme.type.monoStrong,
                )
            }
        }

        ProgressTrack(
            percent = state.progressPercent,
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            height = 3.dp,
        )
    }
}

/** The 40dp accent circle from the transport bar, scaled per surface. */
@Composable
internal fun PlayButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val colors = KoalaTheme.colors
    val ground = if (colors.isDark) colors.accentFill else colors.accentInk
    val glyph = if (colors.isDark) colors.accentOn else colors.bgPanel

    Box(
        modifier = modifier
            .size(size)
            .clip(KoalaShapes.round)
            .background(ground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Crossfaded and slightly scaled rather than swapped: the glyph replacing
        // itself between frames is the single most-pressed control in the app and
        // reads as a jump. Short enough that it never delays the tap.
        val showPause = isPlaying || isBuffering
        AnimatedContent(
            targetState = showPause,
            transitionSpec = {
                (fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.7f))
                    .togetherWith(fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.7f))
            },
            label = "playPause",
        ) { pause ->
            PhosphorIcon(
                // Buffering keeps the pause glyph rather than swapping in a spinner:
                // the transport must not flicker on every stall.
                icon = if (pause) PhosphorIcons.PauseFill else PhosphorIcons.PlayFill,
                contentDescription = stringResource(
                    if (isPlaying) R.string.player_pause else R.string.player_play,
                ),
                tint = glyph,
                size = size * 0.42f,
            )
        }
    }
}

@Composable
internal fun IconButtonPlain(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 22.dp,
) {
    Box(
        modifier = modifier
            .size(KoalaSpacing.minTouchTarget)
            .clip(KoalaShapes.round)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PhosphorIcon(
            icon = icon,
            contentDescription = contentDescription,
            tint = KoalaTheme.colors.ink2,
            size = size,
        )
    }
}
