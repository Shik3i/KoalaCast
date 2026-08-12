package net.koalastuff.koalacast.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.repository.DownloadRepository
import net.koalastuff.koalacast.core.model.DownloadState
import net.koalastuff.koalacast.core.model.EpisodeDownload
import net.koalastuff.koalacast.core.player.PlayerConnection
import net.koalastuff.koalacast.core.model.isAllowedByExplicitPreference

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    private val preferences: PreferencesRepository,
    private val player: PlayerConnection,
) : ViewModel() {
    val items = combine(downloads.downloads, preferences.preferences) { items, prefs ->
        items.filter { it.track.explicit.isAllowedByExplicitPreference(prefs.allowExplicitContent) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun play(item: EpisodeDownload) = player.play(item.track)

    fun retry(item: EpisodeDownload) {
        viewModelScope.launch {
            val prefs = preferences.preferences.first()
            downloads.enqueue(
                item.track,
                wifiOnly = prefs.downloadWifiOnly,
                concurrency = prefs.downloadConcurrency,
                storage = prefs.downloadStorage,
                treeUri = prefs.downloadTreeUri,
                budgetBytes = prefs.downloadBudgetBytes,
            )
        }
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
