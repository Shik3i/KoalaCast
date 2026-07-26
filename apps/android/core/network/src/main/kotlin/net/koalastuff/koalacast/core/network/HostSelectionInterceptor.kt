package net.koalastuff.koalacast.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the origin the listener picked. Implemented in `core:data` on top of
 * DataStore; kept as an interface here so the network layer stays storage-agnostic.
 */
interface BaseUrlProvider {
    /** The current server origin, or `null` while none is configured. */
    fun current(): HttpUrl?
}

/**
 * KoalaCast is self-hostable, so there is no compile-time base URL. Retrofit is built
 * against a placeholder and every request is re-pointed here at call time — which also
 * means switching servers takes effect on the very next request, with no rebuild of
 * the Retrofit instance and no stale clients hanging around.
 *
 * A request carrying [ABSOLUTE_HEADER] is left alone; that is how server validation
 * probes a candidate URL that has not been saved yet.
 */
@Singleton
class HostSelectionInterceptor @Inject constructor(
    private val baseUrlProvider: BaseUrlProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.header(ABSOLUTE_HEADER) != null) {
            return chain.proceed(request.newBuilder().removeHeader(ABSOLUTE_HEADER).build())
        }

        val base = baseUrlProvider.current()
            ?: throw IOException("No KoalaCast server configured")

        // A self-hosted instance may live under a path prefix
        // (https://example.org/koalacast), so the base path is prepended rather
        // than replaced. Query and fragment come from the original request.
        val prefix = base.encodedPathSegments.filter { it.isNotEmpty() }
        val encodedPath = if (prefix.isEmpty()) {
            request.url.encodedPath
        } else {
            "/" + prefix.joinToString("/") + request.url.encodedPath
        }

        val rewritten = request.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .encodedPath(encodedPath)
            .build()

        return chain.proceed(request.newBuilder().url(rewritten).build())
    }

    companion object {
        const val ABSOLUTE_HEADER = "X-KoalaCast-Absolute-Url"
    }
}
