package net.koalastuff.koalacast.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `adds bearer token without exposing it in the url`() {
        server.enqueue(MockResponse(code = 200, body = "ok"))
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(AuthTokenProvider { "secret-device-token" }))
            .build()

        client.newCall(Request.Builder().url(server.url("/api/v1/sync")).build())
            .execute()
            .close()

        val request = server.takeRequest()
        assertEquals("Bearer secret-device-token", request.headers["Authorization"])
        assertEquals(null, request.url.query)
    }

    @Test
    fun `leaves anonymous requests without authorization header`() {
        server.enqueue(MockResponse(code = 200, body = "ok"))
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(AuthTokenProvider { null }))
            .build()

        client.newCall(Request.Builder().url(server.url("/api/v1/healthz")).build())
            .execute()
            .close()

        assertEquals(null, server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `absolute server probe never receives the configured bearer token`() {
        server.enqueue(MockResponse(code = 200, body = "ok"))
        val client = OkHttpClient.Builder()
            .addInterceptor(HostSelectionInterceptor(object : BaseUrlProvider {
                override fun current() = server.url("/")
            }))
            .addInterceptor(AuthInterceptor(AuthTokenProvider { "secret-device-token" }))
            .build()

        client.newCall(
            Request.Builder()
                .url(server.url("/api/v1/healthz"))
                .header(HostSelectionInterceptor.ABSOLUTE_HEADER, "1")
                .build(),
        ).execute().close()

        val request = server.takeRequest()
        assertNull(request.headers["Authorization"])
        assertNull(request.headers[HostSelectionInterceptor.ABSOLUTE_HEADER])
    }
}
