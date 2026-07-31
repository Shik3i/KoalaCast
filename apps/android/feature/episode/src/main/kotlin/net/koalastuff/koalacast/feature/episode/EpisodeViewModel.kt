package net.koalastuff.koalacast.feature.episode

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
import net.koalastuff.koalacast.core.data.repository.DownloadRepository
import net.koalastuff.koalacast.core.data.repository.PodcastRepository
import net.koalastuff.koalacast.core.data.repository.ContentTtl
import net.koalastuff.koalacast.core.data.repository.ProgressRepository
import net.koalastuff.koalacast.core.data.repository.QueueRepository
import net.koalastuff.koalacast.core.player.PlayerConnection
import net.koalastuff.koalacast.core.model.Chapter
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.DownloadState
import net.koalastuff.koalacast.core.model.Podcast
import net.koalastuff.koalacast.core.model.Track
import net.koalastuff.koalacast.core.model.TimeBookmark
import javax.inject.Inject

data class EpisodeUiState(
    val loading: Boolean = true,
    val error: DataError? = null,
    val serverUrl: String = "",
    val episode: Episode? = null,
    /** Loaded after the episode, purely for the artwork and show name in the header. */
    val podcast: Podcast? = null,
    val isFavorite: Boolean = false,
    val isQueued: Boolean = false,
    val isPlayed: Boolean = false,
    val transcriptExpanded: Boolean = false,
    val transcriptLoading: Boolean = false,
    val transcript: String = "",
    val transcriptError: Boolean = false,
    val transcriptQuery: String = "",
    val chaptersExpanded: Boolean = false,
    val chaptersLoading: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    val chaptersError: Boolean = false,
    val downloadState: DownloadState? = null,
    /** 0-100 while a download runs, so the control can show it rather than a colour. */
    val downloadPercent: Int = 0,
    val timeBookmarks: List<TimeBookmark> = emptyList(),
    val bookmarkPositionMs: Long = 0,
) {
    val visibleTranscript: String
        get() {
            val query = transcriptQuery.trim()
            if (query.isEmpty()) return transcript
            return transcript.lineSequence()
                .filter { it.contains(query, ignoreCase = true) }
                .joinToString("\n")
        }
}

