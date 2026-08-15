package net.koalastuff.koalacast.feature.episode

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.Chapter
import net.koalastuff.koalacast.core.model.TimeBookmark

import net.koalastuff.koalacast.core.ui.component.AccentButton
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.DataErrorState
import net.koalastuff.koalacast.core.ui.component.Hairline
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.LabelledDownloadAction
import net.koalastuff.koalacast.core.ui.component.LabelledIconAction
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.OutlineButton
import net.koalastuff.koalacast.core.ui.component.ShowNotes
import net.koalastuff.koalacast.core.ui.component.SkeletonRows
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.theme.spotlightGlow
import net.koalastuff.koalacast.core.ui.util.Format
import net.koalastuff.koalacast.core.ui.R as CoreR

@Composable
fun EpisodeScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: EpisodeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.episode_share_chooser)

    EpisodeContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onOpenSettings = onOpenSettings,
        onPlay = viewModel::play,
        onToggleFavorite = viewModel::toggleFavorite,
        onToggleQueue = viewModel::toggleQueue,
        onTogglePlayed = viewModel::togglePlayed,
        onToggleTranscript = viewModel::toggleTranscript,
        onTranscriptQueryChange = viewModel::setTranscriptQuery,

        onToggleChapters = viewModel::toggleChapters,

        onSeekToChapter = viewModel::seekToChapter,
        onToggleDownload = viewModel::toggleDownload,
        onAddTimeBookmark = viewModel::addTimeBookmark,
        onRemoveTimeBookmark = viewModel::removeTimeBookmark,
        onPlayTimeBookmark = viewModel::playTimeBookmark,
        onShareHandoff = {
            viewModel.handoffUrl()?.let { url ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, state.episode?.title.orEmpty())
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                context.startActivity(
                    Intent.createChooser(intent, shareChooserTitle),
                )
            }
        },
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
internal fun EpisodeContent(
    state: EpisodeUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleQueue: () -> Unit,
    onTogglePlayed: () -> Unit,
    onToggleTranscript: () -> Unit,
    onTranscriptQueryChange: (String) -> Unit,

    onToggleChapters: () -> Unit,

    onSeekToChapter: (Chapter) -> Unit,
    onToggleDownload: () -> Unit,
    onAddTimeBookmark: () -> Unit,
    onRemoveTimeBookmark: (String) -> Unit,
    onPlayTimeBookmark: (TimeBookmark) -> Unit,
    onShareHandoff: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = KoalaTheme.colors
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPanel)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Row(modifier = Modifier.padding(horizontal = KoalaSpacing.gapSmall, vertical = KoalaSpacing.gapTiny)) {
            IconButtonSquare(
                icon = PhosphorIcons.CaretLeft,
                contentDescription = stringResource(CoreR.string.action_back),
                onClick = onBack,
                bordered = false,
            )
        }

        when {
            state.error != null -> DataErrorState(
                error = state.error,
                serverUrl = state.serverUrl,
                onRetry = onRetry,
            )

            state.episode == null -> SkeletonRows(
                count = 6,
                modifier = Modifier.padding(KoalaSpacing.screenH),
            )

            state.explicitBlocked -> Column(
                modifier = Modifier.padding(KoalaSpacing.screenH),
                verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
            ) {
                Text(
                    text = stringResource(R.string.episode_explicit_blocked),
                    style = KoalaTheme.type.body,
                    color = colors.ink2,
                )
                OutlineButton(
                    text = stringResource(R.string.episode_open_settings),
                    onClick = onOpenSettings,
                )
            }

            else -> {
                val episode = state.episode

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .spotlightGlow(colors.accentFill)
                        .padding(
                            horizontal = KoalaSpacing.screenH,
                            vertical = KoalaSpacing.gapSection,
                        ),
                    verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverArt(
                            url = episode.artworkUrl.ifBlank { state.podcast?.artworkUrl },
                            contentDescription = episode.title,
                            modifier = Modifier.size(88.dp),
                            sizeHint = 88.dp,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny)) {
                            state.podcast?.title?.let { showTitle ->
                                MonoText(
                                    text = showTitle,
                                    color = colors.accentInk,
                                    style = KoalaTheme.type.monoSmall,
                                )
                            }
                            MonoText(
                                text = listOf(
                                    Format.publishedAt(
                                        context,
                                        episode.pubDateMs,
                                        episode.hasPubDate,
                                        now,
                                    ),
                                    Format.duration(context, episode.durationMs),
                                ).joinToString(" · "),
                                color = colors.ink4,
                                style = KoalaTheme.type.monoSmall,
                            )
                        }
                    }

                    Text(
                        text = episode.title,
                        style = KoalaTheme.type.screenTitle,
                        color = colors.inkStrong,
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
                    ) {
                        AccentButton(
                            text = stringResource(
                                if (state.isPlayed) R.string.episode_play_again
                                else R.string.episode_play,
                            ),
                            onClick = onPlay,
                            leadingIcon = PhosphorIcons.PlayFill,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Named, not just drawn: a heart, a tick and a tray say
                            // nothing definite to someone opening their first episode.
                            LabelledIconAction(
                                icon = if (state.isQueued) PhosphorIcons.Check else PhosphorIcons.ListPlus,
                                label = stringResource(
                                    if (state.isQueued) R.string.episode_queued_short
                                    else R.string.episode_queue_short,
                                ),
                                contentDescription = stringResource(
                                    if (state.isQueued) R.string.episode_queued else R.string.episode_queue,
                                ),
                                onClick = onToggleQueue,
                                tint = if (state.isQueued) colors.accentInk else colors.ink3,
                            )
                            LabelledIconAction(
                                icon = if (state.isFavorite) PhosphorIcons.HeartFill else PhosphorIcons.Heart,
                                label = stringResource(R.string.episode_save_short),
                                contentDescription = stringResource(
                                    if (state.isFavorite) R.string.episode_unsave else R.string.episode_save,
                                ),
                                onClick = onToggleFavorite,
                                tint = if (state.isFavorite) colors.accentInk else colors.ink3,
                            )
                            LabelledIconAction(
                                icon = if (state.isPlayed) PhosphorIcons.CheckCircleFill else PhosphorIcons.CheckCircle,
                                label = stringResource(R.string.episode_played_short),
                                contentDescription = stringResource(
                                    if (state.isPlayed) R.string.episode_mark_unplayed else R.string.episode_mark_played,
                                ),
                                onClick = onTogglePlayed,
                                tint = if (state.isPlayed) colors.accentInk else colors.ink3,
                            )
                            // A ring rather than a flat icon: a download has a
                            // duration, and the old control reported none of it.
                            LabelledDownloadAction(
                                state = state.downloadState,
                                progressPercent = state.downloadPercent,
                                label = stringResource(
                                    when (state.downloadState) {
                                        net.koalastuff.koalacast.core.model.DownloadState.DONE ->
                                            R.string.episode_downloaded_short
                                        net.koalastuff.koalacast.core.model.DownloadState.DOWNLOADING,
                                        net.koalastuff.koalacast.core.model.DownloadState.QUEUED ->
                                            R.string.episode_downloading_short
                                        else -> R.string.episode_download_short
                                    },
                                ),
                                contentDescription = stringResource(
                                    when (state.downloadState) {
                                        net.koalastuff.koalacast.core.model.DownloadState.DONE ->
                                            R.string.episode_remove_download
                                        net.koalastuff.koalacast.core.model.DownloadState.DOWNLOADING,
                                        net.koalastuff.koalacast.core.model.DownloadState.QUEUED ->
                                            R.string.episode_pause_download
                                        else -> R.string.episode_download
                                    },
                                ),
                                onClick = onToggleDownload,
                            )
                        }
                    }

                }

                Hairline(modifier = Modifier.padding(horizontal = KoalaSpacing.screenH))

                Column(
                    modifier = Modifier.padding(
                        horizontal = KoalaSpacing.screenH,
                        vertical = KoalaSpacing.sectionV,
                    ),
                    verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
                    ) {
                        OutlineButton(
                            text = stringResource(
                                R.string.episode_add_bookmark,
                                Format.timeCode(state.bookmarkPositionMs),
                            ),
                            onClick = onAddTimeBookmark,
                            leadingIcon = PhosphorIcons.Clock,
                            modifier = Modifier.weight(1f),
                        )
                        OutlineButton(
                            text = stringResource(R.string.episode_share),
                            onClick = onShareHandoff,
                            leadingIcon = PhosphorIcons.ArrowSquareOut,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (state.timeBookmarks.isNotEmpty()) {
                        MonoText(
                            text = stringResource(R.string.episode_bookmarks),
                            color = colors.ink4,
                            style = KoalaTheme.type.monoSmall,
                        )
                        state.timeBookmarks.forEach { bookmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPlayTimeBookmark(bookmark) }
                                    .padding(vertical = KoalaSpacing.gapTiny),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                            ) {
                                MonoText(
                                    text = Format.timeCode(bookmark.positionMs),
                                    color = colors.accentInk,
                                    style = KoalaTheme.type.monoStrong,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButtonSquare(
                                    icon = PhosphorIcons.Trash,
                                    contentDescription = stringResource(
                                        R.string.episode_remove_bookmark,
                                        Format.timeCode(bookmark.positionMs),
                                    ),
                                    onClick = { onRemoveTimeBookmark(bookmark.id) },
                                    bordered = false,
                                )
                            }
                        }
                    }

                    MonoText(
                        text = stringResource(R.string.episode_show_notes),
                        color = colors.ink4,
                        style = KoalaTheme.type.monoSmall,
                    )
                    val notes = episode.notesHtml
                    if (notes.isBlank()) {
                        Text(
                            text = stringResource(R.string.episode_no_notes),
                            style = KoalaTheme.type.bodySmall,
                            color = colors.ink4,
                        )
                    } else {
                        ShowNotes(html = notes)
                    }

                    if (episode.chaptersUrl.isNotBlank()) {
                        OutlineButton(
                            text = stringResource(
                                if (state.chaptersExpanded) R.string.episode_hide_chapters
                                else R.string.episode_show_chapters,
                            ),
                            onClick = onToggleChapters,
                            leadingIcon = PhosphorIcons.Clock,
                        )
                        if (state.chaptersExpanded) {
                            when {
                                state.chaptersLoading -> Text(
                                    text = stringResource(R.string.episode_loading_chapters),
                                    style = KoalaTheme.type.bodySmall,
                                    color = colors.ink4,
                                )
                                state.chaptersError -> Text(
                                    text = stringResource(R.string.episode_chapters_error),
                                    style = KoalaTheme.type.bodySmall,
                                    color = colors.ink2,
                                )
                                state.chapters.isEmpty() -> Text(
                                    text = stringResource(R.string.episode_empty_chapters),
                                    style = KoalaTheme.type.bodySmall,
                                    color = colors.ink4,
                                )
                                else -> Column {
                                    state.chapters.forEach { chapter ->
                                        ChapterRow(
                                            chapter = chapter,
                                            onClick = { onSeekToChapter(chapter) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (episode.transcripts.isNotEmpty()) {
                        OutlineButton(
                            text = stringResource(
                                if (state.transcriptExpanded) R.string.episode_hide_transcript
                                else R.string.episode_show_transcript,
                            ),
                            onClick = onToggleTranscript,
                            leadingIcon = PhosphorIcons.Waveform,
                        )
                        if (state.transcriptExpanded) {
                            when {
                                state.transcriptLoading -> Text(
                                    text = stringResource(R.string.episode_loading_transcript),
                                    style = KoalaTheme.type.bodySmall,
                                    color = colors.ink4,
                                )
                                state.transcriptError -> Text(
                                    text = stringResource(R.string.episode_transcript_error),
                                    style = KoalaTheme.type.bodySmall,
                                    color = colors.ink2,
                                )
                                else -> {
                                    OutlinedTextField(
                                        value = state.transcriptQuery,
                                        onValueChange = onTranscriptQueryChange,
                                        label = {
                                            Text(stringResource(R.string.episode_search_transcript))
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        text = state.visibleTranscript.ifBlank {
                                            stringResource(
                                                if (state.transcriptQuery.isBlank()) {
                                                    R.string.episode_empty_transcript
                                                } else {
                                                    R.string.episode_no_transcript_matches
                                                },
                                            )
                                        },
                                        style = KoalaTheme.type.bodySmall,
                                        color = colors.ink2,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One chapter. The start time is the anchor a listener scans for, so it is mono
 * and tabular; tapping the row jumps the player there.
 */
@Composable
private fun ChapterRow(
    chapter: Chapter,
    onClick: () -> Unit,
) {
    val colors = KoalaTheme.colors
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = KoalaSpacing.gapSmall),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(
            text = Format.timeCode(chapter.startMs),
            color = colors.accentInk,
            style = KoalaTheme.type.monoStrong,
        )
        Text(
            text = chapter.title,
            style = KoalaTheme.type.bodySmall,
            color = colors.ink2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
