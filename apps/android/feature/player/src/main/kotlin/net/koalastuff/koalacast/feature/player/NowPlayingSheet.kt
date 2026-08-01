package net.koalastuff.koalacast.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.Chapter
import net.koalastuff.koalacast.core.model.VisualizerStyle
import net.koalastuff.koalacast.core.player.PlaybackUiState
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.OutlineButton
import net.koalastuff.koalacast.core.ui.component.PhosphorIcon
import net.koalastuff.koalacast.core.ui.component.MenuAction
import net.koalastuff.koalacast.core.ui.component.MenuButton
import net.koalastuff.koalacast.core.ui.component.VisualizerTrack
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.theme.spotlightGlow
import net.koalastuff.koalacast.core.ui.util.Format
import kotlinx.coroutines.delay

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
    val style by viewModel.visualizer.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Motion for its own sake is exactly what the system's animation scale is for,
    // so honouring it means falling back to the plain bar rather than slowing down.
    val effectiveStyle = remember(style, context) {
        if (style.needsAudio && animationsDisabled(context)) VisualizerStyle.OFF else style
    }

    // The tap does no arithmetic unless something is drawing it, and "something" has
    // to include being on screen — not merely having been opened once.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, effectiveStyle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setVisualizerActive(effectiveStyle.needsAudio)
                Lifecycle.Event.ON_STOP -> viewModel.setVisualizerActive(false)
                else -> Unit
            }
        }
        viewModel.setVisualizerActive(effectiveStyle.needsAudio)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setVisualizerActive(false)
        }
    }

    NowPlayingContent(
        state = state,
        chapters = chapters,
        visualizer = effectiveStyle,
        amplitude = { viewModel.amplitudeLevel() },
        amplitudeHistory = viewModel::copyAmplitudeHistory,
        onCollapse = onCollapse,
        onOpenEpisode = onOpenEpisode,
        onTogglePlayPause = viewModel::togglePlayPause,
        onRetry = viewModel::retry,
        onSeekBack = viewModel::seekBack,
        onSeekForward = viewModel::seekForward,
        onSeekTo = viewModel::seekTo,
        onSetSpeed = viewModel::setSpeed,
        onSetSleepTimer = viewModel::setSleepTimer,
        modifier = modifier,
    )
}

