package net.koalastuff.koalacast.feature.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.Subscription
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.EmptyState
import net.koalastuff.koalacast.core.ui.component.EpisodeProgressButton
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.OutlineButton
import net.koalastuff.koalacast.core.ui.component.RowSeparator
import net.koalastuff.koalacast.core.ui.component.SegmentedControl
import net.koalastuff.koalacast.core.ui.component.SkeletonRows
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.util.Format
import java.text.DateFormat
import java.util.Date

@Composable
fun InboxScreen(
    onOpenEpisode: (String) -> Unit,
    onOpenDiscover: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InboxContent(
        state = state,
        onRefresh = viewModel::refresh,
        onToggleSettings = viewModel::toggleSettings,
        onSetUnplayedOnly = viewModel::setUnplayedOnly,
        onSetMode = viewModel::setInboxMode,
        onOpenEpisode = onOpenEpisode,
        onOpenDiscover = onOpenDiscover,
        onPlay = viewModel::play,
        onQueue = viewModel::addToQueue,
        onTogglePlayed = viewModel::togglePlayed,
        onMarkAllPlayed = viewModel::markAllPlayed,
        onMarkOlder = viewModel::markThisAndOlder,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
internal fun InboxContent(
    state: InboxUiState,
    onRefresh: () -> Unit,
    onToggleSettings: () -> Unit,
    onSetUnplayedOnly: (Boolean) -> Unit,
    onSetMode: (String, InboxMode) -> Unit,
    onOpenEpisode: (String) -> Unit,
    onOpenDiscover: () -> Unit,
    onPlay: (InboxEpisode) -> Unit,
    onQueue: (InboxEpisode) -> Unit,
    onTogglePlayed: (InboxEpisode) -> Unit,
    onMarkAllPlayed: () -> Unit,
    onMarkOlder: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = KoalaTheme.colors
    val feed = state.feed

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.bgPanel),
        contentPadding = contentPadding,
    ) {
        item(key = "header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bgRail)
                    .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
                verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
            ) {
                Text(
                    text = stringResource(R.string.inbox_title),
                    style = KoalaTheme.type.screenTitle,
                    color = colors.inkStrong,
                )
                Text(
                    text = stringResource(R.string.inbox_subtitle),
                    style = KoalaTheme.type.bodySmall,
                    color = colors.ink3,
                )
                if (state.subscriptions.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlineButton(
                            text = stringResource(R.string.inbox_mark_all),
                            onClick = onMarkAllPlayed,
                            enabled = feed.isNotEmpty(),
                        )
                        OutlineButton(
                            text = stringResource(R.string.inbox_settings),
                            onClick = onToggleSettings,
                            leadingIcon = PhosphorIcons.SlidersHorizontal,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetUnplayedOnly(!state.unplayedOnly) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.inbox_unplayed_only),
                            style = KoalaTheme.type.label,
                            color = colors.ink2,
                        )
                        Switch(
                            checked = state.unplayedOnly,
                            onCheckedChange = onSetUnplayedOnly,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.accentOn,
                                checkedTrackColor = colors.accentInk,
                            ),
                        )
                    }
                }
            }
        }

        if (state.showSettings) {
            item(key = "settings") {
                InboxSettings(state.subscriptions, onSetMode)
            }
        }

        when {
            state.loading -> item(key = "loading") {
                SkeletonRows(
                    count = 6,
                    modifier = Modifier.padding(KoalaSpacing.screenH),
                )
            }
            state.subscriptions.isEmpty() -> item(key = "no-subscriptions") {
                EmptyState(
                    title = stringResource(R.string.inbox_empty_subscriptions_title),
                    body = stringResource(R.string.inbox_empty_subscriptions_body),
                    icon = PhosphorIcons.Tray,
                    actionLabel = stringResource(R.string.inbox_discover),
                    onAction = onOpenDiscover,
                )
            }
            feed.isEmpty() -> item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.inbox_empty_title),
                    body = stringResource(
                        if (state.unplayedOnly) R.string.inbox_empty_caught_up
                        else R.string.inbox_empty_recent,
                    ),
                    icon = PhosphorIcons.CheckCircle,
                    actionLabel = if (state.failedFeeds > 0) stringResource(R.string.inbox_retry) else null,
                    onAction = if (state.failedFeeds > 0) onRefresh else null,
                )
            }
            else -> {
                val grouped = feed.groupBy {
                    if (it.episode.hasPubDate) {
                        DateFormat.getDateInstance(DateFormat.FULL).format(Date(it.episode.pubDateMs))
                    } else {
                        ""
                    }
                }
                grouped.forEach { (date, episodes) ->
                    item(key = "day-$date") {
                        DayHeader(date, episodes)
                    }
                    items(episodes, key = { it.episode.id }) { item ->
                        InboxEpisodeRow(
                            item = item,
                            played = item.episode.id in state.completedIds,
                            progressPercent = state.progressByEpisode[item.episode.id] ?: 0,
                            isCurrent = item.episode.id == state.currentEpisodeId,
                            onOpen = { onOpenEpisode(item.episode.id) },
                            onPlay = { onPlay(item) },
                            onQueue = { onQueue(item) },
                            onTogglePlayed = { onTogglePlayed(item) },
                            onMarkOlder = { played ->
                                onMarkOlder(item.episode.id, played)
                            },
                        )
                        RowSeparator(modifier = Modifier.padding(horizontal = KoalaSpacing.screenH))
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxSettings(
    subscriptions: List<Subscription>,
    onSetMode: (String, InboxMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KoalaTheme.colors.bgSunken)
            .padding(KoalaSpacing.screenH),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
    ) {
        Text(
            text = stringResource(R.string.inbox_settings_hint),
            style = KoalaTheme.type.bodySmall,
            color = KoalaTheme.colors.ink3,
        )
        subscriptions.forEach { subscription ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(
                    url = subscription.artworkUrl,
                    contentDescription = subscription.title,
                    modifier = Modifier.size(40.dp),
                    sizeHint = 40.dp,
                )
                Text(
                    text = subscription.title,
                    style = KoalaTheme.type.label,
                    color = KoalaTheme.colors.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.inbox_mode_all),
                        stringResource(R.string.inbox_mode_latest),
                    ),
                    selectedIndex = if (subscription.inboxMode == InboxMode.LATEST) 1 else 0,
                    onSelect = {
                        onSetMode(
                            subscription.podcastId,
                            if (it == 1) InboxMode.LATEST else InboxMode.ALL,
                        )
                    },
                    modifier = Modifier.weight(1.2f),
                )
            }
        }
    }
}

