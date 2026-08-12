package net.koalastuff.koalacast.core.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.di.IoDispatcher
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.server.ServerUrl
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.network.KoalaCastApi
import net.koalastuff.koalacast.core.network.apiCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Choosing where the app talks to. Self-hosting is a first-class path, so a candidate
 * URL is always probed before it is stored — a typo should fail in onboarding, not
 * three screens later.
 */
@Singleton
class ServerRepository @Inject constructor(
    private val api: KoalaCastApi,
    private val preferences: PreferencesRepository,
    private val accountStore: SecureAccountStore,
    private val accountData: AccountDataNamespace,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /** Probes `{url}/api/v1/healthz` without touching the stored configuration. */
    suspend fun validate(rawUrl: String): DataResult<String> = withContext(dispatcher) {
        if (ServerUrl.rejectsCleartext(rawUrl)) {
            return@withContext DataResult.Failure(
                DataError.Malformed(ServerUrl.CLEARTEXT_REJECTED),
            )
        }
        val normalised = ServerUrl.normalise(rawUrl)
            ?: return@withContext DataResult.Failure(DataError.Malformed("not a URL"))

        apiCall { api.healthzAt("$normalised/api/v1/healthz") }.let { result ->
            when (result) {
                is DataResult.Failure -> result
                is DataResult.Success -> {
                    if (result.data.status != "ok") {
                        return@withContext DataResult.Failure(
                            DataError.Malformed("unexpected /healthz payload"),
                        )
                    }
                    when (val ready = apiCall { api.readyzAt("$normalised/api/v1/readyz") }) {
                        is DataResult.Failure -> ready
                        is DataResult.Success ->
                            if (
                                ready.data.status == "ready" &&
                                ready.data.database == "connected"
                            ) {
                                DataResult.Success(normalised)
                            } else {
                                DataResult.Failure(
                                    DataError.Malformed("unexpected /readyz payload"),
                                )
                            }
                    }
                }
            }
        }
    }

    /** Validates, then persists. Returns the normalised URL that was stored. */
    suspend fun selectServer(rawUrl: String): DataResult<String> =
        when (val validated = validate(rawUrl)) {
            is DataResult.Failure -> validated
            is DataResult.Success -> {
                val changed = preferences.serverUrl.first() != validated.data
                if (changed) {
                    accountStore.beginAccountTransition()
                    accountData.switchTo(AccountDataNamespace.GUEST_OWNER)
                    accountStore.clear()
                    accountStore.setServerOrigin(validated.data)
                }
                preferences.setServerUrl(validated.data)
                validated
            }
        }
}
