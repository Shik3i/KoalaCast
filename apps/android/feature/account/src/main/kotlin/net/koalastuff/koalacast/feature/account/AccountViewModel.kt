package net.koalastuff.koalacast.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.repository.AccountRepository
import net.koalastuff.koalacast.core.data.repository.SyncRepository
import net.koalastuff.koalacast.core.model.Account
import net.koalastuff.koalacast.core.model.AccountSession
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.OpmlReport
import net.koalastuff.koalacast.core.model.SyncStatus
import javax.inject.Inject

data class AccountUiState(
    val account: Account? = null,
    val syncStatus: SyncStatus = SyncStatus.OFF,
    val lastSyncedAtMs: Long? = null,
    /** Why the last sync failed. Shown verbatim: it exists to be reported. */
    val syncError: String? = null,
    val username: String = "",
    val password: String = "",
    val recoveryCodeInput: String = "",
    val newPassword: String = "",
    val recoveryCodeDisplay: String? = null,
    val busy: Boolean = false,
    val error: DataError? = null,
    val notice: String? = null,
    val sessions: List<AccountSession> = emptyList(),
    val globalStatsOptIn: Boolean = false,
    val opmlReport: OpmlReport? = null,
    val opmlExport: String? = null,
    val opmlImporting: Boolean = false,
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val sync: SyncRepository,
) : ViewModel() {
    private val form = MutableStateFlow(AccountUiState())

    val state: StateFlow<AccountUiState> = combine(
        form,
        accounts.account,
        sync.status,
        sync.lastSyncedAt,
        sync.lastSyncError,
    ) { local, account, syncStatus, lastSyncedAt, syncError ->
        local.copy(
            account = account,
            syncStatus = syncStatus,
            lastSyncedAtMs = lastSyncedAt,
            syncError = syncError,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AccountUiState(),
    )

    init {
        if (state.value.account != null) refreshAccount()
        viewModelScope.launch {
            accounts.account.collect { if (it != null) refreshAccount() }
        }
    }

    fun setUsername(value: String) = form.update { it.copy(username = value) }
    fun setPassword(value: String) = form.update { it.copy(password = value) }
    fun setRecoveryCode(value: String) = form.update { it.copy(recoveryCodeInput = value) }
    fun setNewPassword(value: String) = form.update { it.copy(newPassword = value) }
    fun confirmRecoveryCodeSaved() = form.update { it.copy(recoveryCodeDisplay = null) }

    fun register() = launchAction {
        when (val result = accounts.register(state.value.username, state.value.password)) {
            is DataResult.Failure -> fail(result.error)
            is DataResult.Success -> {
                form.update { it.copy(recoveryCodeDisplay = result.data) }
                when (val login = accounts.login(state.value.username, state.value.password)) {
                    is DataResult.Failure -> fail(login.error)
                    is DataResult.Success -> {
                        sync.syncNow()
                        notice("account_created")
                    }
                }
            }
        }
    }

    fun login() = launchAction {
        when (val result = accounts.login(state.value.username, state.value.password)) {
            is DataResult.Failure -> fail(result.error)
            is DataResult.Success -> {
                sync.syncNow()
                notice("signed_in")
            }
        }
    }

    fun recover() = launchAction {
        when (
            val result = accounts.recover(
                state.value.username,
                state.value.recoveryCodeInput,
                state.value.newPassword,
            )
        ) {
            is DataResult.Failure -> fail(result.error)
            is DataResult.Success -> notice("password_changed")
        }
    }

    fun logout() = launchAction {
        accounts.logout()
        sync.signedOut()
        form.value = AccountUiState(notice = "signed_out")
    }

    fun syncNow() = viewModelScope.launch { sync.syncNow() }

    fun revokeSession(id: String) = launchAction {
        when (val result = accounts.revokeSession(id)) {
            is DataResult.Failure -> fail(result.error)
            is DataResult.Success -> {
                form.update { it.copy(sessions = it.sessions.filterNot { session -> session.id == id }) }
                notice("session_revoked")
            }
        }
    }

    fun setGlobalStats(enabled: Boolean) = launchAction {
        when (val result = accounts.setGlobalStatsPreference(enabled)) {
            is DataResult.Failure -> fail(result.error)
            is DataResult.Success -> {
                form.update { it.copy(globalStatsOptIn = result.data) }
                // Consent alone cannot make local listening visible to the
                // aggregate. Upload existing sessions immediately so opening
                // Community next does not show an apparently contradictory zero.
                if (result.data) sync.syncNow()
            }
        }
    }

    fun beginOpmlImport() {
        form.update {
            it.copy(
                busy = true,
                error = null,
                notice = null,
                opmlReport = null,
                opmlImporting = true,
            )
        }
    }

    fun importOpml(xml: String) = viewModelScope.launch {
        try {
            when (val result = accounts.importOpml(xml)) {
                is DataResult.Failure -> fail(result.error)
                is DataResult.Success -> form.update { it.copy(opmlReport = result.data) }
            }
        } finally {
            finishOpmlImport()
        }
    }

    fun importFailed(throwable: Throwable) {
        fail(DataError.Malformed("OPML: ${throwable.message ?: "failed to read file"}"))
        finishOpmlImport()
    }

    fun prepareOpmlExport() = launchAction {
        when (val result = accounts.exportOpml()) {
            is DataResult.Failure -> fail(result.error)
            is DataResult.Success -> form.update { it.copy(opmlExport = result.data) }
        }
    }

    fun consumeOpmlExport() = form.update { it.copy(opmlExport = null) }

    private fun refreshAccount() = viewModelScope.launch {
        val sessions = accounts.sessions()
        val preference = accounts.globalStatsPreference()
        form.update {
            it.copy(
                sessions = (sessions as? DataResult.Success)?.data ?: it.sessions,
                globalStatsOptIn = (preference as? DataResult.Success)?.data ?: it.globalStatsOptIn,
            )
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            form.update { it.copy(busy = true, error = null, notice = null) }
            try {
                block()
            } finally {
                form.update { it.copy(busy = false) }
            }
        }
    }

    private fun fail(error: DataError) {
        form.update { it.copy(error = error) }
    }

    private fun notice(value: String) {
        form.update { it.copy(notice = value) }
    }

    private fun finishOpmlImport() {
        form.update { it.copy(busy = false, opmlImporting = false) }
    }
}
