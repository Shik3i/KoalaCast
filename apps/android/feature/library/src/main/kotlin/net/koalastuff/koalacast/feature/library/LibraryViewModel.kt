package net.koalastuff.koalacast.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.repository.LibraryRepository
import net.koalastuff.koalacast.core.data.repository.AccountRepository
import net.koalastuff.koalacast.core.data.repository.ProgressRepository
import net.koalastuff.koalacast.core.data.repository.QueueRepository
import net.koalastuff.koalacast.core.data.repository.NamedQueueRepository
import net.koalastuff.koalacast.core.data.repository.SyncRepository
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.player.PlayerConnection
import net.koalastuff.koalacast.core.model.Favorite
import net.koalastuff.koalacast.core.model.PlaybackProgress
import net.koalastuff.koalacast.core.model.QueueEntry
import net.koalastuff.koalacast.core.model.Subscription
import net.koalastuff.koalacast.core.model.Track
import net.koalastuff.koalacast.core.model.NamedQueue
import net.koalastuff.koalacast.core.model.isAllowedByExplicitPreference
import javax.inject.Inject

enum class LibraryTab { SUBSCRIPTIONS, IN_PROGRESS, QUEUE, FAVORITES }

data class LibraryUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val inProgress: List<PlaybackProgress> = emptyList(),
    val queue: List<QueueEntry> = emptyList(),
    val favorites: List<Favorite> = emptyList(),
    val namedQueues: List<NamedQueue> = emptyList(),
) {
    /** Total remaining runtime of the queue at the given speed. */
    fun queueRuntimeMs(speed: Float): Long =
        queue.sumOf { (it.track.durationMs / speed.coerceAtLeast(0.1f)).toLong() }
}

/**
 * Everything here reads from Room, so the library is fully usable with the
 * network off and with no account — which is the whole point of local-first.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val account: AccountRepository,
    private val library: LibraryRepository,
    private val queue: QueueRepository,
    private val namedQueues: NamedQueueRepository,
    private val progress: ProgressRepository,
    private val player: PlayerConnection,
    private val preferences: PreferencesRepository,
    private val sync: SyncRepository,
) : ViewModel() {

    init {
        viewModelScope.launch { account.resolvePendingSubscriptions() }
    }

    private val _tab = MutableStateFlow(LibraryTab.SUBSCRIPTIONS)
    val tab: StateFlow<LibraryTab> = _tab

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /**
     * Pull-to-refresh. Every list here is fed from Room and updates itself, so
     * the only thing a listener can actually be waiting for is the account
     * catching up with another device — which is exactly what a pull means on
     * this screen.
     */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                account.resolvePendingSubscriptions()
                sync.syncNow()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private val storedState = combine(
        library.allSubscriptions,
        progress.inProgress,
        queue.entries,
        library.allFavorites,
        namedQueues.all,
    ) { subscriptions, inProgress, queueEntries, favorites, savedQueues ->
        LibraryUiState(
            subscriptions = subscriptions,
            inProgress = inProgress,
            queue = queueEntries,
            favorites = favorites,
            namedQueues = savedQueues,
        )
    }

    val state: StateFlow<LibraryUiState> = combine(
        storedState,
        preferences.preferences,
    ) { stored, prefs ->
        stored.copy(
            inProgress = stored.inProgress.filter {
                it.track?.explicit.isAllowedByExplicitPreference(prefs.allowExplicitContent)
            },
            queue = stored.queue.filter {
                it.track.explicit.isAllowedByExplicitPreference(prefs.allowExplicitContent)
            },
            favorites = stored.favorites.filter {
                it.track?.explicit.isAllowedByExplicitPreference(prefs.allowExplicitContent)
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun selectTab(tab: LibraryTab) {
        _tab.value = tab
    }

    /** Resumes from the stored position; the connection looks it up. */
    fun play(track: Track?) {
        track?.takeIf { it.enclosureUrl.isNotBlank() }?.let(player::play)
    }

    fun unsubscribe(podcastId: String) {
        viewModelScope.launch { library.unsubscribe(podcastId) }
    }

    fun setFolder(podcastId: String, folder: String) {
        viewModelScope.launch { library.setFolder(podcastId, folder) }
    }

    fun removeFromQueue(episodeId: String) {
        viewModelScope.launch { queue.remove(episodeId) }
    }

    fun clearQueue() {
        viewModelScope.launch { queue.clear() }
    }

    /** Drag-to-reorder commits the whole sequence, not a pairwise swap. */
    fun reorderQueue(orderedEpisodeIds: List<String>) {
        viewModelScope.launch { queue.reorder(orderedEpisodeIds) }
    }

    fun removeFavorite(episodeId: String) {
        viewModelScope.launch { library.removeFavorite(episodeId) }
    }

    fun saveNamedQueue(name: String) {
        viewModelScope.launch { namedQueues.save(name) }
    }

    fun restoreNamedQueue(id: String) {
        viewModelScope.launch { namedQueues.restore(id) }
    }

    fun deleteNamedQueue(id: String) {
        viewModelScope.launch { namedQueues.delete(id) }
    }
}
