package net.koalastuff.koalacast.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Build-variant-aware transport policy shared by URL validation and every OkHttp call. */
object TransportSecurityPolicy {
    val allowsDebugCleartext: Boolean
        get() = BuildConfig.DEBUG

    fun permits(url: HttpUrl, debugBuild: Boolean = BuildConfig.DEBUG): Boolean =
        when (url.scheme.lowercase()) {
        "https" -> true
        "http" -> BuildVariantCleartextPolicy.permits(url.host, debugBuild)
        else -> false
    }
}

/** Last application-level guard before OkHttp opens a socket or follows a redirect. */
@Singleton
class TransportSecurityInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!TransportSecurityPolicy.permits(request.url)) {
            throw IOException("Cleartext HTTP is not permitted for ${request.url.host}")
        }
        return chain.proceed(request)
    }
}
