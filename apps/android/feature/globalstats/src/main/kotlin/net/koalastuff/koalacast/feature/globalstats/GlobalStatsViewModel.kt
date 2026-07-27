package net.koalastuff.koalacast.feature.globalstats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.repository.GlobalStatsRepository
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.GlobalStats
import javax.inject.Inject

enum class GlobalRange(val wireName: String) {
    DAYS_90("90days"),
    YEAR("year"),
    ALL("all"),
}

data class GlobalStatsUiState(
    val range: GlobalRange = GlobalRange.YEAR,
    val loading: Boolean = true,
    val error: DataError? = null,
    val stats: GlobalStats? = null,
)

@HiltViewModel
class GlobalStatsViewModel @Inject constructor(
    private val repository: GlobalStatsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(GlobalStatsUiState())
    val state: StateFlow<GlobalStatsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun setRange(range: GlobalRange) {
        if (_state.value.range == range) return
        _state.update { it.copy(range = range) }
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            val requestedRange = _state.value.range
            _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.load(requestedRange.wireName)) {
                is DataResult.Success -> {
                    if (_state.value.range == requestedRange) {
                        _state.update { it.copy(loading = false, stats = result.data) }
                    }
                }
                is DataResult.Failure -> {
                    if (_state.value.range == requestedRange) {
                        _state.update { it.copy(loading = false, error = result.error) }
                    }
                }
            }
        }
    }
}
