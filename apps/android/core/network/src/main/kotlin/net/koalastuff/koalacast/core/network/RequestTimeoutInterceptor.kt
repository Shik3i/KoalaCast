package net.koalastuff.koalacast.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** OPML can resolve hundreds of feeds; only that endpoint receives the long budget. */
@Singleton
class RequestTimeoutInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(LONG_REQUEST_HEADER) != null) {
            chain.call().timeout().timeout(4, TimeUnit.MINUTES)
        }
        return chain.proceed(
            request.newBuilder().removeHeader(LONG_REQUEST_HEADER).build(),
        )
    }

    companion object {
        const val LONG_REQUEST_HEADER = "X-KoalaCast-Long-Request"
    }
}
