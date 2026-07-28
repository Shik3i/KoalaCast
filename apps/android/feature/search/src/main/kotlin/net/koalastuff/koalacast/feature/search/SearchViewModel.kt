package net.koalastuff.koalacast.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.repository.PodcastRepository
import net.koalastuff.koalacast.core.data.repository.ContentTtl
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.PodcastSummary
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<PodcastSummary> = emptyList(),
    val error: DataError? = null,
    val serverUrl: String = "",
    /**
     * Languages and genre start from the listener's settings — the same behaviour as
     * the web client — and can be cleared to search everything.
     */
    val languages: Set<String> = emptySet(),
    val category: String = "",
    val filtersFromSettings: Boolean = true,
    /** Set when the query is a feed URL; adding it resolves and opens the show. */
    val feedUrlCandidate: String? = null,
    val addingFeed: Boolean = false,
    val addedPodcastId: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val preferences: PreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = preferences.preferences.first()
            _state.update {
                it.copy(
                    serverUrl = prefs.serverUrl,
                    languages = prefs.languages,
                    category = prefs.category,
                )
            }
        }

        viewModelScope.launch {
            _state
                .map { SearchRequest(it.query.trim(), it.languages, it.category) }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collectLatest { runSearch(it, force = false) }
        }
    }

    fun onQueryChange(value: String) {
        _state.update {
            it.copy(
                query = value,
                feedUrlCandidate = value.trim().takeIf(::looksLikeFeedUrl),
                addedPodcastId = null,
                error = null,
            )
        }
    }

    fun clearQuery() = onQueryChange("")

    fun toggleLanguage(code: String) {
        _state.update { current ->
            val next = if (code in current.languages) {
                current.languages - code
            } else {
                current.languages + code
            }
            current.copy(languages = next, filtersFromSettings = false)
        }
    }

    fun selectCategory(wireName: String) {
        _state.update { it.copy(category = wireName, filtersFromSettings = false) }
    }

    /** Drops both filters so the search covers everything the server can see. */
    fun clearFilters() {
        _state.update { it.copy(languages = emptySet(), category = "", filtersFromSettings = false) }
    }

    /** Puts the listener's saved defaults back. */
    fun resetFiltersToSettings() {
        viewModelScope.launch {
            val prefs = preferences.preferences.first()
            _state.update {
                it.copy(
                    languages = prefs.languages,
                    category = prefs.category,
                    filtersFromSettings = true,
                )
            }
        }
    }

    /** Ingests a pasted RSS URL and reports the podcast id the caller should open. */
    fun addFeed() {
        val feedUrl = _state.value.feedUrlCandidate ?: return
        if (_state.value.addingFeed) return
        _state.update { it.copy(addingFeed = true, error = null) }

        viewModelScope.launch {
            when (val result = podcasts.resolveFeed(feedUrl)) {
                is DataResult.Success -> _state.update {
                    it.copy(addingFeed = false, addedPodcastId = result.data.id)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(addingFeed = false, error = result.error)
                }
            }
        }
    }

    fun consumeAddedPodcast() = _state.update { it.copy(addedPodcastId = null) }

    fun retry() = rerun()

    private fun rerun() {
        viewModelScope.launch {
            val state = _state.value
            runSearch(
                SearchRequest(state.query.trim(), state.languages, state.category),
                force = true,
            )
        }
    }

    private suspend fun runSearch(request: SearchRequest, force: Boolean) {
        val query = request.query
        if (query.length < MIN_QUERY_LENGTH) {
            _state.update { it.copy(results = emptyList(), searching = false, error = null) }
            return
        }

        val cached = podcasts.cachedSearch(
            query = query,
            languages = request.languages,
            category = request.category,
        )
        _state.update {
            if (it.query.trim() != query) {
                it
            } else {
                it.copy(
                    searching = true,
                    results = cached?.value ?: it.results,
                    error = null,
                )
            }
        }
        if (cached != null && !force && podcasts.isFresh(cached, ContentTtl.SEARCH)) {
            _state.update { it.copy(searching = false) }
            return
        }
        when (
            val result = podcasts.search(
                query = query,
                languages = request.languages,
                category = request.category,
            )
        ) {
            is DataResult.Success -> _state.update {
                // A slower earlier query must not overwrite a newer one.
                if (it.query.trim() != query) it else it.copy(searching = false, results = result.data)
            }

            is DataResult.Failure -> _state.update {
                if (it.query.trim() != query) {
                    it
                } else {
                    it.copy(
                        searching = false,
                        error = if (it.results.isEmpty()) result.error else null,
                    )
                }
            }
        }
    }

    private fun looksLikeFeedUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)

    private data class SearchRequest(
        val query: String,
        val languages: Set<String>,
        val category: String,
    )

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val MIN_QUERY_LENGTH = 2
    }
}
