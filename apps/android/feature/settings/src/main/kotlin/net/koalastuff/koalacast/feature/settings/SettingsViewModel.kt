package net.koalastuff.koalacast.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.repository.AccountRepository
import net.koalastuff.koalacast.core.data.repository.ServerRepository
import net.koalastuff.koalacast.core.data.repository.DownloadRepository
import net.koalastuff.koalacast.core.data.server.ServerUrl
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DownloadRetention
import net.koalastuff.koalacast.core.model.DownloadStorage
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.PaletteId
import net.koalastuff.koalacast.core.model.StartScreen
import net.koalastuff.koalacast.core.model.ThemeMode
import net.koalastuff.koalacast.core.model.UserPreferences
import javax.inject.Inject

data class SettingsUiState(
    val preferences: UserPreferences? = null,
    /** Null while signed out; drives the account row at the top of the screen. */
    val accountName: String? = null,
    /** The URL being edited, which is only committed once it validates. */
    val serverDraft: String = "",
    val checkingServer: Boolean = false,
    val serverError: DataError? = null,
    val serverSaved: Boolean = false,
    val cleartext: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val serverRepository: ServerRepository,
    private val downloads: DownloadRepository,
    accounts: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            accounts.account.collect { account ->
                _state.update { it.copy(accountName = account?.username) }
            }
        }

        viewModelScope.launch {
            preferences.preferences.collect { prefs ->
                _state.update { current ->
                    current.copy(
                        preferences = prefs,
                        // Do not stomp on what the listener is typing.
                        serverDraft = if (current.preferences == null) {
                            prefs.serverUrl
                        } else {
                            current.serverDraft
                        },
                    )
                }
            }
        }
    }

    fun onServerDraftChange(value: String) {
        _state.update {
            it.copy(
                serverDraft = value,
                serverError = null,
                serverSaved = false,
                cleartext = ServerUrl.isCleartext(value),
            )
        }
    }

    /**
     * Switching servers changes what an account means: sync state is per-server, and
     * a device token issued by one instance is worthless on another. The UI says so
     * next to this action.
     */
    fun saveServer() {
        if (_state.value.checkingServer) return
        _state.update { it.copy(checkingServer = true, serverError = null, serverSaved = false) }

        viewModelScope.launch {
            when (val result = serverRepository.selectServer(_state.value.serverDraft)) {
                is DataResult.Success -> _state.update {
                    it.copy(checkingServer = false, serverDraft = result.data, serverSaved = true)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(checkingServer = false, serverError = result.error)
                }
            }
        }
    }

    fun setSkipSilence(enabled: Boolean) {
        viewModelScope.launch { preferences.setSkipSilence(enabled) }
    }

    fun setVolumeBoost(enabled: Boolean) {
        viewModelScope.launch { preferences.setVolumeBoost(enabled) }
    }

    fun setAutoDownloadCount(count: Int) {
        viewModelScope.launch { preferences.setAutoDownloadCount(count) }
    }

    fun setDownloadRetention(retention: DownloadRetention) {
        viewModelScope.launch { preferences.setDownloadRetention(retention) }
    }

    fun setDownloadConcurrency(value: Int) {
        viewModelScope.launch { preferences.setDownloadConcurrency(value) }
    }

    fun setDownloadBudgetMb(value: Int) {
        viewModelScope.launch {
            val bytes = value.toLong() * 1024 * 1024
            preferences.setDownloadBudgetBytes(bytes)
            downloads.cleanupToBudget(bytes)
        }
    }

    fun setDownloadStorage(storage: DownloadStorage, treeUri: String = "") {
        viewModelScope.launch { preferences.setDownloadStorage(storage, treeUri) }
    }

    fun setPalette(palette: PaletteId) {
        viewModelScope.launch { preferences.setPalette(palette) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun toggleLanguage(code: String) {
        val current = _state.value.preferences?.languages ?: return
        val next = if (code in current) current - code else current + code
        viewModelScope.launch { preferences.setLanguages(next) }
    }

    fun cycleGenre(wireName: String) {
        val prefs = _state.value.preferences ?: return
        val interests = prefs.interests.toMutableSet()
        val hidden = prefs.hiddenGenres.toMutableSet()
        when (wireName) {
            in interests -> {
                interests -= wireName
                hidden += wireName
            }
            in hidden -> hidden -= wireName
            else -> interests += wireName
        }
        viewModelScope.launch { preferences.setGenrePreferences(interests, hidden) }
    }

    fun unhidePodcast(key: String) {
        viewModelScope.launch { preferences.unhidePodcast(key) }
    }

    fun setDefaultInboxMode(mode: InboxMode) {
        viewModelScope.launch { preferences.setDefaultInboxMode(mode) }
    }

    fun setStartScreen(screen: StartScreen) {
        viewModelScope.launch { preferences.setStartScreen(screen) }
    }

    fun setProxyImages(enabled: Boolean) {
        viewModelScope.launch { preferences.setProxyImages(enabled) }
    }

    fun setDownloadWifiOnly(enabled: Boolean) {
        viewModelScope.launch { preferences.setDownloadWifiOnly(enabled) }
    }
}
