package net.koalastuff.koalacast.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
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
}
