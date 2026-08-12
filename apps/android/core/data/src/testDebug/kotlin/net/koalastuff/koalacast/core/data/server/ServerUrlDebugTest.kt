package net.koalastuff.koalacast.core.data.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlDebugTest {
    @Test
    fun `debug accepts only the three explicit HTTP loopback hosts`() {
        assertEquals("http://localhost:3000", ServerUrl.normalise("http://localhost:3000"))
        assertEquals("http://127.0.0.1:3000", ServerUrl.normalise("http://127.0.0.1:3000"))
        assertEquals("http://10.0.2.2:3000", ServerUrl.normalise("http://10.0.2.2:3000"))

        listOf(
            "http://example.org",
            "http://192.168.1.10:3000",
            "http://10.0.2.3:3000",
            "http://[::1]:3000",
            "http://localhost.example.org:3000",
        ).forEach { assertNull(it, ServerUrl.normalise(it)) }
    }

    @Test
    fun `debug keeps custom HTTPS servers`() {
        assertEquals("https://example.org/koalacast", ServerUrl.normalise("https://example.org/koalacast"))
    }

    @Test
    fun `debug preserves stored emulator HTTP but resets stored LAN HTTP`() {
        assertEquals(
            ServerUrl.StoredValue("http://10.0.2.2:3000", false),
            ServerUrl.sanitizeStored("http://10.0.2.2:3000", "https://official.example"),
        )
        assertEquals(
            ServerUrl.StoredValue("https://official.example", true),
            ServerUrl.sanitizeStored("http://192.168.1.10:3000", "https://official.example"),
        )
    }
}
