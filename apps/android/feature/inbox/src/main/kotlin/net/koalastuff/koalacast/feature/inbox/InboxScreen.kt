package net.koalastuff.koalacast.feature.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.DownloadState
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.Subscription
import net.koalastuff.koalacast.core.ui.component.ConfirmDialog
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.DownloadButton
import net.koalastuff.koalacast.core.ui.component.EmptyState
import net.koalastuff.koalacast.core.ui.component.EpisodeProgressButton
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.KoalaChip
import net.koalastuff.koalacast.core.ui.component.MenuAction
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.OutlineButton
import net.koalastuff.koalacast.core.ui.component.OverflowMenu
import net.koalastuff.koalacast.core.ui.component.RowSeparator
import net.koalastuff.koalacast.core.ui.component.SegmentedControl
import net.koalastuff.koalacast.core.ui.component.SkeletonRows
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaIconButton
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
        onSetDownloadedOnly = viewModel::setDownloadedOnly,
        onSetPodcastFilter = viewModel::setPodcastFilter,
        onSetDateRange = viewModel::setDateRange,
        onSetMood = viewModel::setMood,
        onSetHideSpecials = viewModel::setHideSpecials,
        onSetSessionMinutes = viewModel::setSessionMinutes,
        onQueueSession = viewModel::queueSession,
        onSetMode = viewModel::setInboxMode,
        onTogglePriority = viewModel::togglePriority,
        onOpenEpisode = onOpenEpisode,
        onOpenDiscover = onOpenDiscover,
        onPlay = viewModel::play,
        onQueue = viewModel::addToQueue,
        onDownload = viewModel::toggleDownload,
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
    onSetDownloadedOnly: (Boolean) -> Unit,
    onSetPodcastFilter: (String?) -> Unit,
    onSetDateRange: (InboxDateRange) -> Unit,
    onSetMood: (InboxMood) -> Unit,
    onSetHideSpecials: (Boolean) -> Unit,
    onSetSessionMinutes: (Int?) -> Unit,
    onQueueSession: () -> Unit,
    onSetMode: (String, InboxMode) -> Unit,
    onTogglePriority: (String) -> Unit,
    onOpenEpisode: (String) -> Unit,
    onOpenDiscover: () -> Unit,
    onPlay: (InboxEpisode) -> Unit,
    onQueue: (InboxEpisode) -> Unit,
    onDownload: (InboxEpisode) -> Unit,
    onTogglePlayed: (InboxEpisode) -> Unit,
    onMarkAllPlayed: () -> Unit,
    onMarkOlder: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = KoalaTheme.colors
    val feed = state.feed
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var confirmMarkAll by rememberSaveable { mutableStateOf(false) }

    if (confirmMarkAll) {
        ConfirmDialog(
            title = stringResource(R.string.inbox_mark_all_title),
            body = stringResource(R.string.inbox_mark_all_body, feed.size),
            confirmLabel = stringResource(R.string.inbox_mark_all_confirm),
            onConfirm = {
                confirmMarkAll = false
                onMarkAllPlayed()
            },
            onDismiss = { confirmMarkAll = false },
        )
    }

    val activeFilterCount = listOf(
        state.downloadedOnly,
        state.selectedPodcastId != null,
        state.dateRange != InboxDateRange.ALL,
        state.mood != InboxMood.ALL,
        state.sessionMinutes != null,
        state.hideSpecials,
    ).count { it }

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
                    // One row that fits. The previous version scrolled sideways,
                    // which hid half its controls behind a gesture nothing
                    // advertised: everything past the third button was invisible
                    // and there was no edge, arrow or cut-off item to suggest
                    // otherwise. Filters stay in reach; the rarer two move into
                    // the overflow.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlineButton(
                            text = if (activeFilterCount > 0) {
                                stringResource(R.string.inbox_filters_active, activeFilterCount)
                            } else {
                                stringResource(R.string.inbox_filters)
                            },
                            onClick = { showFilters = !showFilters },
                            leadingIcon = if (showFilters) {
                                PhosphorIcons.CaretUp
                            } else {
                                PhosphorIcons.Funnel
                            },
                        )
                        OverflowMenu(
                            contentDescription = stringResource(R.string.inbox_more_options),
                            actions = listOf(
                                MenuAction(
                                    label = stringResource(R.string.inbox_settings),
                                    icon = PhosphorIcons.SlidersHorizontal,
                                    onClick = onToggleSettings,
                                ),
                                MenuAction(
                                    label = stringResource(R.string.inbox_mark_all),
                                    icon = PhosphorIcons.CheckCircle,
                                    destructive = true,
                                    enabled = feed.isNotEmpty(),
                                    onClick = { confirmMarkAll = true },
                                ),
                            ),
                        )
                    }
                    if (showFilters) {
                        InboxFilters(
                            state = state,
                            onSetDownloadedOnly = onSetDownloadedOnly,
                            onSetPodcastFilter = onSetPodcastFilter,
                            onSetDateRange = onSetDateRange,
                            onSetMood = onSetMood,
                            onSetHideSpecials = onSetHideSpecials,
                            onSetSessionMinutes = onSetSessionMinutes,
                            onQueueSession = onQueueSession,
                        )
                    }
                }
            }
        }

        if (state.showSettings) {
            item(key = "settings") {
                InboxSettings(
                    subscriptions = state.subscriptions,
                    priorityPodcastIds = state.priorityPodcastIds,
                    onSetMode = onSetMode,
                    onTogglePriority = onTogglePriority,
                )
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
                    body = stringResource(R.string.inbox_empty_caught_up),
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
                            playing = state.currentEpisodePlaying && item.episode.id == state.currentEpisodeId,
                            onOpen = { onOpenEpisode(item.episode.id) },
                            onPlay = { onPlay(item) },
                            onQueue = { onQueue(item) },
                            downloadState = state.downloadStates[item.episode.id],
                            downloadProgress = state.downloadProgress[item.episode.id] ?: 0,
                            onDownload = { onDownload(item) },
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
private fun InboxFilters(
    state: InboxUiState,
    onSetDownloadedOnly: (Boolean) -> Unit,
    onSetPodcastFilter: (String?) -> Unit,
    onSetDateRange: (InboxDateRange) -> Unit,
    onSetMood: (InboxMood) -> Unit,
    onSetHideSpecials: (Boolean) -> Unit,
    onSetSessionMinutes: (Int?) -> Unit,
    onQueueSession: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    // Every group wraps instead of scrolling, and every group says what it is.
    // Four unlabelled rows of chips that each run off the right edge is a
    // guessing game played sideways.
    FilterGroup(stringResource(R.string.inbox_filter_content)) {
        KoalaChip(
            label = stringResource(R.string.inbox_downloaded),
            selected = state.downloadedOnly,
            onClick = { onSetDownloadedOnly(!state.downloadedOnly) },
        )
        KoalaChip(
            label = stringResource(R.string.inbox_hide_specials),
            selected = state.hideSpecials,
            onClick = { onSetHideSpecials(!state.hideSpecials) },
        )
        Box {
            KoalaChip(
                label = state.subscriptions.firstOrNull {
                    it.podcastId == state.selectedPodcastId
                }?.title ?: stringResource(R.string.inbox_all_shows),
                selected = state.selectedPodcastId != null,
                onClick = { showMenu = true },
            )
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.inbox_all_shows)) },
                    onClick = {
                        showMenu = false
                        onSetPodcastFilter(null)
                    },
                )
                state.subscriptions.forEach { subscription ->
                    DropdownMenuItem(
                        text = { Text(subscription.title, maxLines = 1) },
                        onClick = {
                            showMenu = false
                            onSetPodcastFilter(subscription.podcastId)
                        },
                    )
                }
            }
        }
    }
    FilterGroup(stringResource(R.string.inbox_filter_date)) {
        InboxDateRange.entries.forEach { range ->
            KoalaChip(
                label = stringResource(
                    when (range) {
                        InboxDateRange.ALL -> R.string.inbox_date_all
                        InboxDateRange.TODAY -> R.string.inbox_date_today
                        InboxDateRange.WEEK -> R.string.inbox_date_week
                        InboxDateRange.MONTH -> R.string.inbox_date_month
                    },
                ),
                selected = state.dateRange == range,
                onClick = { onSetDateRange(range) },
            )
        }
    }
    FilterGroup(stringResource(R.string.inbox_filter_mood)) {
        InboxMood.entries.forEach { mood ->
            KoalaChip(
                label = stringResource(
                    when (mood) {
                        InboxMood.ALL -> R.string.inbox_mood_all
                        InboxMood.FOCUS -> R.string.inbox_mood_focus
                        InboxMood.LEARN -> R.string.inbox_mood_learn
                        InboxMood.UNWIND -> R.string.inbox_mood_unwind
                        InboxMood.ENERGIZE -> R.string.inbox_mood_energize
                    },
                ),
                selected = state.mood == mood,
                onClick = { onSetMood(mood) },
            )
        }
    }
    FilterGroup(stringResource(R.string.inbox_filter_session)) {
        listOf(null, 25, 40, 60).forEach { minutes ->
            KoalaChip(
                label = minutes?.let { stringResource(R.string.inbox_minutes, it) }
                    ?: stringResource(R.string.inbox_session_off),
                selected = state.sessionMinutes == minutes,
                onClick = { onSetSessionMinutes(minutes) },
            )
        }
        if (state.sessionMinutes != null) {
            OutlineButton(
                text = stringResource(R.string.inbox_queue_session),
                onClick = onQueueSession,
                enabled = state.feed.isNotEmpty(),
            )
        }
    }
}

