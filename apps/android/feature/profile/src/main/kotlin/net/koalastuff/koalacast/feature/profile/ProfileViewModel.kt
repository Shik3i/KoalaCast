package net.koalastuff.koalacast.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.repository.AccountRepository
import net.koalastuff.koalacast.core.data.repository.LibraryRepository
import net.koalastuff.koalacast.core.data.repository.ProgressRepository
import net.koalastuff.koalacast.core.data.repository.SyncRepository
import net.koalastuff.koalacast.core.model.ListeningSession
import net.koalastuff.koalacast.core.model.PlaybackProgress
import java.time.ZonedDateTime
import javax.inject.Inject

data class ProfileUiState(
    val range: StatsRange = StatsRange.YEAR,
    val sessions: List<ListeningSession> = emptyList(),
    val history: List<PlaybackProgress> = emptyList(),
    val subscriptionCount: Int = 0,
    val stats: ListeningAnalytics = ListeningAnalytics(),
    val touchedShows: Int = 0,
    val averagePerActiveDayMs: Long = 0,
    /** Null while signed out. The header says so rather than staying silent. */
    val accountName: String? = null,
) {
    val firstListeningAtMs get() = sessions.firstOrNull()?.startedAtMs
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    progress: ProgressRepository,
    library: LibraryRepository,
    accounts: AccountRepository,
    private val sync: SyncRepository,
) : ViewModel() {

    private val range = MutableStateFlow(StatsRange.YEAR)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /**
     * Pull-to-refresh. Statistics are computed from local listening sessions, so
     * the only thing that can be missing is what another device has not handed
     * over yet.
     */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                sync.syncNow()
            } finally {
                _refreshing.value = false
            }
        }
    }

    val state: StateFlow<ProfileUiState> = combine(
        progress.listeningHistory,
        progress.recentHistory,
        library.allSubscriptions,
        range,
        accounts.account,
    ) { sessions, history, subscriptions, selectedRange, account ->
        val floor = rangeFloor(selectedRange, ZonedDateTime.now())
        val stats = summarizeListening(
            sessions.filter { it.startedAtMs >= floor },
            history.filter { it.lastPlayedAtMs >= floor },
        )
        ProfileUiState(
            range = selectedRange,
            sessions = sessions,
            history = history,
            subscriptionCount = subscriptions.size,
            stats = stats,
            touchedShows = sessions.mapTo(mutableSetOf(), ListeningSession::podcastId).size,
            averagePerActiveDayMs =
                if (stats.activeDays > 0) stats.totalWallMs / stats.activeDays else 0,
            accountName = account?.username,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileUiState(),
    )

    fun setRange(value: StatsRange) {
        range.value = value
    }
}
