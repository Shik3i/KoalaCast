package net.koalastuff.koalacast.feature.episode

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.ui.component.AccentButton
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.DataErrorState
import net.koalastuff.koalacast.core.ui.component.Hairline
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
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
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: EpisodeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    EpisodeContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onToggleFavorite = viewModel::toggleFavorite,
        onToggleQueue = viewModel::toggleQueue,
        onTogglePlayed = viewModel::togglePlayed,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
internal fun EpisodeContent(
    state: EpisodeUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleQueue: () -> Unit,
    onTogglePlayed: () -> Unit,
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
                            contentDescription = null,
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

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.isQueued) {
                            OutlineButton(
                                text = stringResource(R.string.episode_queued),
                                onClick = onToggleQueue,
                                leadingIcon = PhosphorIcons.Check,
                            )
                        } else {
                            AccentButton(
                                text = stringResource(R.string.episode_queue),
                                onClick = onToggleQueue,
                                leadingIcon = PhosphorIcons.ListPlus,
                            )
                        }
                        IconButtonSquare(
                            icon = if (state.isFavorite) PhosphorIcons.HeartFill else PhosphorIcons.Heart,
                            contentDescription = stringResource(
                                if (state.isFavorite) R.string.episode_unsave else R.string.episode_save,
                            ),
                            onClick = onToggleFavorite,
                            tint = if (state.isFavorite) colors.accentInk else colors.ink3,
                        )
                        IconButtonSquare(
                            icon = if (state.isPlayed) PhosphorIcons.CheckCircleFill else PhosphorIcons.CheckCircle,
                            contentDescription = stringResource(
                                if (state.isPlayed) R.string.episode_mark_unplayed else R.string.episode_mark_played,
                            ),
                            onClick = onTogglePlayed,
                            tint = if (state.isPlayed) colors.accentInk else colors.ink3,
                        )
                    }

                    // Playback lands with the Media3 work (P2). Saying so beats a
                    // play button that does nothing.
                    MonoText(
                        text = stringResource(R.string.episode_playback_pending),
                        color = colors.ink4,
                        style = KoalaTheme.type.monoSmall,
                        maxLines = 2,
                    )
                }

                Hairline(modifier = Modifier.padding(horizontal = KoalaSpacing.screenH))

                Column(
                    modifier = Modifier.padding(
                        horizontal = KoalaSpacing.screenH,
                        vertical = KoalaSpacing.sectionV,
                    ),
                    verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                ) {
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

                    if (episode.transcripts.isNotEmpty()) {
                        MonoText(
                            text = pluralStringResource(
                                R.plurals.episode_transcripts,
                                episode.transcripts.size,
                                episode.transcripts.size,
                            ),
                            color = colors.ink4,
                            style = KoalaTheme.type.monoSmall,
                        )
                    }
                }
            }
        }
    }
}
