package net.koalastuff.koalacast.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.repository.PodcastRepository
import net.koalastuff.koalacast.core.data.repository.ContentTtl
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.HiddenPodcast
import net.koalastuff.koalacast.core.model.PodcastSummary
import net.koalastuff.koalacast.core.model.isHiddenBy
import net.koalastuff.koalacast.core.model.isHiddenByPodcast
import net.koalastuff.koalacast.core.model.matchesGenres
import net.koalastuff.koalacast.core.model.preferenceKey
import net.koalastuff.koalacast.core.ui.language.CONTENT_LANGUAGES
import javax.inject.Inject

/**
 * The cover story: the chart's leading show plus its newest episode. The episode is
 * resolved separately and may stay null — the spotlight then shows the show alone
 * rather than inventing a headline.
 */
data class Spotlight(
    val show: PodcastSummary,
    val podcastId: String? = null,
    val episode: Episode? = null,
)

data class DiscoverUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: DataError? = null,
    val serverUrl: String = "",
    /** Wire genre name, or blank for the whole chart. */
    val category: String = "",
    val chart: List<PodcastSummary> = emptyList(),
    val spotlight: Spotlight? = null,
    /** The show hidden most recently, while the reversal is still on offer. */
    val lastHidden: HiddenPodcast? = null,
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val preferences: PreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var spotlightJob: Job? = null

    init {
        load(force = false)
    }

    fun selectCategory(wireName: String) {
        if (wireName == _state.value.category) return
        _state.update { it.copy(category = wireName) }
        load(force = false)
    }

    fun retry() = load(force = true)

    fun hidePodcast(show: PodcastSummary) {
        val key = show.preferenceKey()
        val visible = _state.value.chart.filterNot { it.preferenceKey() == key }
        _state.update {
            it.copy(
                chart = visible,
                spotlight = visible.firstOrNull()?.let(::Spotlight),
                lastHidden = HiddenPodcast(key = key, title = show.title),
            )
        }
        visible.firstOrNull()?.let(::loadSpotlightEpisode)
        viewModelScope.launch {
            preferences.hidePodcast(HiddenPodcast(key = key, title = show.title))
        }
    }

    /** Puts the show back where it was, without a trip through Settings. */
    fun undoHide() {
        val hidden = _state.value.lastHidden ?: return
        _state.update { it.copy(lastHidden = null) }
        viewModelScope.launch {
            preferences.unhidePodcast(hidden.key)
            load(force = false)
        }
    }

    fun dismissUndo() {
        _state.update { it.copy(lastHidden = null) }
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        spotlightJob?.cancel()

        loadJob = viewModelScope.launch {
            val prefs = preferences.preferences.first()
            val category = _state.value.category
            val region = CONTENT_LANGUAGES
                .firstOrNull { it.code in prefs.languages }
                ?.region
            val cached = podcasts.cachedDiscover(
                category = category,
                region = region,
                languages = prefs.languages,
                limit = CHART_SIZE,
            )
            if (cached != null && category == _state.value.category) {
                val visible = cached.value.filterNot {
                    it.isHiddenBy(prefs.hiddenGenres) || it.isHiddenByPodcast(prefs.hiddenPodcasts)
                }.prioritize(prefs.interests, category)
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = true,
                        error = null,
                        serverUrl = prefs.serverUrl,
                        chart = visible,
                        spotlight = visible.firstOrNull()?.let(::Spotlight),
                    )
                }
                visible.firstOrNull()?.let(::loadSpotlightEpisode)
                if (!force && podcasts.isFresh(cached, ContentTtl.DISCOVER)) {
                    _state.update { it.copy(refreshing = false) }
                    return@launch
                }
            }
            _state.update {
                it.copy(
                    loading = it.chart.isEmpty(),
                    refreshing = it.chart.isNotEmpty(),
                    error = null,
                    serverUrl = prefs.serverUrl,
                )
            }

            when (
                val result = podcasts.discover(
                    category = category,
                    region = region,
                    languages = prefs.languages,
                    limit = CHART_SIZE,
                )
            ) {
                is DataResult.Success -> {
                    if (category != _state.value.category) return@launch
                    val visible = result.data.filterNot {
                        it.isHiddenBy(prefs.hiddenGenres) || it.isHiddenByPodcast(prefs.hiddenPodcasts)
                    }.prioritize(prefs.interests, category)
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            chart = visible,
                            spotlight = visible.firstOrNull()?.let(::Spotlight),
                        )
                    }
                    visible.firstOrNull()?.let(::loadSpotlightEpisode)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = if (it.chart.isEmpty()) result.error else null,
                    )
                }
            }
        }
    }

    /**
     * Discovery returns provider records, so the show has to be resolved into a
     * KoalaCast podcast before its episodes exist. Best-effort: a failure here leaves
     * the spotlight without an episode instead of failing the screen.
     */
    private fun loadSpotlightEpisode(show: PodcastSummary) {
        if (show.feedUrl.isBlank()) return

        spotlightJob = viewModelScope.launch {
            val podcast = (podcasts.resolveFeed(show.feedUrl) as? DataResult.Success)?.data
                ?: return@launch
            val episode = (podcasts.episodes(podcast.id, limit = 1) as? DataResult.Success)
                ?.data
                ?.firstOrNull()

            _state.update { current ->
                if (current.spotlight?.show?.feedUrl != show.feedUrl) {
                    current
                } else {
                    current.copy(
                        spotlight = current.spotlight.copy(
                            podcastId = podcast.id,
                            episode = episode,
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        const val CHART_SIZE = 60
    }
}

private fun List<PodcastSummary>.prioritize(
    interests: Set<String>,
    selectedCategory: String,
): List<PodcastSummary> =
    if (selectedCategory.isBlank() && interests.isNotEmpty()) {
        sortedByDescending { it.matchesGenres(interests) }
    } else {
        this
    }
