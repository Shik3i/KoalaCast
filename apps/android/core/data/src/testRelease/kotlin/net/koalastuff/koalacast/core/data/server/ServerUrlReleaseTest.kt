package net.koalastuff.koalacast.core.data.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlReleaseTest {
    @Test
    fun `release default rejects every cleartext server and preserves HTTPS`() {
        listOf(
            "http://localhost:3000",
            "http://127.0.0.1:3000",
            "http://10.0.2.2:3000",
            "http://192.168.1.10:3000",
            "http://example.org",
        ).forEach { assertNull(it, ServerUrl.normalise(it)) }

        assertEquals(
            "https://custom.example.org/koalacast",
            ServerUrl.normalise("https://custom.example.org/koalacast"),
        )
    }
}
