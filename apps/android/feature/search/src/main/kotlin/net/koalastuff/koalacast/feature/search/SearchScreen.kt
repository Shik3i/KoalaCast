package net.koalastuff.koalacast.feature.search

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.PodcastSummary
import net.koalastuff.koalacast.core.ui.component.AccentButton
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.DataErrorState
import net.koalastuff.koalacast.core.ui.component.EmptyState
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.KoalaChip
import net.koalastuff.koalacast.core.ui.component.KoalaTextField
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.RowSeparator
import net.koalastuff.koalacast.core.ui.component.SkeletonRows
import net.koalastuff.koalacast.core.ui.genre.GENRES
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.language.CONTENT_LANGUAGES
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.R as CoreR

@Composable
fun SearchScreen(
    onOpenPodcast: (feedUrl: String, podcastId: String?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.addedPodcastId) {
        state.addedPodcastId?.let { id ->
            viewModel.consumeAddedPodcast()
            onOpenPodcast("", id)
        }
    }

    SearchContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::clearQuery,
        onToggleLanguage = viewModel::toggleLanguage,
        onSelectCategory = viewModel::selectCategory,
        onClearFilters = viewModel::clearFilters,
        onResetFilters = viewModel::resetFiltersToSettings,
        onAddFeed = viewModel::addFeed,
        onRetry = viewModel::retry,
        onOpenPodcast = onOpenPodcast,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
internal fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onToggleLanguage: (String) -> Unit,
    onSelectCategory: (String) -> Unit,
    onClearFilters: () -> Unit,
    onResetFilters: () -> Unit,
    onAddFeed: () -> Unit,
    onRetry: () -> Unit,
    onOpenPodcast: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = KoalaTheme.colors
    val hasFilters = state.languages.isNotEmpty() || state.category.isNotBlank()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPanel),
        contentPadding = contentPadding,
    ) {
        item(key = "field") {
            Column(
                modifier = Modifier
                    .background(colors.bgRail)
                    .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
                verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
            ) {
                KoalaTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.search_placeholder),
                    trailingContent = {
                        if (state.query.isNotEmpty()) {
                            IconButtonSquare(
                                icon = PhosphorIcons.X,
                                contentDescription = stringResource(CoreR.string.action_clear),
                                onClick = onClearQuery,
                                bordered = false,
                                boxSize = 24.dp,
                                iconSize = 15.dp,
                            )
                        }
                    },
                )
                MonoText(
                    text = stringResource(R.string.search_rss_hint),
                    color = colors.ink4,
                    style = KoalaTheme.type.monoSmall,
                )
            }
        }

        state.feedUrlCandidate?.let { feedUrl ->
            item(key = "add-feed") {
                AddFeedCard(
                    feedUrl = feedUrl,
                    adding = state.addingFeed,
                    onAdd = onAddFeed,
                )
            }
        }

        item(key = "filters") {
            FilterBlock(
                languages = state.languages,
                category = state.category,
                hasFilters = hasFilters,
                fromSettings = state.filtersFromSettings,
                onToggleLanguage = onToggleLanguage,
                onSelectCategory = onSelectCategory,
                onClearFilters = onClearFilters,
                onResetFilters = onResetFilters,
            )
        }

        when {
            state.error != null -> item(key = "error") {
                DataErrorState(
                    error = state.error,
                    serverUrl = state.serverUrl,
                    onRetry = onRetry,
                )
            }

            state.searching && state.results.isEmpty() -> item(key = "loading") {
                SkeletonRows(count = 6, modifier = Modifier.padding(KoalaSpacing.screenH))
            }

            state.query.isBlank() -> item(key = "idle") {
                EmptyState(
                    title = stringResource(R.string.search_idle_title),
                    body = stringResource(R.string.search_idle_body),
                    icon = PhosphorIcons.MagnifyingGlass,
                )
            }

            state.results.isEmpty() -> item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.search_empty_title),
                    body = stringResource(R.string.search_empty_body),
                    icon = PhosphorIcons.MagnifyingGlass,
                    actionLabel = if (hasFilters) {
                        stringResource(R.string.search_clear_filters)
                    } else {
                        null
                    },
                    onAction = onClearFilters.takeIf { hasFilters },
                )
            }

            else -> items(
                items = state.results,
                key = { it.feedUrl.ifBlank { it.id } },
            ) { show ->
                ResultRow(show = show, onClick = { onOpenPodcast(show.feedUrl, null) })
                RowSeparator(modifier = Modifier.padding(horizontal = KoalaSpacing.screenH))
            }
        }
    }
}

