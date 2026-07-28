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
import net.koalastuff.koalacast.core.data.repository.ContentTtl
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
    val paginationError: Boolean = false,
    val subscribed: Boolean = false,
    val favoriteIds: Set<String> = emptySet(),
    val queuedIds: Set<String> = emptySet(),
    val completedIds: Set<String> = emptySet(),
    val settings: PodcastSettings = PodcastSettings(podcastId = ""),
    /** episodeId -> 0..100, for the ring on each row's play button. */
    val progressByEpisode: Map<String, Int> = emptyMap(),
    /** The episode the player currently holds, if any. */
    val currentEpisodeId: String? = null,
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
    private var observedPodcastId: String? = null
    private var progressObserved = false

    init {
        load(force = false)
    }

    fun retry() = load(force = true)

    private fun load(force: Boolean) {
        viewModelScope.launch {
            val cachedPodcast = when {
                feedUrl.isNotBlank() -> podcasts.cachedResolvedFeed(feedUrl)
                podcastId.isNotBlank() -> podcasts.cachedPodcast(podcastId)
                else -> null
            }
            if (cachedPodcast != null) {
                val cachedEpisodes = podcasts.cachedEpisodes(
                    cachedPodcast.value.id,
                    limit = PAGE_SIZE,
                )
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        serverUrl = preferences.serverUrl.first(),
                        podcast = cachedPodcast.value,
                        episodes = cachedEpisodes?.value.orEmpty(),
                        endReached = (cachedEpisodes?.value?.size ?: 0) < PAGE_SIZE,
                    )
                }
                observeLocalState(cachedPodcast.value.id)
                observeProgress()
                if (
                    !force &&
                    podcasts.isFresh(cachedPodcast, ContentTtl.PODCAST) &&
                    cachedEpisodes != null &&
                    podcasts.isFresh(cachedEpisodes, ContentTtl.EPISODE_LIST)
                ) {
                    return@launch
                }
            }
            _state.update {
                it.copy(
                    loading = it.podcast == null,
                    error = null,
                    serverUrl = preferences.serverUrl.first(),
                )
            }

            // Discovery and search hand back provider records, so a show reached from
            // a chart row has only its feed URL. Posting that URL resolves it (and
            // ingests it the first time anybody opens it).
            //
            // The feed URL wins when we have one: it identifies a show outright,
            // whereas an id is only meaningful next to the provider that issued it —
            // and iTunes and Podcast Index both issue bare numbers. iTunes Top Charts
            // ships no feed URL at all, so the id is the only handle there and
            // GET /podcasts/{id} resolves it through the iTunes Lookup API.
            val resolved = when {
                feedUrl.isNotBlank() -> podcasts.resolveFeed(feedUrl)
                podcastId.isNotBlank() -> podcasts.podcast(podcastId)
                // Neither handle: a caller navigated with two blanks. Say so instead
                // of posting an empty feed_url and rendering the server's 400.
                else -> DataResult.Failure(DataError.Malformed("no podcast id or feed url"))
            }

            when (resolved) {
                is DataResult.Failure -> {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = if (it.podcast == null) resolved.error else null,
                        )
                    }
                    return@launch
                }

                is DataResult.Success -> {
                    library.canonicalizeImportedSubscription(
                        sourceFeedUrl = feedUrl.ifBlank { resolved.data.feedUrl },
                        podcast = resolved.data,
                    )
                    _state.update { it.copy(podcast = resolved.data) }
                    observeLocalState(resolved.data.id)
                    observeProgress()
                    refreshFirstPage(resolved.data.id)
                }
            }
        }
    }

    /**
     * Subscription, saved and queued state come from Room, so the buttons keep
     * telling the truth after a change made on another screen — or offline.
     */
    private fun observeLocalState(id: String) {
        if (observedPodcastId == id) return
        observedPodcastId = id
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

    /**
     * Progress rings come from two places: stored positions for everything, and the
     * live player for the episode currently running, so its ring advances while it
     * plays instead of freezing at the last checkpoint.
     */
    private fun observeProgress() {
        if (progressObserved) return
        progressObserved = true
        viewModelScope.launch {
            combine(progress.allProgress, player.state) { stored, playback ->
                val map = stored.associate { it.episodeId to it.progressPercent }.toMutableMap()
                val track = playback.track
                if (track != null && playback.durationMs > 0) {
                    map[track.episodeId] =
                        ((playback.positionMs * 100) / playback.durationMs)
                            .toInt()
                            .coerceIn(0, 100)
                }
                map to track?.episodeId
            }.collect { (map, currentId) ->
                _state.update { it.copy(progressByEpisode = map, currentEpisodeId = currentId) }
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

    fun setSkipIntro(seconds: Int) {
        saveSettings(_state.value.settings.copy(skipIntroSeconds = seconds.coerceIn(0, 600)))
    }

    fun setSkipOutro(seconds: Int) {
        saveSettings(_state.value.settings.copy(skipOutroSeconds = seconds.coerceIn(0, 600)))
    }

    fun toggleAutoQueue() {
        val current = _state.value.settings
        saveSettings(current.copy(autoQueueNew = !current.autoQueueNew))
    }

    fun toggleAutoDownload() {
        val current = _state.value.settings
        saveSettings(current.copy(autoDownload = !current.autoDownload))
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
                    current.copy(loadingMore = false, paginationError = true)
                }
            }
        }
    }

    private suspend fun refreshFirstPage(id: String) {
        when (val result = podcasts.refreshEpisodesIncrementally(id, limit = PAGE_SIZE)) {
            is DataResult.Success -> {
                _state.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        episodes = result.data,
                        endReached = result.data.size < PAGE_SIZE,
                    )
                }
                if (library.podcastSettingsSnapshot(id).autoQueueNew) {
                    result.data.firstOrNull { it.id !in _state.value.completedIds }
                        ?.let(::trackFor)
                        ?.let { queue.addToEnd(it) }
                }
            }

            is DataResult.Failure -> _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = if (it.episodes.isEmpty()) result.error else null,
                )
            }
        }
    }

    fun retryPagination() {
        if (_state.value.loadingMore) return
        _state.update { it.copy(paginationError = false) }
        loadMore()
    }

    companion object {
        const val ARG_PODCAST_ID = "podcastId"
        const val ARG_FEED_URL = "feedUrl"
        private const val PAGE_SIZE = 50
    }
}
