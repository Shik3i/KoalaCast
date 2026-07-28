package net.koalastuff.koalacast.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.Chapter
import net.koalastuff.koalacast.core.player.PlaybackUiState
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.PhosphorIcon
import net.koalastuff.koalacast.core.ui.component.SegmentedControl
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.theme.spotlightGlow
import net.koalastuff.koalacast.core.ui.util.Format
import net.koalastuff.koalacast.core.ui.component.ArtworkAccent

/**
 * The full-screen transport. The desktop design puts these controls in a bar
 * across the bottom; on a phone the same pieces stack, in the same order and with
 * the same 40dp accent play button at the centre.
 */
@Composable
fun NowPlayingScreen(
    onCollapse: () -> Unit,
    onOpenEpisode: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()

    ArtworkAccent(state.track?.artworkUrl) {
        NowPlayingContent(
            state = state,
        chapters = chapters,
        onCollapse = onCollapse,
        onOpenEpisode = onOpenEpisode,
        onTogglePlayPause = viewModel::togglePlayPause,
        onSeekBack = viewModel::seekBack,
        onSeekForward = viewModel::seekForward,
        onSeekTo = viewModel::seekTo,
        onCycleSpeed = viewModel::cycleSpeed,
        onSetSleepTimer = viewModel::setSleepTimer,
            modifier = modifier,
        )
    }
}

@Composable
internal fun NowPlayingContent(
    state: PlaybackUiState,
    modifier: Modifier = Modifier,
    chapters: List<Chapter> = emptyList(),
    onCollapse: () -> Unit,
    onOpenEpisode: (String) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onSetSleepTimer: (Int?, Boolean, Boolean) -> Unit,
) {
    val colors = KoalaTheme.colors
    val context = LocalContext.current
    val track = state.track

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPanel)
            .spotlightGlow(colors.accentFill)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = KoalaSpacing.screenH),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapLarge),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            IconButtonPlain(
                icon = PhosphorIcons.CaretDown,
                contentDescription = stringResource(R.string.player_collapse),
                onClick = onCollapse,
            )
        }

        if (track == null) {
            Text(
                text = stringResource(R.string.player_nothing_playing),
                style = KoalaTheme.type.body,
                color = colors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }

        CoverArt(
            url = track.artworkUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .aspectRatio(1f)
                .align(Alignment.CenterHorizontally),
            sizeHint = 512.dp,
        )

        Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny)) {
            MonoText(
                text = track.podcastTitle,
                color = colors.accentInk,
                style = KoalaTheme.type.monoSmall,
                modifier = Modifier.clickable { onOpenEpisode(track.episodeId) },
            )
            Text(
                text = track.title,
                style = KoalaTheme.type.sectionTitle,
                color = colors.inkStrong,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Scrubber(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            chapters = chapters,
            onSeekTo = onSeekTo,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButtonPlain(
                icon = PhosphorIcons.Rewind,
                contentDescription = stringResource(R.string.player_back_15),
                onClick = onSeekBack,
                size = 26.dp,
            )
            PlayButton(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onClick = onTogglePlayPause,
                size = 64.dp,
            )
            IconButtonPlain(
                icon = PhosphorIcons.FastForward,
                contentDescription = stringResource(R.string.player_forward_30),
                onClick = onSeekForward,
                size = 26.dp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.clickable(onClick = onCycleSpeed)) {
                MonoText(
                    text = Format.speed(state.speed),
                    color = colors.ink2,
                    style = KoalaTheme.type.monoStrong,
                )
            }
            MonoText(
                text = stringResource(R.string.player_up_next_count, state.upNextCount),
                color = colors.ink4,
                style = KoalaTheme.type.monoSmall,
            )
        }

        if (chapters.isNotEmpty()) {
            ChapterRow(
                chapters = chapters,
                positionMs = state.positionMs,
                onSeekTo = onSeekTo,
            )
        }

        SleepTimerRow(state = state, onSetSleepTimer = onSetSleepTimer)
    }
}

