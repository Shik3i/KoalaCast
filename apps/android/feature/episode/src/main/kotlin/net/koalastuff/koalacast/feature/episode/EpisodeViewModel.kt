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
    val chaptersExpanded: Boolean = false,
    val chaptersLoading: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    val chaptersError: Boolean = false,
    val downloadState: DownloadState? = null,
)

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
        load()
        observeLocalState()
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
                LocalState(favorite, episodeId in queued, episodeId in completed, download?.state)
            }.collect { local ->
                _state.update {
                    it.copy(
                        isFavorite = local.favorite,
                        isQueued = local.queued,
                        isPlayed = local.played,
                        downloadState = local.downloadState,
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
    )

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
                null, DownloadState.PAUSED, DownloadState.FAILED -> downloads.enqueue(
                    track,
                    wifiOnly = preferences.preferences.first().downloadWifiOnly,
                )
                DownloadState.QUEUED, DownloadState.DOWNLOADING -> downloads.pause(track.episodeId)
                DownloadState.DONE -> downloads.remove(track.episodeId)
            }
        }
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
            _state.update { it.copy(chaptersLoading = true, chaptersError = false) }
            when (val result = podcasts.chapters(url)) {
                is DataResult.Success -> _state.update {
                    it.copy(chaptersLoading = false, chapters = result.data)
                }
                is DataResult.Failure -> _state.update {
                    it.copy(chaptersLoading = false, chaptersError = true)
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
            player.play(track, resume = false)
        }
        player.seekTo(chapter.startMs)
    }

    fun toggleTranscript() {
        val expanded = !_state.value.transcriptExpanded
        _state.update { it.copy(transcriptExpanded = expanded) }
        if (expanded && _state.value.transcript.isBlank() && !_state.value.transcriptLoading) {
            loadTranscript()
        }
    }

    private fun loadTranscript() {
        viewModelScope.launch {
            _state.update { it.copy(transcriptLoading = true, transcriptError = false) }
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
                    it.copy(transcriptLoading = false, transcriptError = true)
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
