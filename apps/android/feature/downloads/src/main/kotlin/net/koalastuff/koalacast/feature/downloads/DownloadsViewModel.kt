package net.koalastuff.koalacast.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.repository.DownloadRepository
import net.koalastuff.koalacast.core.model.DownloadState
import net.koalastuff.koalacast.core.model.EpisodeDownload
import net.koalastuff.koalacast.core.player.PlayerConnection

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    private val player: PlayerConnection,
) : ViewModel() {
    val items = downloads.downloads.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun play(item: EpisodeDownload) = player.play(item.track)

    fun retry(item: EpisodeDownload) {
        viewModelScope.launch { downloads.enqueue(item.track) }
    }

    fun pause(item: EpisodeDownload) {
        viewModelScope.launch { downloads.pause(item.episodeId) }
    }

    fun remove(item: EpisodeDownload) {
        viewModelScope.launch { downloads.remove(item.episodeId) }
    }

    fun removeAll() {
        viewModelScope.launch { items.value.forEach { downloads.remove(it.episodeId) } }
    }

    fun primary(item: EpisodeDownload) {
        when (item.state) {
            DownloadState.DONE -> play(item)
            DownloadState.DOWNLOADING, DownloadState.QUEUED -> pause(item)
            DownloadState.PAUSED, DownloadState.FAILED -> retry(item)
        }
    }
}