@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    chapters: List<Chapter>,
    onSeekTo: (Long) -> Unit,
) {
    val colors = KoalaTheme.colors
    // While a drag is in flight the slider follows the finger, not the player,
    // or the thumb would fight every position tick.
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val fraction = dragValue
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val markers = remember(chapters, durationMs) {
        ChapterState.markerFractions(chapters, durationMs)
    }

    Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny)) {
        Box {
            Slider(
            value = fraction,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { onSeekTo((it * durationMs).toLong()) }
                dragValue = null
            },
            colors = SliderDefaults.colors(
                thumbColor = if (colors.isDark) colors.ink else colors.inkStrong,
                activeTrackColor = if (colors.isDark) colors.accentFill else colors.accentInk,
                inactiveTrackColor = colors.track,
            ),
        )
            // Drawn over the slider rather than inside it: Material's Slider has
            // no hook for tick marks at arbitrary positions, only evenly spaced
            // steps, which is not what chapter starts are.
            if (markers.isNotEmpty()) {
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(horizontal = SLIDER_THUMB_INSET),
                ) {
                    val y = size.height / 2f
                    markers.forEach { fraction ->
                        drawLine(
                            color = colors.bgTransport,
                            start = Offset(size.width * fraction, y - MARKER_HALF_HEIGHT_PX),
                            end = Offset(size.width * fraction, y + MARKER_HALF_HEIGHT_PX),
                            strokeWidth = MARKER_WIDTH_PX,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MonoText(
                text = Format.timeCode((fraction * durationMs).toLong()),
                color = colors.ink3,
                style = KoalaTheme.type.monoStrong,
            )
            MonoText(
                text = "-" + Format.timeCode(durationMs - (fraction * durationMs).toLong()),
                color = colors.ink3,
                style = KoalaTheme.type.monoStrong,
            )
        }
    }
}

/**
 * Which chapter is running, with a step either way. Absent entirely when the
 * episode has no chapters, rather than showing a disabled control.
 */
@Composable
private fun ChapterRow(
    chapters: List<Chapter>,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    val colors = KoalaTheme.colors
    val current = ChapterState.current(chapters, positionMs)
    val previous = ChapterState.previousStartMs(chapters, positionMs)
    val next = ChapterState.nextStartMs(chapters, positionMs)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButtonSquare(
            icon = PhosphorIcons.Rewind,
            contentDescription = stringResource(R.string.player_previous_chapter),
            onClick = { previous?.let(onSeekTo) },
            enabled = previous != null,
            boxSize = 30.dp,
            iconSize = 15.dp,
        )
        Text(
            text = current?.title ?: stringResource(R.string.player_before_first_chapter),
            style = KoalaTheme.type.bodySmall,
            color = if (current != null) colors.ink2 else colors.ink4,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButtonSquare(
            icon = PhosphorIcons.FastForward,
            contentDescription = stringResource(R.string.player_next_chapter),
            onClick = { next?.let(onSeekTo) },
            enabled = next != null,
            boxSize = 30.dp,
            iconSize = 15.dp,
        )
    }
}

@Composable
private fun SleepTimerRow(
    state: PlaybackUiState,
    onSetSleepTimer: (Int?, Boolean, Boolean) -> Unit,
) {
    val options = listOf<Int?>(null, 5, 15, 30)
    val selectedIndex = when {
        state.sleepAtChapterEnd -> 4
        state.sleepAtEpisodeEnd -> 5
        else -> options.indexOf(state.sleepMinutes).takeIf { it >= 0 } ?: 0
    }

    Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhosphorIcon(
                icon = PhosphorIcons.Moon,
                contentDescription = null,
                tint = KoalaTheme.colors.ink4,
                size = 15.dp,
            )
            MonoText(
                text = stringResource(R.string.player_sleep_timer),
                color = KoalaTheme.colors.ink4,
                style = KoalaTheme.type.monoSmall,
            )
        }
        SegmentedControl(
            options = listOf(
                stringResource(R.string.player_sleep_off),
                stringResource(R.string.player_sleep_minutes, 5),
                stringResource(R.string.player_sleep_minutes, 15),
                stringResource(R.string.player_sleep_minutes, 30),
                stringResource(R.string.player_sleep_chapter_end),
                stringResource(R.string.player_sleep_episode_end),
            ),
            selectedIndex = selectedIndex,
            onSelect = { index ->
                when (index) {
                    4 -> onSetSleepTimer(null, false, true)
                    5 -> onSetSleepTimer(null, true, false)
                    else -> onSetSleepTimer(options[index], false, false)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Material's Slider insets its track by half a thumb; the markers must match. */
private val SLIDER_THUMB_INSET = 10.dp
private const val MARKER_WIDTH_PX = 2f
private const val MARKER_HALF_HEIGHT_PX = 5f