@Composable
private fun DayHeader(date: String, episodes: List<InboxEpisode>) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KoalaTheme.colors.bgRail)
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gapSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = date.ifBlank { stringResource(R.string.inbox_undated) },
            style = KoalaTheme.type.label,
            color = KoalaTheme.colors.ink2,
            modifier = Modifier.weight(1f),
        )
        MonoText(
            text = pluralStringResource(
                R.plurals.inbox_episode_count,
                episodes.size,
                episodes.size,
            ) + " · " + Format.duration(context, episodes.sumOf { it.episode.durationMs }),
            color = KoalaTheme.colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )
    }
}

@Composable
private fun InboxEpisodeRow(
    item: InboxEpisode,
    played: Boolean,
    progressPercent: Int,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    onTogglePlayed: () -> Unit,
    onMarkOlder: (Boolean) -> Unit,
) {
    val colors = KoalaTheme.colors
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = item.track.artworkUrl,
            contentDescription = item.track.podcastTitle,
            modifier = Modifier.size(56.dp),
            sizeHint = 56.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny),
        ) {
            Text(
                text = item.episode.title,
                style = KoalaTheme.type.listTitle,
                color = if (played) colors.ink4 else colors.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MonoText(
                text = "${item.subscription.title} · ${Format.duration(context, item.episode.durationMs)}",
                color = colors.ink4,
                style = KoalaTheme.type.monoSmall,
                maxLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                EpisodeProgressButton(
                    progressPercent = if (played) 100 else progressPercent,
                    current = isCurrent,
                    contentDescription = stringResource(R.string.inbox_play),
                    onClick = onPlay,
                    size = 34.dp,
                )
                IconButtonSquare(
                    icon = PhosphorIcons.ListPlus,
                    contentDescription = stringResource(R.string.inbox_queue),
                    onClick = onQueue,
                    boxSize = 30.dp,
                    iconSize = 16.dp,
                )
                IconButtonSquare(
                    icon = if (played) PhosphorIcons.CheckCircleFill else PhosphorIcons.CheckCircle,
                    contentDescription = stringResource(
                        if (played) R.string.inbox_mark_unplayed else R.string.inbox_mark_played,
                    ),
                    onClick = onTogglePlayed,
                    tint = if (played) colors.accentInk else colors.ink3,
                    boxSize = 30.dp,
                    iconSize = 16.dp,
                )
                Column {
                    IconButtonSquare(
                        icon = PhosphorIcons.CaretDown,
                        contentDescription = stringResource(R.string.inbox_more),
                        onClick = { menuOpen = true },
                        boxSize = 30.dp,
                        iconSize = 16.dp,
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.inbox_older_played)) },
                            onClick = {
                                menuOpen = false
                                onMarkOlder(true)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.inbox_older_unplayed)) },
                            onClick = {
                                menuOpen = false
                                onMarkOlder(false)
                            },
                        )
                    }
                }
            }
        }
    }
}
