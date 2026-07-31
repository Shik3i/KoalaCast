package net.koalastuff.koalacast.feature.podcast

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.Podcast
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.ArtworkAccent
import net.koalastuff.koalacast.core.ui.component.DataErrorState
import net.koalastuff.koalacast.core.ui.component.EpisodeProgressButton
import net.koalastuff.koalacast.core.ui.component.AccentButton
import net.koalastuff.koalacast.core.ui.component.ConfirmDialog
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.OutlineButton
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.KoalaChip
import net.koalastuff.koalacast.core.ui.component.KoalaTextField
import net.koalastuff.koalacast.core.ui.component.RowSeparator
import net.koalastuff.koalacast.core.ui.component.SkeletonRows
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.theme.spotlightGlow
import net.koalastuff.koalacast.core.ui.util.Format
import net.koalastuff.koalacast.core.ui.R as CoreR

@Composable
fun PodcastScreen(
    onBack: () -> Unit,
    onOpenEpisode: (episodeId: String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: PodcastViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && !state.settings.notifyNewEpisodes) viewModel.toggleNotifications()
    }
    val toggleNotifications = {
        if (state.settings.notifyNewEpisodes) {
            viewModel.toggleNotifications()
        } else if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.toggleNotifications()
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Paging without a Paging library: the list asks for more once the tail is in
    // sight. Episode lists are bounded (a few hundred rows), so this is enough.
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 5
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore }.collect { if (it) viewModel.loadMore() }
    }

    ArtworkAccent(state.podcast?.artworkUrl) {
        PodcastContent(
            state = state,
        listState = listState,
        onBack = onBack,
        onOpenEpisode = onOpenEpisode,
        onRetry = viewModel::retry,
        onPlay = viewModel::play,
        onToggleSubscribe = viewModel::toggleSubscribe,
        onToggleFavorite = viewModel::toggleFavorite,
        onToggleQueue = viewModel::toggleQueue,
        onTogglePlayed = viewModel::togglePlayed,
        onSetSpeed = viewModel::setSpeed,
        onSetSkipIntro = viewModel::setSkipIntro,
        onSetSkipOutro = viewModel::setSkipOutro,
        onSetVolumeBoost = viewModel::setVolumeBoost,
        onSetSkipSilence = viewModel::setSkipSilence,
        onToggleAutoQueue = viewModel::toggleAutoQueue,
        onToggleAutoDownload = viewModel::toggleAutoDownload,
        onToggleNotifications = toggleNotifications,
        onMarkAllPlayed = { viewModel.markAllPlayed(true) },
        onMarkAllUnplayed = { viewModel.markAllPlayed(false) },
        modifier = modifier,
            contentPadding = contentPadding,
        )
    }
}

