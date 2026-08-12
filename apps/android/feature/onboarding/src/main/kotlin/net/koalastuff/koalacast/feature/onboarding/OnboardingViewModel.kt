package net.koalastuff.koalacast.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.prefs.KoalaCastDefaults
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.repository.ServerRepository
import net.koalastuff.koalacast.core.data.server.ServerUrl
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import javax.inject.Inject

data class OnboardingUiState(
    val url: String = KoalaCastDefaults.SERVER_URL,
    val checking: Boolean = false,
    val error: DataError? = null,
    /** True while the typed URL would send traffic unencrypted. */
    val cleartext: Boolean = false,
    val cleartextRejected: Boolean = false,
    val showEmulatorOption: Boolean = ServerUrl.supportsEmulatorLoopback,
    val finished: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val preferences: PreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun onUrlChange(value: String) {
        val rejected = ServerUrl.rejectsCleartext(value)
        _state.update {
            it.copy(
                url = value,
                error = if (rejected) {
                    DataError.Malformed(ServerUrl.CLEARTEXT_REJECTED)
                } else {
                    null
                },
                cleartext = ServerUrl.isCleartext(value) && !rejected,
                cleartextRejected = rejected,
            )
        }
    }

    fun useOfficialInstance() = onUrlChange(KoalaCastDefaults.SERVER_URL)

    fun useEmulatorLoopback() = onUrlChange(KoalaCastDefaults.EMULATOR_LOOPBACK_URL)

    /**
     * A typo has to fail here, not three screens later, so the URL is probed against
     * `/api/v1/healthz` before anything is stored.
     */
    fun confirm() {
        if (_state.value.checking) return
        if (_state.value.cleartextRejected) return
        _state.update { it.copy(checking = true, error = null) }

        viewModelScope.launch {
            when (val result = serverRepository.selectServer(_state.value.url)) {
                is DataResult.Success -> {
                    preferences.setOnboardingComplete(true)
                    _state.update {
                        it.copy(url = result.data, checking = false, finished = true)
                    }
                }

                is DataResult.Failure ->
                    _state.update { it.copy(checking = false, error = result.error) }
            }
        }
    }
}
