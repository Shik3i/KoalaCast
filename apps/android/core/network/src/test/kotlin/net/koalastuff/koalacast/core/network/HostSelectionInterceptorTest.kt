package net.koalastuff.koalacast.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HostSelectionInterceptorTest {

    private lateinit var server: MockWebServer
    private var configured: HttpUrl? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HostSelectionInterceptor(object : BaseUrlProvider {
                override fun current(): HttpUrl? = configured
            }))
            .build()
    }

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
    fun `requests are re-pointed at the configured server`() {
        configured = server.url("/").toString().trimEnd('/').toHttpUrl()
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        val response = client
            .newCall(Request.Builder().url("http://server.invalid/api/v1/healthz").build())
            .execute()
        response.close()

        val recorded = server.takeRequest()
        assertEquals("/api/v1/healthz", recorded.url.encodedPath)
        assertEquals(server.hostName, recorded.url.host)
    }

    @Test
    fun `a base path prefix is prepended, not replaced`() {
        configured = server.url("/koalacast").toString().toHttpUrl()
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        client.newCall(
            Request.Builder().url("http://server.invalid/api/v1/podcasts/discover?limit=5").build(),
        ).execute().close()

        val recorded = server.takeRequest()
        assertEquals("/koalacast/api/v1/podcasts/discover", recorded.url.encodedPath)
        assertEquals("5", recorded.url.queryParameter("limit"))
    }

    @Test
    fun `a request marked absolute keeps its own host and loses the marker header`() {
        // Deliberately point the interceptor somewhere else: the probe must ignore it.
        configured = "https://elsewhere.invalid".toHttpUrl()
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        client.newCall(
            Request.Builder()
                .url(server.url("/api/v1/healthz"))
                .header(HostSelectionInterceptor.ABSOLUTE_HEADER, "1")
                .build(),
        ).execute().close()

        val recorded = server.takeRequest()
        assertEquals("/api/v1/healthz", recorded.url.encodedPath)
        assertNull(recorded.headers[HostSelectionInterceptor.ABSOLUTE_HEADER])
    }

    @Test(expected = IOException::class)
    fun `no configured server fails as an IO error rather than a crash`() {
        configured = null
        client.newCall(Request.Builder().url("http://server.invalid/api/v1/healthz").build())
            .execute()
            .close()
    }
}
