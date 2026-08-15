package net.koalastuff.koalacast.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.Favorite
import net.koalastuff.koalacast.core.model.PlaybackProgress
import net.koalastuff.koalacast.core.model.QueueEntry
import net.koalastuff.koalacast.core.model.Track
import net.koalastuff.koalacast.core.model.NamedQueue
import net.koalastuff.koalacast.core.ui.component.ConfirmDialog
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.EmptyState
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.OutlineButton
import net.koalastuff.koalacast.core.ui.component.ProgressTrack
import net.koalastuff.koalacast.core.ui.component.RowSeparator
import net.koalastuff.koalacast.core.ui.component.SegmentedControl
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaIconButton
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.util.Format

@Composable
fun LibraryScreen(
    onOpenPodcast: (feedUrl: String, podcastId: String?) -> Unit,
    onOpenEpisode: (episodeId: String) -> Unit,
    onOpenDiscover: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    LibraryContent(
        state = state,
        tab = tab,
        refreshing = refreshing,
        onRefresh = viewModel::refresh,
        onSelectTab = viewModel::selectTab,
        onOpenPodcast = onOpenPodcast,
        onOpenEpisode = onOpenEpisode,
        onPlay = viewModel::play,
        onOpenDiscover = onOpenDiscover,
        onUnsubscribe = viewModel::unsubscribe,
        onSetFolder = viewModel::setFolder,
        onRemoveFromQueue = viewModel::removeFromQueue,
        onMoveInQueue = { from, to ->
            val ids = state.queue.map { it.track.episodeId }.toMutableList()
            if (from in ids.indices && to in ids.indices) {
                ids.add(to, ids.removeAt(from))
                viewModel.reorderQueue(ids)
            }
        },
        onClearQueue = viewModel::clearQueue,
        onRemoveFavorite = viewModel::removeFavorite,
        onSaveNamedQueue = viewModel::saveNamedQueue,
        onRestoreNamedQueue = viewModel::restoreNamedQueue,
        onDeleteNamedQueue = viewModel::deleteNamedQueue,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryContent(
    state: LibraryUiState,
    tab: LibraryTab,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onSelectTab: (LibraryTab) -> Unit,
    onOpenPodcast: (String, String?) -> Unit,
    onOpenEpisode: (String) -> Unit,
    onPlay: (Track?) -> Unit,
    onOpenDiscover: () -> Unit,
    onUnsubscribe: (String) -> Unit,
    onSetFolder: (String, String) -> Unit,
    onRemoveFromQueue: (String) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    onRemoveFavorite: (String) -> Unit,
    onSaveNamedQueue: (String) -> Unit,
    onRestoreNamedQueue: (String) -> Unit,
    onDeleteNamedQueue: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = KoalaTheme.colors
    val tabs = LibraryTab.entries

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPanel)
            .padding(contentPadding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgRail)
                .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
            verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
        ) {
            Text(
                text = stringResource(R.string.library_title),
                style = KoalaTheme.type.screenTitle,
                color = colors.inkStrong,
            )
            SegmentedControl(
                options = tabs.map { stringResource(it.labelRes) },
                selectedIndex = tabs.indexOf(tab),
                onSelect = { onSelectTab(tabs[it]) },
            )
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            when (tab) {
                LibraryTab.SUBSCRIPTIONS -> SubscriptionGrid(
                    subscriptions = state.subscriptions,
                    onOpen = onOpenPodcast,
                    onUnsubscribe = onUnsubscribe,
                    onSetFolder = onSetFolder,
                    onOpenDiscover = onOpenDiscover,
                )

                LibraryTab.IN_PROGRESS -> InProgressList(
                    items = state.inProgress,
                    onOpenEpisode = onOpenEpisode,
                    onPlay = onPlay,
                )

                LibraryTab.QUEUE -> QueueList(
                    items = state.queue,
                    onOpenEpisode = onOpenEpisode,
                    onPlay = onPlay,
                    onRemove = onRemoveFromQueue,
                    onMove = onMoveInQueue,
                    onClear = onClearQueue,
                    namedQueues = state.namedQueues,
                    onSaveNamedQueue = onSaveNamedQueue,
                    onRestoreNamedQueue = onRestoreNamedQueue,
                    onDeleteNamedQueue = onDeleteNamedQueue,
                )

                LibraryTab.FAVORITES -> FavoritesList(
                    items = state.favorites,
                    onOpenEpisode = onOpenEpisode,
                    onPlay = onPlay,
                    onRemove = onRemoveFavorite,
                )
            }
        }
    }
}

private val LibraryTab.labelRes: Int
    get() = when (this) {
        LibraryTab.SUBSCRIPTIONS -> R.string.library_tab_subscriptions
        LibraryTab.IN_PROGRESS -> R.string.library_tab_in_progress
        LibraryTab.QUEUE -> R.string.library_tab_queue
        LibraryTab.FAVORITES -> R.string.library_tab_favorites
    }

