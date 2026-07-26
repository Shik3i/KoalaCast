package net.koalastuff.koalacast.core.data.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.di.ApplicationScope
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cover art is the one thing that would otherwise expose a listener's IP to every
 * publisher CDN and to Apple. Routing it through the configured KoalaCast instance
 * — which already runs a downscaling image cache — keeps the browsing pattern
 * between the listener and the server they chose. The listener can turn it off in
 * Settings, in which case artwork is fetched straight from its origin.
 *
 * Audio is never proxied: enclosures always stream from the publisher.
 */
@Singleton
class ArtworkUrls @Inject constructor(
    preferences: PreferencesRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    @Volatile
    private var serverUrl: String = ""

    @Volatile
    private var proxyEnabled: Boolean = true

    init {
        scope.launch {
            preferences.preferences.collect {
                serverUrl = it.serverUrl.trimEnd('/')
                proxyEnabled = it.proxyImages
            }
        }
    }

    /**
     * @param widthPx pass the width the image is drawn at so the server downscales
     *   instead of shipping a 3000px original to a 56dp tile.
     */
    fun forArtwork(rawUrl: String?, widthPx: Int? = null): String? {
        val source = rawUrl?.trim().orEmpty()
        if (source.isEmpty()) return null
        if (!proxyEnabled) return source

        val base = serverUrl.toHttpUrlOrNull() ?: return source
        if (source.toHttpUrlOrNull() == null) return source

        return base.newBuilder()
            .addPathSegments("api/v1/proxy/image")
            .addQueryParameter("url", source)
            .apply { widthPx?.let { addQueryParameter("w", it.toString()) } }
            .build()
            .toString()
    }
}