@Composable
private fun AddFeedCard(feedUrl: String, adding: Boolean, onAdd: () -> Unit) {
    val colors = KoalaTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
    ) {
        MonoText(
            text = stringResource(R.string.search_feed_detected),
            color = colors.accentInk,
            style = KoalaTheme.type.monoSmall,
        )
        Text(
            text = feedUrl,
            style = KoalaTheme.type.bodySmall,
            color = colors.ink3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        AccentButton(
            text = if (adding) {
                stringResource(R.string.search_feed_adding)
            } else {
                stringResource(R.string.search_feed_add)
            },
            onClick = onAdd,
            enabled = !adding,
            leadingIcon = PhosphorIcons.RssSimple,
        )
    }
}

@Composable
private fun FilterBlock(
    languages: Set<String>,
    category: String,
    hasFilters: Boolean,
    fromSettings: Boolean,
    onToggleLanguage: (String) -> Unit,
    onSelectCategory: (String) -> Unit,
    onClearFilters: () -> Unit,
    onResetFilters: () -> Unit,
) {
    val colors = KoalaTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = KoalaSpacing.screenH,
                    end = KoalaSpacing.screenH,
                    top = KoalaSpacing.gap,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoText(
                text = stringResource(R.string.search_filters_label),
                color = colors.ink4,
                style = KoalaTheme.type.monoSmall,
            )
            Box(
                modifier = Modifier.clickable(
                    onClick = if (fromSettings) onClearFilters else onResetFilters,
                ),
            ) {
                MonoText(
                    text = if (fromSettings) {
                        stringResource(R.string.search_clear_filters)
                    } else {
                        stringResource(R.string.search_reset_filters)
                    },
                    color = colors.accentInk,
                    style = KoalaTheme.type.monoSmall,
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = KoalaSpacing.screenH),
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        ) {
            items(items = CONTENT_LANGUAGES, key = { it.code }) { language ->
                KoalaChip(
                    label = language.name,
                    selected = language.code in languages,
                    onClick = { onToggleLanguage(language.code) },
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = KoalaSpacing.screenH),
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        ) {
            item(key = "all") {
                KoalaChip(
                    label = stringResource(CoreR.string.genre_all),
                    selected = category.isBlank(),
                    onClick = { onSelectCategory("") },
                )
            }
            items(items = GENRES, key = { it.id }) { genre ->
                KoalaChip(
                    label = stringResource(genre.labelRes),
                    selected = category == genre.wireName,
                    onClick = { onSelectCategory(genre.wireName) },
                )
            }
        }

        if (!hasFilters) {
            Text(
                text = stringResource(R.string.search_no_filters_note),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink4,
                modifier = Modifier.padding(horizontal = KoalaSpacing.screenH),
            )
        }
    }
}

@Composable
private fun ResultRow(show: PodcastSummary, onClick: () -> Unit) {
    val colors = KoalaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = show.artworkUrl,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            sizeHint = 56.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny),
        ) {
            Text(
                text = show.title,
                style = KoalaTheme.type.listTitle,
                color = colors.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MonoText(
                text = listOf(show.author, show.category, show.language.uppercase())
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                color = colors.ink4,
                style = KoalaTheme.type.monoSmall,
            )
        }
    }
}