@Composable
internal fun PodcastContent(
    state: PodcastUiState,
    listState: LazyListState,
    onBack: () -> Unit,
    onOpenEpisode: (String) -> Unit,
    onRetry: () -> Unit,
    onPlay: (Episode) -> Unit,
    onToggleSubscribe: () -> Unit,
    onToggleFavorite: (Episode) -> Unit,
    onToggleQueue: (Episode) -> Unit,
    onTogglePlayed: (Episode) -> Unit,
    onSetSpeed: (Float?) -> Unit,
    onSetSkipIntro: (Int) -> Unit,
    onSetSkipOutro: (Int) -> Unit,
    onSetVolumeBoost: (Boolean?) -> Unit,
    onSetSkipSilence: (Boolean?) -> Unit,
    onToggleAutoQueue: () -> Unit,
    onToggleAutoDownload: () -> Unit,
    onToggleNotifications: () -> Unit,
    onMarkAllPlayed: () -> Unit,
    onMarkAllUnplayed: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = KoalaTheme.colors
    var markAllTarget by rememberSaveable { mutableStateOf<Boolean?>(null) }

    markAllTarget?.let { played ->
        ConfirmDialog(
            title = stringResource(
                if (played) R.string.podcast_mark_all_played_title
                else R.string.podcast_mark_all_unplayed_title,
            ),
            body = stringResource(
                if (played) R.string.podcast_mark_all_played_body
                else R.string.podcast_mark_all_unplayed_body,
                state.episodes.size,
            ),
            confirmLabel = stringResource(
                if (played) R.string.podcast_mark_all_played
                else R.string.podcast_mark_all_unplayed,
            ),
            onConfirm = {
                markAllTarget = null
                if (played) onMarkAllPlayed() else onMarkAllUnplayed()
            },
            onDismiss = { markAllTarget = null },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPanel),
        state = listState,
        contentPadding = contentPadding,
    ) {
        item(key = "header") {
            when {
                state.error != null && state.podcast == null -> Column {
                    BackRow(onBack = onBack)
                    DataErrorState(
                        error = state.error,
                        serverUrl = state.serverUrl,
                        onRetry = onRetry,
                    )
                }

                state.podcast == null -> Column {
                    BackRow(onBack = onBack)
                    SkeletonRows(count = 5, modifier = Modifier.padding(KoalaSpacing.screenH))
                }

                else -> PodcastHeader(
                    podcast = state.podcast,
                    subscribed = state.subscribed,
                    settings = state.settings,
                    onBack = onBack,
                    onToggleSubscribe = onToggleSubscribe,
                    onSetSpeed = onSetSpeed,
                    onSetSkipIntro = onSetSkipIntro,
                    onSetSkipOutro = onSetSkipOutro,
                    onSetVolumeBoost = onSetVolumeBoost,
                    onSetSkipSilence = onSetSkipSilence,
                    onToggleAutoQueue = onToggleAutoQueue,
                    onToggleAutoDownload = onToggleAutoDownload,
                    onToggleNotifications = onToggleNotifications,
                )
            }
        }

        if (state.podcast != null) {
            item(key = "episodes-header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = KoalaSpacing.screenH,
                            vertical = KoalaSpacing.gap,
                        ),
                    verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = stringResource(R.string.podcast_episodes),
                            style = KoalaTheme.type.sectionTitle,
                            color = colors.inkStrong,
                        )
                        val episodeCount =
                            state.podcast.episodeCount.takeIf { it > 0 } ?: state.episodes.size
                        MonoText(
                            text = pluralStringResource(
                                R.plurals.podcast_episode_count,
                                episodeCount,
                                episodeCount,
                            ),
                            color = colors.ink4,
                            style = KoalaTheme.type.monoSmall,
                        )
                    }
                    // Both rewrite the played state of every episode in the show;
                    // neither is worth doing by accident on the way past.
                    Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                        OutlineButton(
                            text = stringResource(R.string.podcast_mark_all_played),
                            onClick = { markAllTarget = true },
                        )
                        OutlineButton(
                            text = stringResource(R.string.podcast_mark_all_unplayed),
                            onClick = { markAllTarget = false },
                        )
                    }
                }
            }

            items(items = state.episodes, key = { it.id }) { episode ->
                EpisodeRow(
                    episode = episode,
                    isFavorite = episode.id in state.favoriteIds,
                    isQueued = episode.id in state.queuedIds,
                    isPlayed = episode.id in state.completedIds,
                    // A finished episode reads as a full ring even if its stored
                    // position was never written all the way to the end.
                    progressPercent = if (episode.id in state.completedIds) {
                        100
                    } else {
                        state.progressByEpisode[episode.id] ?: 0
                    },
                    isCurrent = episode.id == state.currentEpisodeId,
                    onClick = { onOpenEpisode(episode.id) },
                    onPlay = { onPlay(episode) },
                    onToggleFavorite = { onToggleFavorite(episode) },
                    onToggleQueue = { onToggleQueue(episode) },
                    onTogglePlayed = { onTogglePlayed(episode) },
                )
                RowSeparator(modifier = Modifier.padding(horizontal = KoalaSpacing.screenH))
            }

            if (state.loadingMore) {
                item(key = "more") {
                    SkeletonRows(count = 2, modifier = Modifier.padding(KoalaSpacing.screenH))
                }
            }

            if (state.paginationError) {
                item(key = "pagination_error") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onRetry)
                            .padding(KoalaSpacing.screenH),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonoText(
                            text = stringResource(R.string.podcast_retry_pagination),
                            color = KoalaTheme.colors.accentInk,
                            style = KoalaTheme.type.monoSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(modifier = Modifier.padding(horizontal = KoalaSpacing.gapSmall, vertical = KoalaSpacing.gapTiny)) {
        IconButtonSquare(
            icon = PhosphorIcons.CaretLeft,
            contentDescription = stringResource(CoreR.string.action_back),
            onClick = onBack,
            bordered = false,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PodcastHeader(
    podcast: Podcast,
    subscribed: Boolean,
    settings: net.koalastuff.koalacast.core.model.PodcastSettings,
    onBack: () -> Unit,
    onToggleSubscribe: () -> Unit,
    onSetSpeed: (Float?) -> Unit,
    onSetSkipIntro: (Int) -> Unit,
    onSetSkipOutro: (Int) -> Unit,
    onSetVolumeBoost: (Boolean?) -> Unit,
    onSetSkipSilence: (Boolean?) -> Unit,
    onToggleAutoQueue: () -> Unit,
    onToggleAutoDownload: () -> Unit,
    onToggleNotifications: () -> Unit,
) {
    val colors = KoalaTheme.colors
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Unsubscribing throws away this show's per-podcast settings and inbox state,
    // and the control sits where "subscribe" was a tap earlier — so it asks.
    var confirmUnsubscribe by rememberSaveable { mutableStateOf(false) }

    if (confirmUnsubscribe) {
        ConfirmDialog(
            title = stringResource(R.string.podcast_unsubscribe_title),
            body = stringResource(R.string.podcast_unsubscribe_body, podcast.title),
            confirmLabel = stringResource(R.string.podcast_unsubscribe_confirm),
            onConfirm = {
                confirmUnsubscribe = false
                onToggleSubscribe()
            },
            onDismiss = { confirmUnsubscribe = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .spotlightGlow(colors.accentFill),
    ) {
        BackRow(onBack = onBack)

        Column(
            modifier = Modifier.padding(
                start = KoalaSpacing.screenH,
                end = KoalaSpacing.screenH,
                bottom = KoalaSpacing.gap,
            ),
            verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(
                    url = podcast.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.size(88.dp),
                    sizeHint = 160.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
                ) {
                    MonoText(
                        text = listOf(
                            podcast.author,
                            podcast.language.uppercase(),
                            if (podcast.explicit) stringResource(R.string.podcast_explicit) else "",
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        color = colors.ink4,
                        style = KoalaTheme.type.monoSmall,
                    )
                    Text(
                        text = podcast.title,
                        style = KoalaTheme.type.displaySmall,
                        color = colors.inkStrong,
                    )
                }
            }
            val description = Format.plainText(podcast.description)
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = KoalaTheme.type.bodySmall,
                    color = colors.ink3,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
            ) {
                // Subscribing is purely local: no account, no request, works offline.
                if (subscribed) {
                    OutlineButton(
                        text = stringResource(R.string.podcast_subscribed),
                        onClick = { confirmUnsubscribe = true },
                        modifier = Modifier.weight(1f),
                        leadingIcon = PhosphorIcons.Check,
                    )
                } else {
                    AccentButton(
                        text = stringResource(R.string.podcast_subscribe),
                        onClick = onToggleSubscribe,
                        modifier = Modifier.weight(1f),
                        leadingIcon = PhosphorIcons.Plus,
                    )
                }
                OutlineButton(
                    text = stringResource(
                        if (showSettings) {
                            R.string.podcast_options_hide
                        } else {
                            R.string.podcast_options_show
                        },
                    ),
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier.weight(1f),
                    leadingIcon = if (showSettings) {
                        PhosphorIcons.CaretUp
                    } else {
                        PhosphorIcons.SlidersHorizontal
                    },
                )
            }

            if (showSettings) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.bgSunken)
                        .padding(KoalaSpacing.gap),
                    verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                ) {
                    MonoText(
                        text = stringResource(R.string.podcast_show_speed),
                        color = colors.ink4,
                        style = KoalaTheme.type.monoSmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                        listOf(null, 1f, 1.15f, 1.25f, 1.5f, 2f).forEach { speed ->
                            KoalaChip(
                                label = speed?.let { "${it}×" }
                                    ?: stringResource(R.string.podcast_speed_default),
                                selected = settings.speed == speed,
                                onClick = { onSetSpeed(speed) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                    ) {
                        SkipField(
                            label = stringResource(R.string.podcast_skip_intro),
                            seconds = settings.skipIntroSeconds,
                            onChange = onSetSkipIntro,
                            modifier = Modifier.weight(1f),
                        )
                        SkipField(
                            label = stringResource(R.string.podcast_skip_outro),
                            seconds = settings.skipOutroSeconds,
                            onChange = onSetSkipOutro,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    MonoText(
                        text = stringResource(R.string.podcast_audio_processing),
                        color = colors.ink4,
                        style = KoalaTheme.type.monoSmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                        listOf<Boolean?>(null, true, false).forEach { enabled ->
                            KoalaChip(
                                label = when (enabled) {
                                    null -> stringResource(R.string.podcast_audio_default)
                                    true -> stringResource(R.string.podcast_volume_boost_on)
                                    false -> stringResource(R.string.podcast_volume_boost_off)
                                },
                                selected = settings.volumeBoost == enabled,
                                onClick = { onSetVolumeBoost(enabled) },
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                        listOf<Boolean?>(null, true, false).forEach { enabled ->
                            KoalaChip(
                                label = when (enabled) {
                                    null -> stringResource(R.string.podcast_silence_default)
                                    true -> stringResource(R.string.podcast_silence_on)
                                    false -> stringResource(R.string.podcast_silence_off)
                                },
                                selected = settings.skipSilence == enabled,
                                onClick = { onSetSkipSilence(enabled) },
                            )
                        }
                    }
                    PodcastSettingSwitch(
                        title = stringResource(R.string.podcast_auto_queue),
                        checked = settings.autoQueueNew,
                        onToggle = onToggleAutoQueue,
                    )
                    PodcastSettingSwitch(
                        title = stringResource(R.string.podcast_auto_download),
                        note = stringResource(
                            if (subscribed) R.string.podcast_auto_download_note
                            else R.string.podcast_auto_download_needs_sub,
                        ),
                        checked = settings.autoDownload && subscribed,
                        enabled = subscribed,
                        onToggle = onToggleAutoDownload,
                    )
                    PodcastSettingSwitch(
                        title = stringResource(R.string.podcast_notifications),
                        note = stringResource(
                            if (subscribed) R.string.podcast_notifications_note
                            else R.string.podcast_notifications_needs_sub,
                        ),
                        checked = settings.notifyNewEpisodes && subscribed,
                        enabled = subscribed,
                        onToggle = onToggleNotifications,
                    )
                }
            }
        }
    }
}

@Composable
private fun PodcastSettingSwitch(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    note: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = KoalaTheme.type.bodySmall,
                color = KoalaTheme.colors.ink2,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = KoalaTheme.type.bodySmall,
                    color = KoalaTheme.colors.ink4,
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { onToggle() },
        )
    }
}

@Composable
private fun SkipField(
    label: String,
    seconds: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny),
    ) {
        MonoText(
            text = label,
            color = KoalaTheme.colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )
        KoalaTextField(
            value = seconds.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { value ->
                val digits = value.filter(Char::isDigit).take(3)
                onChange(digits.toIntOrNull()?.coerceAtMost(600) ?: 0)
            },
            placeholder = stringResource(R.string.podcast_skip_seconds),
            leadingIcon = null,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    isFavorite: Boolean,
    isQueued: Boolean,
    isPlayed: Boolean,
    progressPercent: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleQueue: () -> Unit,
    onTogglePlayed: () -> Unit,
) {
    val colors = KoalaTheme.colors
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny),
            ) {
                MonoText(
                    text = buildString {
                        append(Format.publishedAt(context, episode.pubDateMs, episode.hasPubDate, now))
                        if (episode.episodeNumber > 0) {
                            append(" · ")
                            append(stringResource(R.string.podcast_episode_number, episode.episodeNumber))
                        }
                        if (isPlayed) {
                            append(" · ")
                            append(stringResource(R.string.podcast_played))
                        }
                    },
                    color = if (isPlayed) colors.accentInk else colors.ink4,
                    style = KoalaTheme.type.monoSmall,
                )
                Text(
                    text = episode.title,
                    style = KoalaTheme.type.listTitle,
                    // A finished episode steps back rather than disappearing.
                    color = if (isPlayed) colors.ink4 else colors.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val blurb = Format.plainText(episode.description)
                if (blurb.isNotBlank()) {
                    Text(
                        text = blurb,
                        style = KoalaTheme.type.bodySmall,
                        color = colors.ink3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(modifier = Modifier.padding(top = 2.dp)) {
                MonoText(
                    text = Format.duration(context, episode.durationMs),
                    color = colors.ink3,
                    style = KoalaTheme.type.monoStrong,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EpisodeProgressButton(
                progressPercent = progressPercent,
                current = isCurrent,
                contentDescription = stringResource(R.string.podcast_action_play),
                onClick = onPlay,
                size = 38.dp,
            )
            IconButtonSquare(
                icon = if (isQueued) PhosphorIcons.Check else PhosphorIcons.ListPlus,
                contentDescription = stringResource(
                    if (isQueued) R.string.podcast_action_dequeue else R.string.podcast_action_queue,
                ),
                onClick = onToggleQueue,
                tint = if (isQueued) colors.accentInk else colors.ink3,
                boxSize = 30.dp,
                iconSize = 16.dp,
            )
            IconButtonSquare(
                icon = if (isFavorite) PhosphorIcons.HeartFill else PhosphorIcons.Heart,
                contentDescription = stringResource(
                    if (isFavorite) R.string.podcast_action_unsave else R.string.podcast_action_save,
                ),
                onClick = onToggleFavorite,
                tint = if (isFavorite) colors.accentInk else colors.ink3,
                boxSize = 30.dp,
                iconSize = 16.dp,
            )
            IconButtonSquare(
                icon = if (isPlayed) PhosphorIcons.CheckCircleFill else PhosphorIcons.CheckCircle,
                contentDescription = stringResource(
                    if (isPlayed) R.string.podcast_action_unplayed else R.string.podcast_action_played,
                ),
                onClick = onTogglePlayed,
                tint = if (isPlayed) colors.accentInk else colors.ink3,
                boxSize = 30.dp,
                iconSize = 16.dp,
            )
        }
    }
}