/** A named set of filter chips that wraps onto as many lines as it needs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(
    title: String,
    content: @Composable FlowRowScope.() -> Unit,
) {
    MonoText(
        text = title,
        color = KoalaTheme.colors.ink4,
        style = KoalaTheme.type.monoSmall,
        modifier = Modifier.padding(top = KoalaSpacing.gapSmall),
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        content = content,
    )
}

@Composable
private fun InboxSettings(
    subscriptions: List<Subscription>,
    priorityPodcastIds: Set<String>,
    onSetMode: (String, InboxMode) -> Unit,
    onTogglePriority: (String) -> Unit,
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
                KoalaChip(
                    label = stringResource(R.string.inbox_priority),
                    selected = subscription.podcastId in priorityPodcastIds,
                    onClick = { onTogglePriority(subscription.podcastId) },
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
    playing: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    downloadState: DownloadState?,
    downloadProgress: Int,
    onDownload: () -> Unit,
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                EpisodeProgressButton(
                    progressPercent = if (played) 100 else progressPercent,
                    current = isCurrent,
                    playing = playing,
                    contentDescription = stringResource(R.string.inbox_play),
                    onClick = onPlay,
                    size = 34.dp,
                )
                IconButtonSquare(
                    icon = PhosphorIcons.ListPlus,
                    contentDescription = stringResource(R.string.inbox_queue),
                    onClick = onQueue,
                    boxSize = KoalaIconButton.rowBox,
                    iconSize = KoalaIconButton.rowIcon,
                )
                DownloadButton(
                    state = downloadState,
                    progressPercent = downloadProgress,
                    contentDescription = stringResource(
                        when (downloadState) {
                            DownloadState.QUEUED, DownloadState.DOWNLOADING ->
                                R.string.inbox_pause_download
                            DownloadState.DONE -> R.string.inbox_remove_download
                            else -> R.string.inbox_download
                        },
                    ),
                    onClick = onDownload,
                    // The same 30dp box as the square icon buttons either side of
                    // it. At 34dp it was the odd one out in its own row.
                    size = 30.dp,
                )
                IconButtonSquare(
                    icon = if (played) PhosphorIcons.CheckCircleFill else PhosphorIcons.CheckCircle,
                    contentDescription = stringResource(
                        if (played) R.string.inbox_mark_unplayed else R.string.inbox_mark_played,
                    ),
                    onClick = onTogglePlayed,
                    tint = if (played) colors.accentInk else colors.ink3,
                    boxSize = KoalaIconButton.rowBox,
                    iconSize = KoalaIconButton.rowIcon,
                )
                Column {
                    IconButtonSquare(
                        icon = PhosphorIcons.CaretDown,
                        contentDescription = stringResource(R.string.inbox_more),
                        onClick = { menuOpen = true },
                        boxSize = KoalaIconButton.rowBox,
                        iconSize = KoalaIconButton.rowIcon,
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
