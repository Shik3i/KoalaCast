package net.koalastuff.koalacast.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSecurityPolicyDebugTest {
    @Test
    fun `debug network guard allows only explicit loopback cleartext`() {
        listOf("localhost", "127.0.0.1", "10.0.2.2").forEach { host ->
            assertTrue(TransportSecurityPolicy.permits("http://$host:3000".toHttpUrl()))
        }
        listOf("example.org", "192.168.1.10", "10.0.2.3").forEach { host ->
            assertFalse(TransportSecurityPolicy.permits("http://$host:3000".toHttpUrl()))
        }
        assertTrue(TransportSecurityPolicy.permits("https://example.org".toHttpUrl()))
    }
}