@HiltViewModel
class EpisodeViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val preferences: PreferencesRepository,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val queue: QueueRepository,
    private val progress: ProgressRepository,
    private val player: PlayerConnection,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val episodeId: String = savedStateHandle.get<String>(ARG_EPISODE_ID).orEmpty()

    private val _state = MutableStateFlow(EpisodeUiState())
    val state: StateFlow<EpisodeUiState> = _state.asStateFlow()

    init {
        load(force = false)
        observeLocalState()
        observeBookmarks()
    }

    private fun observeBookmarks() {
        viewModelScope.launch {
            combine(
                library.timeBookmarks(episodeId),
                player.state,
                progress.progress(episodeId),
            ) { bookmarks, playback, saved ->
                val position = if (playback.track?.episodeId == episodeId) {
                    playback.positionMs
                } else {
                    saved?.positionMs ?: 0
                }
                bookmarks to position
            }.collect { (bookmarks, position) ->
                _state.update {
                    it.copy(timeBookmarks = bookmarks, bookmarkPositionMs = position)
                }
            }
        }
    }

    /**
     * Saved / queued / played come from Room, so the buttons stay honest after a
     * change made elsewhere in the app — and with no network at all.
     */
    private fun observeLocalState() {
        viewModelScope.launch {
            combine(
                library.isFavorite(episodeId),
                queue.queuedEpisodeIds,
                progress.completedEpisodeIds,
                downloads.download(episodeId),
            ) { favorite, queued, completed, download ->
                LocalState(
                    favorite,
                    episodeId in queued,
                    episodeId in completed,
                    download?.state,
                    download?.progressPercent ?: 0,
                )
            }.collect { local ->
                _state.update {
                    it.copy(
                        isFavorite = local.favorite,
                        isQueued = local.queued,
                        isPlayed = local.played,
                        downloadState = local.downloadState,
                        downloadPercent = local.downloadPercent,
                    )
                }
            }
        }
    }

    private data class LocalState(
        val favorite: Boolean,
        val queued: Boolean,
        val played: Boolean,
        val downloadState: DownloadState?,
        val downloadPercent: Int,
    )

    fun retry() = load(force = true)

    private fun load(force: Boolean) {
        viewModelScope.launch {
            val cachedEpisode = podcasts.cachedEpisode(episodeId)
            if (cachedEpisode != null) {
                val cachedPodcast = cachedEpisode.value.podcastId
                    .takeIf(String::isNotBlank)
                    ?.let { podcasts.cachedPodcast(it) }
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        serverUrl = preferences.serverUrl.first(),
                        episode = cachedEpisode.value,
                        podcast = cachedPodcast?.value,
                    )
                }
                if (
                    !force &&
                    podcasts.isFresh(cachedEpisode, ContentTtl.EPISODE) &&
                    (cachedPodcast == null || podcasts.isFresh(cachedPodcast, ContentTtl.PODCAST))
                ) {
                    return@launch
                }
            }
            _state.update {
                it.copy(
                    loading = it.episode == null,
                    error = null,
                    serverUrl = preferences.serverUrl.first(),
                )
            }

            when (val result = podcasts.episode(episodeId)) {
                is DataResult.Success -> {
                    _state.update { it.copy(loading = false, episode = result.data) }
                    loadShow(result.data.podcastId)
                }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = if (it.episode == null) result.error else null,
                        )
                    }
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

    fun play() {
        track()?.let(player::play)
    }

    fun toggleFavorite() {
        val track = track() ?: return
        viewModelScope.launch {
            if (_state.value.isFavorite) library.removeFavorite(track.episodeId)
            else library.addFavorite(track)
        }
    }

    fun toggleQueue() {
        val track = track() ?: return
        viewModelScope.launch {
            if (_state.value.isQueued) queue.remove(track.episodeId) else queue.addToEnd(track)
        }
    }

    fun togglePlayed() {
        val track = track() ?: return
        viewModelScope.launch { progress.setPlayed(track, played = !_state.value.isPlayed) }
    }

    fun toggleDownload() {
        val track = track() ?: return
        viewModelScope.launch {
            when (_state.value.downloadState) {
                null, DownloadState.PAUSED, DownloadState.FAILED -> {
                    val prefs = preferences.preferences.first()
                    downloads.enqueue(
                        track,
                        wifiOnly = prefs.downloadWifiOnly,
                        concurrency = prefs.downloadConcurrency,
                        storage = prefs.downloadStorage,
                        treeUri = prefs.downloadTreeUri,
                        budgetBytes = prefs.downloadBudgetBytes,
                    )
                }
                DownloadState.QUEUED, DownloadState.DOWNLOADING -> downloads.pause(track.episodeId)
                DownloadState.DONE -> downloads.remove(track.episodeId)
            }
        }
    }

    fun addTimeBookmark() {
        viewModelScope.launch {
            library.addTimeBookmark(episodeId, _state.value.bookmarkPositionMs)
        }
    }

    fun removeTimeBookmark(id: String) {
        viewModelScope.launch { library.removeTimeBookmark(id) }
    }

    fun playTimeBookmark(bookmark: TimeBookmark) {
        val track = track() ?: return
        if (player.state.value.track?.episodeId == track.episodeId) {
            player.seekTo(bookmark.positionMs)
        } else {
            player.playAt(track, bookmark.positionMs)
        }
    }

    fun handoffUrl(): String? {
        val state = _state.value
        if (state.episode == null || state.serverUrl.isBlank()) return null
        val base = state.serverUrl.trimEnd('/')
        val seconds = state.bookmarkPositionMs.coerceAtLeast(0) / 1_000
        return "$base/episode/${android.net.Uri.encode(episodeId)}?t=$seconds"
    }

    fun toggleChapters() {
        val expanded = !_state.value.chaptersExpanded
        _state.update { it.copy(chaptersExpanded = expanded) }
        if (expanded && _state.value.chapters.isEmpty() && !_state.value.chaptersLoading) {
            loadChapters()
        }
    }

    private fun loadChapters() {
        val url = _state.value.episode?.chaptersUrl.orEmpty()
        if (url.isBlank()) return
        viewModelScope.launch {
            val cached = podcasts.cachedChapters(url)
            if (cached != null) {
                _state.update {
                    it.copy(chaptersLoading = true, chaptersError = false, chapters = cached.value)
                }
                if (podcasts.isFresh(cached, ContentTtl.AUXILIARY)) {
                    _state.update { it.copy(chaptersLoading = false) }
                    return@launch
                }
            } else {
                _state.update { it.copy(chaptersLoading = true, chaptersError = false) }
            }
            when (val result = podcasts.chapters(url)) {
                is DataResult.Success -> _state.update {
                    it.copy(chaptersLoading = false, chapters = result.data)
                }
                is DataResult.Failure -> _state.update {
                    it.copy(
                        chaptersLoading = false,
                        chaptersError = it.chapters.isEmpty(),
                    )
                }
            }
        }
    }

    /** Jumps to a chapter, starting the episode first when it is not the one playing. */
    fun seekToChapter(chapter: Chapter) {
        val track = track() ?: return
        // `resume = false` so a fresh start ignores any stored position — the
        // listener asked for this chapter, not for where they left off.
        if (player.state.value.track?.episodeId != track.episodeId) {
            player.playAt(track, chapter.startMs)
        } else {
            player.seekTo(chapter.startMs)
        }
    }

    fun toggleTranscript() {
        val expanded = !_state.value.transcriptExpanded
        _state.update { it.copy(transcriptExpanded = expanded) }
        if (expanded && _state.value.transcript.isBlank() && !_state.value.transcriptLoading) {
            loadTranscript()
        }
    }

    fun setTranscriptQuery(query: String) {
        _state.update { it.copy(transcriptQuery = query) }
    }

    private fun loadTranscript() {
        viewModelScope.launch {
            val cached = podcasts.cachedTranscript(episodeId)
            if (cached != null) {
                _state.update {
                    it.copy(
                        transcriptLoading = true,
                        transcriptError = false,
                        transcript = TranscriptFormatter.format(
                            type = cached.value.first,
                            content = cached.value.second,
                        ),
                    )
                }
                if (podcasts.isFresh(cached, ContentTtl.AUXILIARY)) {
                    _state.update { it.copy(transcriptLoading = false) }
                    return@launch
                }
            } else {
                _state.update { it.copy(transcriptLoading = true, transcriptError = false) }
            }
            when (val result = podcasts.transcript(episodeId)) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        transcriptLoading = false,
                        transcript = TranscriptFormatter.format(
                            type = result.data.first,
                            content = result.data.second,
                        ),
                    )
                }
                is DataResult.Failure -> _state.update {
                    it.copy(
                        transcriptLoading = false,
                        transcriptError = it.transcript.isBlank(),
                    )
                }
            }
        }
    }

    /**
     * The show may still be loading — its title and artwork are a nicety, not a
     * precondition, so a save or a queue never has to wait for it.
     */
    private fun track(): Track? {
        val episode = _state.value.episode ?: return null
        val podcast = _state.value.podcast
        return episode.toTrack(
            podcastTitle = podcast?.title.orEmpty(),
            fallbackArtworkUrl = podcast?.artworkUrl.orEmpty(),
        )
    }

    companion object {
        const val ARG_EPISODE_ID = "episodeId"
    }
}
