package net.koalastuff.koalacast.core.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

fun interface AuthTokenProvider {
    fun token(): String?
}

enum class AuthPolicy {
    NO_AUTH,
}

@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenProvider: AuthTokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(AuthPolicy::class.java) == AuthPolicy.NO_AUTH) {
            return chain.proceed(request)
        }
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