@Composable
private fun InProgressList(
    items: List<PlaybackProgress>,
    onOpenEpisode: (String) -> Unit,
    onPlay: (Track?) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.library_empty_in_progress_title),
            body = stringResource(R.string.library_empty_in_progress_body),
            icon = PhosphorIcons.Waveform,
        )
        return
    }

    val context = LocalContext.current
    LazyColumn {
        itemsIndexed(items = items, key = { _, item -> item.episodeId }) { _, progress ->
            TrackRow(
                title = progress.track?.title.orEmpty(),
                subtitle = progress.track?.podcastTitle.orEmpty(),
                artworkUrl = progress.track?.artworkUrl,
                meta = stringResource(
                    R.string.library_left,
                    Format.duration(context, progress.remainingMs),
                ),
                progressPercent = progress.progressPercent,
                onClick = { onOpenEpisode(progress.episodeId) },
                onPlay = { onPlay(progress.track) },
            )
            RowSeparator(modifier = Modifier.padding(horizontal = KoalaSpacing.screenH))
        }
    }
}

@Composable
private fun QueueList(
    items: List<QueueEntry>,
    onOpenEpisode: (String) -> Unit,
    onPlay: (Track?) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    namedQueues: List<NamedQueue>,
    onSaveNamedQueue: (String) -> Unit,
    onRestoreNamedQueue: (String) -> Unit,
    onDeleteNamedQueue: (String) -> Unit,
) {
    val context = LocalContext.current
    val totalMs = items.sumOf { it.track.durationMs }
    var queueName by remember { mutableStateOf("") }
    // A saved queue is the only thing on this screen that cannot be rebuilt from
    // what is still on the device, so deleting one asks first.
    var pendingDelete by remember { mutableStateOf<NamedQueue?>(null) }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.library_named_queue_delete_title),
            body = stringResource(R.string.library_named_queue_delete_body, target.name),
            confirmLabel = stringResource(R.string.library_named_queue_delete_confirm),
            onConfirm = {
                pendingDelete = null
                onDeleteNamedQueue(target.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    LazyColumn {
        item(key = "named-queues") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
                verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
            ) {
                MonoText(
                    text = stringResource(R.string.library_named_queues),
                    color = KoalaTheme.colors.ink3,
                    style = KoalaTheme.type.monoStrong,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = queueName,
                        onValueChange = { queueName = it },
                        label = { Text(stringResource(R.string.library_named_queue_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlineButton(
                        text = stringResource(R.string.library_named_queue_save),
                        onClick = {
                            onSaveNamedQueue(queueName)
                            queueName = ""
                        },
                        enabled = queueName.isNotBlank() && items.isNotEmpty(),
                    )
                }
                namedQueues.forEach { saved ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onRestoreNamedQueue(saved.id) }
                                .padding(vertical = KoalaSpacing.gapSmall),
                        ) {
                            Text(
                                text = saved.name,
                                style = KoalaTheme.type.listTitle,
                                color = KoalaTheme.colors.ink2,
                            )
                            MonoText(
                                text = stringResource(
                                    R.string.library_named_queue_count,
                                    saved.itemCount,
                                ),
                                color = KoalaTheme.colors.ink4,
                                style = KoalaTheme.type.monoSmall,
                            )
                        }
                        IconButtonSquare(
                            icon = PhosphorIcons.Trash,
                            contentDescription = stringResource(
                                R.string.library_named_queue_delete,
                                saved.name,
                            ),
                            onClick = { pendingDelete = saved },
                            bordered = false,
                        )
                    }
                }
            }
            RowSeparator(modifier = Modifier.padding(horizontal = KoalaSpacing.screenH))
        }

        if (items.isEmpty()) {
            item(key = "queue-empty") {
                EmptyState(
                    title = stringResource(R.string.library_empty_queue_title),
                    body = stringResource(R.string.library_empty_queue_body),
                    icon = PhosphorIcons.ListPlus,
                )
            }
        }

        if (items.isNotEmpty()) {
        item(key = "queue-summary") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoText(
                    text = "${items.size} · ${Format.duration(context, totalMs)}",
                    color = KoalaTheme.colors.ink3,
                    style = KoalaTheme.type.monoStrong,
                )
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = KoalaSpacing.minTouchTarget)
                        .clickable(onClick = onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    MonoText(
                        text = stringResource(R.string.library_queue_clear),
                        color = KoalaTheme.colors.accentInk,
                        style = KoalaTheme.type.monoSmall,
                    )
                }
            }
        }

        itemsIndexed(items = items, key = { _, entry -> entry.id }) { index, entry ->
            QueueRow(
                entry = entry,
                index = index,
                isFirst = index == 0,
                isLast = index == items.lastIndex,
                onOpen = { onOpenEpisode(entry.track.episodeId) },
                onPlay = { onPlay(entry.track) },
                onRemove = { onRemove(entry.track.episodeId) },
                onMoveUp = { onMove(index, index - 1) },
                onMoveDown = { onMove(index, index + 1) },
            )
            RowSeparator(modifier = Modifier.padding(horizontal = KoalaSpacing.screenH))
        }
        }
    }
}

