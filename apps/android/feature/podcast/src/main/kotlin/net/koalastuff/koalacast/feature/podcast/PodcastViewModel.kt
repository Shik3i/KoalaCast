package net.koalastuff.koalacast.feature.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import net.koalastuff.koalacast.core.model.Podcast
import javax.inject.Inject

data class PodcastUiState(
    val loading: Boolean = true,
    val error: DataError? = null,
    val serverUrl: String = "",
    val podcast: Podcast? = null,
    val episodes: List<Episode> = emptyList(),
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
)

@HiltViewModel
class PodcastViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val preferences: PreferencesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val podcastId: String = savedStateHandle.get<String>(ARG_PODCAST_ID).orEmpty()
    private val feedUrl: String = savedStateHandle.get<String>(ARG_FEED_URL).orEmpty()

    private val _state = MutableStateFlow(PodcastUiState())
    val state: StateFlow<PodcastUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    serverUrl = preferences.serverUrl.first(),
                )
            }

            // Discovery and search hand back provider records, so a show reached from
            // a chart row has only its feed URL. Posting that URL resolves it (and
            // ingests it the first time anybody opens it).
            val resolved = if (podcastId.isNotBlank()) {
                podcasts.podcast(podcastId)
            } else {
                podcasts.resolveFeed(feedUrl)
            }

            when (resolved) {
                is DataResult.Failure -> {
                    _state.update { it.copy(loading = false, error = resolved.error) }
                    return@launch
                }

                is DataResult.Success -> {
                    _state.update { it.copy(podcast = resolved.data) }
                    loadEpisodes(resolved.data.id, offset = 0)
                }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val podcast = current.podcast ?: return
        if (current.loadingMore || current.endReached || current.loading) return

        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch { loadEpisodes(podcast.id, offset = current.episodes.size) }
    }

    private suspend fun loadEpisodes(id: String, offset: Int) {
        when (val result = podcasts.episodes(id, limit = PAGE_SIZE, offset = offset)) {
            is DataResult.Success -> _state.update { current ->
                current.copy(
                    loading = false,
                    loadingMore = false,
                    episodes = if (offset == 0) result.data else current.episodes + result.data,
                    endReached = result.data.size < PAGE_SIZE,
                )
            }

            is DataResult.Failure -> _state.update { current ->
                // A failed *page* must not wipe the episodes already on screen.
                if (offset == 0) {
                    current.copy(loading = false, loadingMore = false, error = result.error)
                } else {
                    current.copy(loadingMore = false, endReached = true)
                }
            }
        }
    }

    companion object {
        const val ARG_PODCAST_ID = "podcastId"
        const val ARG_FEED_URL = "feedUrl"
        private const val PAGE_SIZE = 50
    }
}
