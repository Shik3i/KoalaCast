package net.koalastuff.koalacast.feature.podcast

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.Podcast
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.DataErrorState
import net.koalastuff.koalacast.core.ui.component.AccentButton
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
        onToggleAutoQueue = viewModel::toggleAutoQueue,
        onMarkAllPlayed = { viewModel.markAllPlayed(true) },
        onMarkAllUnplayed = { viewModel.markAllPlayed(false) },
        modifier = modifier,
        contentPadding = contentPadding,
    )
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
    onToggleAutoQueue: () -> Unit,
    onMarkAllPlayed: () -> Unit,
    onMarkAllUnplayed: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = KoalaTheme.colors

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
                    onToggleAutoQueue = onToggleAutoQueue,
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
                    Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                        OutlineButton(
                            text = stringResource(R.string.podcast_mark_all_played),
                            onClick = onMarkAllPlayed,
                        )
                        OutlineButton(
                            text = stringResource(R.string.podcast_mark_all_unplayed),
                            onClick = onMarkAllUnplayed,
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
    onToggleAutoQueue: () -> Unit,
) {
    val colors = KoalaTheme.colors
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
                bottom = KoalaSpacing.gapSection,
            ),
            verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
        ) {
            CoverArt(
                url = podcast.artworkUrl,
                contentDescription = null,
                modifier = Modifier.size(140.dp),
                sizeHint = 140.dp,
            )
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
            val description = Format.plainText(podcast.description)
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = KoalaTheme.type.bodySmall,
                    color = colors.ink3,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Subscribing is purely local: no account, no request, works offline.
            if (subscribed) {
                OutlineButton(
                    text = stringResource(R.string.podcast_subscribed),
                    onClick = onToggleSubscribe,
                    leadingIcon = PhosphorIcons.Check,
                )
            } else {
                AccentButton(
                    text = stringResource(R.string.podcast_subscribe),
                    onClick = onToggleSubscribe,
                    leadingIcon = PhosphorIcons.Plus,
                )
            }

            MonoText(
                text = stringResource(R.string.podcast_show_speed),
                color = colors.ink4,
                style = KoalaTheme.type.monoSmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                listOf(null, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.podcast_auto_queue),
                    style = KoalaTheme.type.bodySmall,
                    color = colors.ink2,
                )
                Switch(
                    checked = settings.autoQueueNew,
                    onCheckedChange = { onToggleAutoQueue() },
                )
            }
        }
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
            IconButtonSquare(
                icon = PhosphorIcons.PlayFill,
                contentDescription = stringResource(R.string.podcast_action_play),
                onClick = onPlay,
                tint = colors.accentInk,
                boxSize = 30.dp,
                iconSize = 16.dp,
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
