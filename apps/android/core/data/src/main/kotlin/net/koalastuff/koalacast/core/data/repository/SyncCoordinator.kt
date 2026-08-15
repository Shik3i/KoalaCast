package net.koalastuff.koalacast.core.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
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
    private val foreground = MutableStateFlow(false)

    /**
     * Reported by the application from the activity lifecycle.
     *
     * Returning to the app after listening somewhere else is exactly when a
     * stale library is most visible, and the periodic tick alone made that a
     * coin flip: the process is frozen while it is cached, so the remainder of
     * a 45-second delay is only counted once the app is on screen again. An
     * episode finished in the browser could therefore still be sitting there
     * unplayed for the better part of a minute after opening the app.
     */
    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            accounts.account.collectLatest { account ->
                if (account == null) {
                    sync.signedOut()
                    return@collectLatest
                }
                coroutineScope {
                    // `drop(1)` discards the state at subscription time: the loop
                    // below already syncs once here, so only a real transition
                    // back into the foreground should add a run.
                    launch {
                        foreground.drop(1).collect { visible -> if (visible) sync.syncNow() }
                    }
                    // The tick is deliberately not gated on the foreground.
                    // Playback continues with the screen off, and progress from
                    // that listening has to keep reaching the account.
                    while (true) {
                        sync.syncNow()
                        delay(INTERVAL_MS)
                    }
                }
            }
        }
    }

    private companion object {
        const val INTERVAL_MS = 45_000L
    }
}
