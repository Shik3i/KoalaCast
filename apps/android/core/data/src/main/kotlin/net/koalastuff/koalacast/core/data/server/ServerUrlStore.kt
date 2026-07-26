package net.koalastuff.koalacast.core.data.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.koalastuff.koalacast.core.data.di.ApplicationScope
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.network.BaseUrlProvider
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the stored server URL into the OkHttp interceptor, which needs it
 * synchronously on a background thread. The value is cached and kept current by
 * observing DataStore, so a server switch applies to the next request.
 */
@Singleton
class ServerUrlStore @Inject constructor(
    private val preferences: PreferencesRepository,
    @ApplicationScope scope: CoroutineScope,
) : BaseUrlProvider {

    @Volatile
    private var cached: HttpUrl? = null

    init {
        scope.launch {
            preferences.serverUrl.collect { cached = ServerUrl.parse(it) }
        }
    }

    override fun current(): HttpUrl? = cached ?: loadBlocking()

    /**
     * Only reached if a request beats the first DataStore emission — a cold start
     * racing its own first fetch. Runs on an OkHttp dispatcher thread, never the main one.
     */
    private fun loadBlocking(): HttpUrl? =
        runBlocking { ServerUrl.parse(preferences.serverUrl.first()) }.also { cached = it }
}
