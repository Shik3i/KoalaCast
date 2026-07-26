package net.koalastuff.koalacast.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.koalastuff.koalacast.core.data.repository.LibraryRepository
import net.koalastuff.koalacast.core.data.repository.ProgressRepository
import net.koalastuff.koalacast.core.model.ListeningSession
import net.koalastuff.koalacast.core.model.PlaybackProgress
import java.time.ZonedDateTime
import javax.inject.Inject

data class ProfileUiState(
    val range: StatsRange = StatsRange.YEAR,
    val sessions: List<ListeningSession> = emptyList(),
    val history: List<PlaybackProgress> = emptyList(),
    val subscriptionCount: Int = 0,
) {
    private val floor get() = rangeFloor(range, ZonedDateTime.now())
    val filteredSessions get() = sessions.filter { it.startedAtMs >= floor }
    val filteredHistory get() = history.filter { it.lastPlayedAtMs >= floor }
    val stats get() = summarizeListening(filteredSessions, filteredHistory)
    val firstListeningAtMs get() = sessions.firstOrNull()?.startedAtMs
    val touchedShows get() = sessions.map(ListeningSession::podcastId).toSet().size
    val averagePerActiveDayMs
        get() = if (stats.activeDays > 0) stats.totalWallMs / stats.activeDays else 0
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    progress: ProgressRepository,
    library: LibraryRepository,
) : ViewModel() {

    private val range = MutableStateFlow(StatsRange.YEAR)

    val state: StateFlow<ProfileUiState> = combine(
        progress.listeningHistory,
        progress.allProgress,
        library.allSubscriptions,
        range,
    ) { sessions, history, subscriptions, selectedRange ->
        ProfileUiState(
            range = selectedRange,
            sessions = sessions,
            history = history,
            subscriptionCount = subscriptions.size,
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
