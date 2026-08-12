package net.koalastuff.koalacast.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSecurityPolicyReleaseTest {
    @Test
    fun `release artifact has no cleartext exception even when requested`() {
        listOf(
            "localhost",
            "127.0.0.1",
            "10.0.2.2",
            "192.168.1.10",
            "example.org",
        ).forEach { host ->
            assertFalse(
                TransportSecurityPolicy.permits(
                    "http://$host:3000".toHttpUrl(),
                    debugBuild = true,
                ),
            )
        }
        assertTrue(TransportSecurityPolicy.permits("https://example.org".toHttpUrl()))
    }
}