@Composable
internal fun NowPlayingContent(
    state: PlaybackUiState,
    modifier: Modifier = Modifier,
    chapters: List<Chapter> = emptyList(),
    visualizer: VisualizerStyle = VisualizerStyle.OFF,
    amplitude: () -> Float = { 0f },
    amplitudeHistory: (FloatArray) -> Unit = {},
    onCollapse: () -> Unit,
    onOpenEpisode: (String) -> Unit,
    onTogglePlayPause: () -> Unit,
    onRetry: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetSleepTimer: (Int?, Boolean, Boolean) -> Unit,
) {
    val colors = KoalaTheme.colors
    val context = LocalContext.current
    val track = state.track

    // Deliberately not scrollable. Everything a listener reaches for mid-episode —
    // scrubber, transport, speed, sleep timer — has to be one tap away, and a
    // control that has to be scrolled into view is not. The artwork is the only
    // element that gives ground: it takes whatever height is left over.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPanel)
            .spotlightGlow(colors.accentFill)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = KoalaSpacing.screenH)
            .padding(bottom = KoalaSpacing.gap),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButtonPlain(
                icon = PhosphorIcons.CaretDown,
                contentDescription = stringResource(R.string.player_collapse),
                onClick = onCollapse,
            )
            // Top right, where listeners expect the system output picker.
            CastButton()
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

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // A square that fits both ways: the design's 78% of the width on a
            // roomy screen, capped by whatever height the controls left behind.
            val side = minOf(maxWidth * 0.78f, maxHeight, 420.dp)
            if (side >= MIN_ARTWORK) {
                CoverArt(
                    url = track.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.size(side),
                    sizeHint = 512.dp,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny)) {
            MonoText(
                text = track.podcastTitle,
                color = colors.accentInk,
                style = KoalaTheme.type.monoSmall,
                modifier = Modifier
                    .defaultMinSize(minHeight = KoalaSpacing.minTouchTarget)
                    .clickable { onOpenEpisode(track.episodeId) },
            )
            Text(
                text = track.title,
                style = KoalaTheme.type.sectionTitle,
                color = colors.inkStrong,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        state.playbackError?.let { error ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bgTransport)
                    .padding(KoalaSpacing.gap),
                verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny),
            ) {
                Text(
                    text = stringResource(R.string.player_error),
                    style = KoalaTheme.type.bodySmall,
                    color = colors.inkStrong,
                )
                MonoText(
                    text = stringResource(
                        R.string.player_error_diagnostic,
                        stringResource(
                            if (state.isOfflineSource) {
                                R.string.player_source_offline
                            } else {
                                R.string.player_source_stream
                            },
                        ),
                        error,
                    ),
                    color = colors.ink4,
                    style = KoalaTheme.type.monoSmall,
                )
                OutlineButton(
                    text = stringResource(R.string.player_retry),
                    onClick = onRetry,
                )
            }
        }

        Scrubber(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            chapters = chapters,
            onSeekTo = onSeekTo,
            visualizer = visualizer,
            playing = state.isPlaying,
            amplitude = amplitude,
            amplitudeHistory = amplitudeHistory,
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

        // The secondary controls, as one quiet row of affordances rather than a
        // stack of open panels: speed cycles in place, the sleep timer unfolds
        // its options only when asked for them.
        // Equal weights rather than SpaceBetween: with three items of different
        // widths, "space between" centres nothing — the middle control drifts
        // wherever its neighbours leave room. Three equal columns put the sleep
        // timer on the screen's centre line, under the play button.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                SpeedButton(speed = state.speed, onSetSpeed = onSetSpeed)
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                SleepTimerButton(state = state, onSetSleepTimer = onSetSleepTimer)
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                MonoText(
                    text = stringResource(R.string.player_up_next_count, state.upNextCount),
                    color = colors.ink4,
                    style = KoalaTheme.type.monoSmall,
                )
            }
        }

        if (chapters.isNotEmpty()) {
            ChapterRow(
                chapters = chapters,
                positionMs = state.positionMs,
                onSeekTo = onSeekTo,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    chapters: List<Chapter>,
    onSeekTo: (Long) -> Unit,
    visualizer: VisualizerStyle = VisualizerStyle.OFF,
    playing: Boolean = false,
    amplitude: () -> Float = { 0f },
    amplitudeHistory: (FloatArray) -> Unit = {},
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

    // Sampled on the frame clock rather than pushed from the audio thread: the
    // renderer decides when it needs a value, and a paused player needs none.
    var level by remember { mutableFloatStateOf(0f) }
    val history = remember { FloatArray(WAVEFORM_BARS) }
    var historyRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(visualizer, playing) {
        if (!visualizer.needsAudio || !playing) {
            level = 0f
            return@LaunchedEffect
        }
        while (true) {
            delay(VISUALIZER_SAMPLE_MS)
            level = amplitude()
            if (visualizer.needsHistory) {
                amplitudeHistory(history)
                // FloatArray is mutated in place, so Compose needs a separate
                // signal that its contents changed.
                historyRevision++
            }
        }
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
                // Material 3 draws a 4x44dp bar for the handle, which on a 4dp track
                // reads as a scroll bar rather than a playhead. A dot barely wider than
                // the track is what the rest of the app's progress indicators imply.
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(THUMB_DIAMETER)
                            .background(
                                color = if (colors.isDark) colors.ink else colors.inkStrong,
                                shape = CircleShape,
                            ),
                    )
                },
                // A fixed-height slot gives every track style the exact same
                // centre line. The thumb and chapter overlay use that line too.
                track = { sliderState ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SCRUBBER_TRACK_SLOT_HEIGHT),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (visualizer.needsAudio) {
                            @Suppress("UNUSED_EXPRESSION")
                            historyRevision
                            VisualizerTrack(
                                style = visualizer,
                                fraction = fraction,
                                level = level,
                                history = history,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            SliderDefaults.Track(
                                sliderState = sliderState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                colors = SliderDefaults.colors(
                                    activeTrackColor = if (colors.isDark) colors.accentFill else colors.accentInk,
                                    inactiveTrackColor = colors.track,
                                ),
                                drawStopIndicator = null,
                                thumbTrackGapSize = 0.dp,
                            )
                        }
                    }
                },
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

