package net.koalastuff.koalacast.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AppReadiness @Inject constructor() {
    private val initialized = CompletableDeferred<Unit>()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    suspend fun await() = initialized.await()

    fun markReady() {
        if (initialized.complete(Unit)) _ready.value = true
    }
}