/**
 * Reordering uses explicit up/down controls rather than a drag gesture: they work
 * with TalkBack and with a switch, which a long-press drag does not. A drag
 * affordance can be layered on top later — never instead.
 */
@Composable
private fun QueueRow(
    entry: QueueEntry,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val colors = KoalaTheme.colors
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gapSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(22.dp)) {
                MonoText(
                    text = "${index + 1}.",
                    color = colors.ink4,
                    style = KoalaTheme.type.monoStrong,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .defaultMinSize(minHeight = KoalaSpacing.minTouchTarget)
                    .padding(vertical = KoalaSpacing.gapSmall),
                verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny),
            ) {
                Text(
                    text = entry.track.title,
                    style = KoalaTheme.type.listTitle,
                    color = colors.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MonoText(
                    text = listOf(
                        entry.track.podcastTitle,
                        Format.duration(context, entry.track.durationMs),
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    color = colors.ink4,
                    style = KoalaTheme.type.monoSmall,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButtonSquare(
                icon = PhosphorIcons.PlayFill,
                contentDescription = stringResource(R.string.library_play),
                onClick = onPlay,
                tint = colors.accentInk,
                bordered = false,
                boxSize = KoalaIconButton.compactBox,
                iconSize = KoalaIconButton.compactIcon,
            )
            IconButtonSquare(
                icon = PhosphorIcons.CaretUp,
                contentDescription = stringResource(R.string.library_queue_move_up),
                onClick = onMoveUp,
                tint = if (isFirst) colors.track else colors.ink3,
                bordered = false,
                boxSize = KoalaIconButton.compactBox,
                iconSize = KoalaIconButton.compactIcon,
                enabled = !isFirst,
            )
            IconButtonSquare(
                icon = PhosphorIcons.CaretDown,
                contentDescription = stringResource(R.string.library_queue_move_down),
                onClick = onMoveDown,
                tint = if (isLast) colors.track else colors.ink3,
                bordered = false,
                boxSize = KoalaIconButton.compactBox,
                iconSize = KoalaIconButton.compactIcon,
                enabled = !isLast,
            )
            IconButtonSquare(
                icon = PhosphorIcons.X,
                contentDescription = stringResource(R.string.library_queue_remove),
                onClick = onRemove,
                tint = colors.ink3,
                bordered = false,
                boxSize = KoalaIconButton.compactBox,
                iconSize = KoalaIconButton.compactIcon,
            )
        }
    }
}

@Composable
private fun FavoritesList(
    items: List<Favorite>,
    onOpenEpisode: (String) -> Unit,
    onPlay: (Track?) -> Unit,
    onRemove: (String) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.library_empty_favorites_title),
            body = stringResource(R.string.library_empty_favorites_body),
            icon = PhosphorIcons.Heart,
        )
        return
    }

    val context = LocalContext.current
    LazyColumn {
        itemsIndexed(items = items, key = { _, item -> item.episodeId }) { _, favorite ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    TrackRow(
                        title = favorite.track?.title.orEmpty(),
                        subtitle = favorite.track?.podcastTitle.orEmpty(),
                        artworkUrl = favorite.track?.artworkUrl,
                        meta = Format.duration(context, favorite.track?.durationMs ?: 0L),
                        progressPercent = null,
                        onClick = { onOpenEpisode(favorite.episodeId) },
                        onPlay = { onPlay(favorite.track) },
                    )
                }
                IconButtonSquare(
                    icon = PhosphorIcons.HeartFill,
                    contentDescription = stringResource(R.string.library_favorite_remove),
                    onClick = { onRemove(favorite.episodeId) },
                    tint = KoalaTheme.colors.accentInk,
                    bordered = false,
                    boxSize = KoalaIconButton.rowBox,
                    modifier = Modifier.padding(end = KoalaSpacing.gapSmall),
                )
            }
            RowSeparator(modifier = Modifier.padding(horizontal = KoalaSpacing.screenH))
        }
    }
}

@Composable
private fun TrackRow(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    meta: String,
    progressPercent: Int?,
    onClick: () -> Unit,
    onPlay: () -> Unit,
) {
    val colors = KoalaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CoverArt(
                url = artworkUrl,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                sizeHint = 56.dp,
            )
            IconButtonSquare(
                icon = PhosphorIcons.PlayFill,
                contentDescription = stringResource(R.string.library_play),
                onClick = onPlay,
                tint = colors.inkStrong,
                bordered = false,
                boxSize = KoalaIconButton.rowBox,
                iconSize = KoalaIconButton.rowIcon,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny),
        ) {
            Text(
                text = title,
                style = KoalaTheme.type.listTitle,
                color = colors.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MonoText(
                text = listOf(subtitle, meta).filter { it.isNotBlank() }.joinToString(" · "),
                color = colors.ink4,
                style = KoalaTheme.type.monoSmall,
            )
            if (progressPercent != null) {
                ProgressTrack(percent = progressPercent, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
