package net.koalastuff.koalacast.feature.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import net.koalastuff.koalacast.core.player.PlaybackUiState
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
) : ViewModel() {

    val state: StateFlow<PlaybackUiState> = player.state

    fun togglePlayPause() = player.togglePlayPause()
    fun seekBack() = player.seekBack()
    fun seekForward() = player.seekForward()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun cycleSpeed() = player.cycleSpeed()
    fun setSleepTimer(minutes: Int?, atEpisodeEnd: Boolean = false) =
        player.setSleepTimer(minutes, atEpisodeEnd)
    fun playNextFromQueue() = player.playNextFromQueue()
    fun stop() = player.stop()
}
