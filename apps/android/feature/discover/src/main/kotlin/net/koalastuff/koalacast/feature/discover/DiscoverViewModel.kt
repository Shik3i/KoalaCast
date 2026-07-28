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
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.PodcastSummary
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
        load()
    }

    fun selectCategory(wireName: String) {
        if (wireName == _state.value.category) return
        _state.update { it.copy(category = wireName) }
        load()
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        spotlightJob?.cancel()

        loadJob = viewModelScope.launch {
            val prefs = preferences.preferences.first()
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
                    category = _state.value.category,
                    region = CONTENT_LANGUAGES
                        .firstOrNull { it.code in prefs.languages }
                        ?.region,
                    languages = prefs.languages,
                    limit = CHART_SIZE,
                )
            ) {
                is DataResult.Success -> {
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            chart = result.data,
                            spotlight = result.data.firstOrNull()?.let(::Spotlight),
                        )
                    }
                    result.data.firstOrNull()?.let(::loadSpotlightEpisode)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, refreshing = false, error = result.error)
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
