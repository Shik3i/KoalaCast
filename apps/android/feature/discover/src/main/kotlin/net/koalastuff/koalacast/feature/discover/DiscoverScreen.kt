package net.koalastuff.koalacast.feature.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.PodcastSummary
import net.koalastuff.koalacast.core.ui.component.AccentBadge
import net.koalastuff.koalacast.core.ui.component.AccentButton
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.DataErrorState
import net.koalastuff.koalacast.core.ui.component.KoalaChip
import net.koalastuff.koalacast.core.ui.component.OutlineButton
import net.koalastuff.koalacast.core.ui.component.PhosphorIcon
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.RowSeparator
import net.koalastuff.koalacast.core.ui.component.SkeletonRows
import net.koalastuff.koalacast.core.ui.genre.GENRES
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.theme.spotlightGlow
import net.koalastuff.koalacast.core.ui.util.Format
import net.koalastuff.koalacast.core.ui.R as CoreR

@Composable
fun DiscoverScreen(
    onOpenSearch: () -> Unit,
    onOpenPodcast: (feedUrl: String, podcastId: String?) -> Unit,
    onOpenEpisode: (episodeId: String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DiscoverContent(
        state = state,
        onOpenSearch = onOpenSearch,
        onSelectCategory = viewModel::selectCategory,
        onOpenPodcast = onOpenPodcast,
        onOpenEpisode = onOpenEpisode,
        onRetry = viewModel::retry,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
internal fun DiscoverContent(
    state: DiscoverUiState,
    onOpenSearch: () -> Unit,
    onSelectCategory: (String) -> Unit,
    onOpenPodcast: (String, String?) -> Unit,
    onOpenEpisode: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = KoalaTheme.colors

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPanel),
        contentPadding = contentPadding,
    ) {
        item(key = "search") {
            Box(
                modifier = Modifier
                    .background(colors.bgRail)
                    .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
            ) {
                SearchEntry(onClick = onOpenSearch)
            }
        }

        item(key = "spotlight") {
            when {
                state.error != null && state.chart.isEmpty() -> DataErrorState(
                    error = state.error,
                    serverUrl = state.serverUrl,
                    onRetry = onRetry,
                )

                state.loading -> SkeletonRows(
                    count = 4,
                    modifier = Modifier.padding(KoalaSpacing.screenH),
                )

                state.spotlight != null -> SpotlightCard(
                    spotlight = state.spotlight,
                    onOpenShow = { onOpenPodcast(state.spotlight.show.feedUrl, state.spotlight.podcastId) },
                    onOpenEpisode = { state.spotlight.episode?.let { onOpenEpisode(it.id) } },
                )
            }
        }

        if (!state.loading && state.error == null) {
            item(key = "categories") {
                CategoryRow(selected = state.category, onSelect = onSelectCategory)
            }

            item(key = "chart-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = KoalaSpacing.screenH,
                            end = KoalaSpacing.screenH,
                            top = KoalaSpacing.sectionV,
                            bottom = KoalaSpacing.gap,
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = stringResource(R.string.discover_chart_title),
                        style = KoalaTheme.type.sectionTitle,
                        color = colors.inkStrong,
                    )
                    MonoText(
                        text = pluralStringResource(
                            R.plurals.discover_chart_count,
                            state.chart.size,
                            state.chart.size,
                        ),
                        color = colors.ink4,
                        style = KoalaTheme.type.monoSmall,
                    )
                }
            }

            itemsIndexed(
                items = state.chart,
                key = { _, show -> show.feedUrl.ifBlank { show.id } },
            ) { index, show ->
                ChartRow(
                    rank = index + 1,
                    show = show,
                    // iTunes Top Charts entries carry no feed URL, so the provider id
                    // has to travel too — the server resolves it via iTunes Lookup.
                    onClick = { onOpenPodcast(show.feedUrl, show.id) },
                )
                RowSeparator(modifier = Modifier.padding(horizontal = KoalaSpacing.screenH))
            }
        }
    }
}

@Composable
private fun SpotlightCard(
    spotlight: Spotlight,
    onOpenShow: () -> Unit,
    onOpenEpisode: () -> Unit,
) {
    val colors = KoalaTheme.colors
    val context = LocalContext.current
    val episode = spotlight.episode

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .spotlightGlow(colors.accentFill)
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccentBadge(label = stringResource(R.string.discover_cover_story))
            MonoText(
                text = buildString {
                    append(spotlight.show.author.ifBlank { spotlight.show.title })
                    if (episode != null && episode.durationMs > 0) {
                        append(" · ")
                        append(Format.duration(context, episode.durationMs))
                    }
                },
                color = colors.ink4,
                style = KoalaTheme.type.monoSmall,
            )
        }

        val dek = episode?.let { Format.plainText(it.description) }
            ?: Format.plainText(spotlight.show.description)
        Row(
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(
                url = spotlight.show.artworkUrl,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                sizeHint = 160.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
            ) {
                Text(
                    text = episode?.title ?: spotlight.show.title,
                    style = KoalaTheme.type.displaySmall,
                    color = colors.inkStrong,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (dek.isNotBlank()) {
                    Text(
                        text = dek,
                        style = KoalaTheme.type.bodySmall,
                        color = colors.ink3,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
            AccentButton(
                text = stringResource(R.string.discover_open_show),
                onClick = onOpenShow,
            )
            if (episode != null) {
                OutlineButton(
                    text = stringResource(R.string.discover_open_episode),
                    onClick = onOpenEpisode,
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = KoalaSpacing.screenH),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
    ) {
        item(key = "all") {
            KoalaChip(
                label = stringResource(CoreR.string.genre_all),
                selected = selected.isBlank(),
                onClick = { onSelect("") },
            )
        }
        items(items = GENRES, key = { it.id }) { genre ->
            KoalaChip(
                label = stringResource(genre.labelRes),
                selected = selected == genre.wireName,
                onClick = { onSelect(genre.wireName) },
            )
        }
    }
}

@Composable
private fun ChartRow(
    rank: Int,
    show: PodcastSummary,
    onClick: () -> Unit,
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
        Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.CenterStart) {
            MonoText(
                text = rank.toString(),
                color = if (rank <= 3) colors.accentInk else colors.ink4,
                style = KoalaTheme.type.monoStrong,
            )
        }
        CoverArt(
            url = show.artworkUrl,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            sizeHint = 52.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny),
        ) {
            Text(
                text = show.title,
                style = KoalaTheme.type.listTitle,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MonoText(
                text = listOf(show.author, show.category)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                color = colors.ink4,
                style = KoalaTheme.type.monoSmall,
            )
        }
    }
}

/**
 * Looks like the search field but behaves like a button: tapping it opens the search
 * screen instead of raising a keyboard over a list that cannot answer yet.
 */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    val colors = KoalaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KoalaShapes.card)
            .background(colors.bgSunken)
            .border(BorderStroke(1.dp, colors.borderUi), KoalaShapes.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorIcon(
            icon = PhosphorIcons.MagnifyingGlass,
            contentDescription = null,
            tint = colors.ink4,
            size = 15.dp,
        )
        Text(
            text = stringResource(R.string.discover_search_placeholder),
            style = KoalaTheme.type.bodySmall,
            color = colors.ink4,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
