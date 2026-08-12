package net.koalastuff.koalacast.core.network

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModuleTransportTest {
    @Test
    fun `API transport guard runs after host selection and before auth`() {
        val guard = TransportSecurityInterceptor()
        val base = OkHttpClient.Builder()
            .addInterceptor(guard)
            .addNetworkInterceptor(guard)
            .build()
        var tokenRead = false
        val client = NetworkModule.provideApiClient(
            base = base,
            hostSelection = HostSelectionInterceptor(object : BaseUrlProvider {
                override fun current() = "http://192.168.1.10:3000".toHttpUrl()
            }),
            auth = AuthInterceptor {
                tokenRead = true
                "must-not-be-read"
            },
            requestTimeout = RequestTimeoutInterceptor(),
            transportSecurity = guard,
        )

        val error = runCatching {
            client.newCall(
                Request.Builder().url("http://server.invalid/api/v1/sync").build(),
            ).execute().close()
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(tokenRead)
        assertTrue(client.interceptors[0] is HostSelectionInterceptor)
        assertTrue(client.interceptors[1] is TransportSecurityInterceptor)
        assertTrue(client.interceptors[2] is AuthInterceptor)
        assertTrue(client.networkInterceptors.single() is TransportSecurityInterceptor)
    }
}
