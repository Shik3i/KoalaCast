package net.koalastuff.koalacast.core.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncCoordinator @Inject constructor(
    private val accounts: SecureAccountStore,
    private val sync: SyncRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            accounts.account.collectLatest { account ->
                if (account == null) {
                    sync.signedOut()
                    return@collectLatest
                }
                while (true) {
                    sync.syncNow()
                    delay(INTERVAL_MS)
                }
            }
        }
    }

    private companion object {
        const val INTERVAL_MS = 45_000L
    }
}
