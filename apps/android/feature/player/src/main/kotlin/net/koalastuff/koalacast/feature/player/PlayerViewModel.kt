package net.koalastuff.koalacast.feature.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import net.koalastuff.koalacast.core.player.PlaybackUiState
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.repository.PodcastRepository
import net.koalastuff.koalacast.core.model.Chapter
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.player.PlayerConnection
import javax.inject.Inject

/**
 * A thin pass-through: the playback state itself is owned by [PlayerConnection],
 * which outlives every screen. Putting it in a ViewModel would mean the mini
 * player and the full player each held their own idea of what is playing.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlayerConnection,
    private val podcasts: PodcastRepository,
) : ViewModel() {

    val state: StateFlow<PlaybackUiState> = player.state

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    init {
        player.connect()
        // Reload only when the episode changes, not on every position tick.
        viewModelScope.launch {
            player.state
                .map { it.track?.episodeId }
                .distinctUntilChanged()
                .collect { episodeId ->
                    _chapters.value = emptyList()
                    if (episodeId != null) loadChapters(episodeId)
                }
        }
    }

    /**
     * Chapters are optional and the player must not care if they never arrive —
     * a failure here leaves the row hidden, it does not surface an error over
     * something the listener did not ask for.
     */
    private suspend fun loadChapters(episodeId: String) {
        val episode = (podcasts.episode(episodeId) as? DataResult.Success)?.data ?: return
        if (episode.chaptersUrl.isBlank()) return
        val loaded = podcasts.chapters(episode.chaptersUrl)
        if (loaded is DataResult.Success) _chapters.value = loaded.data
    }

    fun togglePlayPause() = player.togglePlayPause()
    fun retry() = player.retry()
    fun seekBack() = player.seekBack()
    fun seekForward() = player.seekForward()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun cycleSpeed() = player.cycleSpeed()
    fun setSleepTimer(minutes: Int?, atEpisodeEnd: Boolean = false, atChapterEnd: Boolean = false) {
        if (!atChapterEnd) {
            player.setSleepTimer(minutes, atEpisodeEnd, atChapterEnd = false)
            return
        }
        val nextChapter = ChapterState.nextStartMs(chapters.value, state.value.positionMs)
        if (nextChapter == null) {
            player.setSleepTimer(minutes = null, atEpisodeEnd = true)
        } else {
            player.setSleepAtPosition(nextChapter)
        }
    }
    fun playNextFromQueue() = player.playNextFromQueue()
    fun stop() = player.stop()
}
