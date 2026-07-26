package net.koalastuff.koalacast.feature.episode

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

data class EpisodeUiState(
    val loading: Boolean = true,
    val error: DataError? = null,
    val serverUrl: String = "",
    val episode: Episode? = null,
    /** Loaded after the episode, purely for the artwork and show name in the header. */
    val podcast: Podcast? = null,
)

@HiltViewModel
class EpisodeViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val preferences: PreferencesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val episodeId: String = savedStateHandle.get<String>(ARG_EPISODE_ID).orEmpty()

    private val _state = MutableStateFlow(EpisodeUiState())
    val state: StateFlow<EpisodeUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            _state.update {
                it.copy(loading = true, error = null, serverUrl = preferences.serverUrl.first())
            }

            when (val result = podcasts.episode(episodeId)) {
                is DataResult.Success -> {
                    _state.update { it.copy(loading = false, episode = result.data) }
                    loadShow(result.data.podcastId)
                }

                is DataResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.error) }
            }
        }
    }

    /** Best-effort: the episode reads fine without its show's artwork. */
    private fun loadShow(podcastId: String) {
        if (podcastId.isBlank()) return
        viewModelScope.launch {
            val podcast = (podcasts.podcast(podcastId) as? DataResult.Success)?.data ?: return@launch
            _state.update { it.copy(podcast = podcast) }
        }
    }

    companion object {
        const val ARG_EPISODE_ID = "episodeId"
    }
}
