package net.koalastuff.koalacast.feature.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.mapper.toTrack
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.repository.LibraryRepository
import net.koalastuff.koalacast.core.data.repository.PodcastRepository
import net.koalastuff.koalacast.core.data.repository.ProgressRepository
import net.koalastuff.koalacast.core.data.repository.QueueRepository
import net.koalastuff.koalacast.core.player.PlayerConnection
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.Podcast
import net.koalastuff.koalacast.core.model.PodcastSettings
import net.koalastuff.koalacast.core.model.Track
import javax.inject.Inject

data class PodcastUiState(
    val loading: Boolean = true,
    val error: DataError? = null,
    val serverUrl: String = "",
    val podcast: Podcast? = null,
    val episodes: List<Episode> = emptyList(),
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val subscribed: Boolean = false,
    val favoriteIds: Set<String> = emptySet(),
    val queuedIds: Set<String> = emptySet(),
    val completedIds: Set<String> = emptySet(),
    val settings: PodcastSettings = PodcastSettings(podcastId = ""),
)

@HiltViewModel
class PodcastViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val preferences: PreferencesRepository,
    private val library: LibraryRepository,
    private val queue: QueueRepository,
    private val progress: ProgressRepository,
    private val player: PlayerConnection,
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
                    observeLocalState(resolved.data.id)
                    loadEpisodes(resolved.data.id, offset = 0)
                }
            }
        }
    }

    /**
     * Subscription, saved and queued state come from Room, so the buttons keep
     * telling the truth after a change made on another screen — or offline.
     */
    private fun observeLocalState(id: String) {
        viewModelScope.launch {
            combine(
                library.isSubscribed(id),
                library.favoriteEpisodeIds,
                queue.queuedEpisodeIds,
                progress.completedEpisodeIds,
                library.podcastSettings(id),
            ) { subscribed, favorites, queued, completed, settings ->
                LocalState(subscribed, favorites, queued, completed, settings)
            }.collect { local ->
                _state.update {
                    it.copy(
                        subscribed = local.subscribed,
                        favoriteIds = local.favoriteIds,
                        queuedIds = local.queuedIds,
                        completedIds = local.completedIds,
                        settings = local.settings,
                    )
                }
            }
        }
    }

    private data class LocalState(
        val subscribed: Boolean,
        val favoriteIds: Set<String>,
        val queuedIds: Set<String>,
        val completedIds: Set<String>,
        val settings: PodcastSettings,
    )

    fun loadMore() {
        val current = _state.value
        val podcast = current.podcast ?: return
        if (current.loadingMore || current.endReached || current.loading) return

        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch { loadEpisodes(podcast.id, offset = current.episodes.size) }
    }

    fun toggleSubscribe() {
        val podcast = _state.value.podcast ?: return
        viewModelScope.launch {
            if (_state.value.subscribed) {
                library.unsubscribe(podcast.id)
            } else {
                library.subscribe(podcast)
            }
        }
    }

    fun play(episode: Episode) {
        val track = trackFor(episode) ?: return
        player.play(track)
    }

    fun toggleFavorite(episode: Episode) {
        val track = trackFor(episode) ?: return
        viewModelScope.launch {
            if (episode.id in _state.value.favoriteIds) {
                library.removeFavorite(episode.id)
            } else {
                library.addFavorite(track)
            }
        }
    }

    fun toggleQueue(episode: Episode) {
        val track = trackFor(episode) ?: return
        viewModelScope.launch {
            if (episode.id in _state.value.queuedIds) {
                queue.remove(episode.id)
            } else {
                queue.addToEnd(track)
            }
        }
    }

    fun togglePlayed(episode: Episode) {
        val track = trackFor(episode) ?: return
        viewModelScope.launch {
            progress.setPlayed(track, played = episode.id !in _state.value.completedIds)
        }
    }

    fun setSpeed(speed: Float?) {
        saveSettings(_state.value.settings.copy(speed = speed))
    }

    fun toggleAutoQueue() {
        val current = _state.value.settings
        saveSettings(current.copy(autoQueueNew = !current.autoQueueNew))
    }

    fun markAllPlayed(played: Boolean) {
        val tracks = _state.value.episodes.mapNotNull(::trackFor)
        viewModelScope.launch {
            tracks.forEach { progress.setPlayed(it, played) }
        }
    }

    private fun saveSettings(settings: PodcastSettings) {
        viewModelScope.launch { library.savePodcastSettings(settings) }
    }

    /** Denormalises the show's title and artwork onto the episode, once. */
    private fun trackFor(episode: Episode): Track? {
        val podcast = _state.value.podcast ?: return null
        return episode.toTrack(
            podcastTitle = podcast.title,
            fallbackArtworkUrl = podcast.artworkUrl,
        )
    }

    private suspend fun loadEpisodes(id: String, offset: Int) {
        when (val result = podcasts.episodes(id, limit = PAGE_SIZE, offset = offset)) {
            is DataResult.Success -> {
                _state.update { current ->
                    current.copy(
                    loading = false,
                    loadingMore = false,
                    episodes = if (offset == 0) result.data else current.episodes + result.data,
                    endReached = result.data.size < PAGE_SIZE,
                )
                }
                if (offset == 0 && library.podcastSettingsSnapshot(id).autoQueueNew) {
                    result.data.firstOrNull { it.id !in _state.value.completedIds }
                        ?.let(::trackFor)
                        ?.let { queue.addToEnd(it) }
                }
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