/**
 * Speed as a named menu rather than a chip that cycles. "1×" tapped blind and
 * landing on 1.25× teaches nothing; a titled list of speeds says what the control
 * is and what it can be set to.
 */
@Composable
private fun SpeedButton(speed: Float, onSetSpeed: (Float) -> Unit) {
    // Stops at 2×: past that speech stops being speech. 1.15× is the step most
    // listeners actually settle on, so it earns a place next to 1×.
    val steps = listOf(0.8f, 1f, 1.15f, 1.25f, 1.5f, 1.75f, 2f)
    MenuButton(
        icon = PhosphorIcons.Waveform,
        contentDescription = stringResource(R.string.player_speed),
        actions = steps.map { step ->
            MenuAction(
                label = Format.speed(step),
                onClick = { onSetSpeed(step) },
                selected = kotlin.math.abs(step - speed) < 0.01f,
            )
        },
        label = Format.speed(speed),
        active = kotlin.math.abs(speed - 1f) >= 0.01f,
        iconSize = 16.dp,
    )
}

/**
 * A moon that opens the six choices, the way every other podcast player does it.
 * Armed, it wears its setting as a label so the state is readable without
 * opening anything; off, it is a single unobtrusive icon.
 */
@Composable
private fun SleepTimerButton(
    state: PlaybackUiState,
    onSetSleepTimer: (Int?, Boolean, Boolean) -> Unit,
) {
    val minuteOptions = listOf(5, 15, 30, 60)
    val sleepMinutes = state.sleepMinutes
    val armed = sleepMinutes != null || state.sleepAtChapterEnd || state.sleepAtEpisodeEnd

    val actions = buildList {
        add(
            MenuAction(
                label = stringResource(R.string.player_sleep_off),
                onClick = { onSetSleepTimer(null, false, false) },
                selected = !armed,
            ),
        )
        minuteOptions.forEach { minutes ->
            add(
                MenuAction(
                    label = stringResource(R.string.player_sleep_minutes, minutes),
                    onClick = { onSetSleepTimer(minutes, false, false) },
                    selected = sleepMinutes == minutes,
                ),
            )
        }
        add(
            MenuAction(
                label = stringResource(R.string.player_sleep_chapter_end),
                onClick = { onSetSleepTimer(null, false, true) },
                selected = state.sleepAtChapterEnd,
            ),
        )
        add(
            MenuAction(
                label = stringResource(R.string.player_sleep_episode_end),
                onClick = { onSetSleepTimer(null, true, false) },
                selected = state.sleepAtEpisodeEnd,
            ),
        )
    }

    MenuButton(
        icon = PhosphorIcons.Moon,
        contentDescription = stringResource(R.string.player_sleep_timer),
        actions = actions,
        label = when {
            sleepMinutes != null -> stringResource(R.string.player_sleep_minutes, sleepMinutes)
            state.sleepAtChapterEnd -> stringResource(R.string.player_sleep_chapter_end)
            state.sleepAtEpisodeEnd -> stringResource(R.string.player_sleep_episode_end)
            else -> null
        },
        active = armed,
        iconSize = 18.dp,
    )
}

/**
 * True when the system has animations turned off. A visualiser is motion for its
 * own sake, which is the category that setting exists for, so it steps aside
 * entirely rather than animating more slowly.
 */
private fun animationsDisabled(context: android.content.Context): Boolean =
    android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

/** How many bars the waveform draws. Wider than this and each bar is a hairline. */
private const val WAVEFORM_BARS = 48
private const val VISUALIZER_SAMPLE_MS = 33L

/** The playhead. Small enough to sit on a 4dp track without swallowing it. */
private val THUMB_DIAMETER = 12.dp
private val SCRUBBER_TRACK_SLOT_HEIGHT = 30.dp

/**
 * Below this the artwork is a smudge rather than a picture, so a very short
 * viewport (landscape, split screen) drops it and keeps the controls whole.
 */
private val MIN_ARTWORK = 72.dp

/** Material's Slider insets its track by half a thumb; the markers must match. */
private val SLIDER_THUMB_INSET = THUMB_DIAMETER / 2
private const val MARKER_WIDTH_PX = 2f
private const val MARKER_HALF_HEIGHT_PX = 5f
