package net.koalastuff.koalacast.core.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

fun interface AuthTokenProvider {
    fun token(): String?
}

@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenProvider: AuthTokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokenProvider.token()
        return chain.proceed(
            if (token.isNullOrBlank()) {
                request
            } else {
                request.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            },
        )
    }
}
