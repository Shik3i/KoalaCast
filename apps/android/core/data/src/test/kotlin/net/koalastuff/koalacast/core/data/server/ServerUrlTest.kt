package net.koalastuff.koalacast.core.data.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    @Test
    fun `bare host gets https`() {
        assertEquals("https://cast.koalastuff.net", ServerUrl.normalise("cast.koalastuff.net"))
    }

    @Test
    fun `trailing slash and whitespace are trimmed`() {
        assertEquals(
            "https://cast.koalastuff.net",
            ServerUrl.normalise("  https://cast.koalastuff.net/  "),
        )
    }

    @Test
    fun `a host with a port but no scheme is still assumed to be TLS`() {
        assertEquals("https://example.org:8443", ServerUrl.normalise("example.org:8443"))
    }

    @Test
    fun `a path prefix survives normalisation`() {
        assertEquals("https://example.org/koalacast", ServerUrl.normalise("example.org/koalacast/"))
    }

    @Test
    fun `blank input is not a URL`() {
        assertNull(ServerUrl.normalise("   "))
    }

    @Test
    fun `cleartext is reported only for http`() {
        assertTrue(ServerUrl.isCleartext("http://10.0.2.2:3000"))
        assertFalse(ServerUrl.isCleartext("https://cast.koalastuff.net"))
        // No scheme means https, so it is not cleartext.
        assertFalse(ServerUrl.isCleartext("cast.koalastuff.net"))
    }

    @Test
    fun `credentials query and fragments are rejected from a server origin`() {
        assertNull(ServerUrl.normalise("https://user:secret@example.org"))
        assertNull(ServerUrl.normalise("https://example.org?token=secret"))
        assertNull(ServerUrl.normalise("https://example.org/#fragment"))
    }

    @Test
    fun `release policy rejects every HTTP origin and keeps HTTPS`() {
        listOf(
            "http://cast.koalastuff.net",
            "http://localhost:3000",
            "http://127.0.0.1:3000",
            "http://10.0.2.2:3000",
            "http://192.168.1.10:3000",
        ).forEach { assertNull(it, ServerUrl.normalise(it, debugBuild = false)) }
        assertEquals(
            "https://example.org/koalacast",
            ServerUrl.normalise("example.org/koalacast", debugBuild = false),
        )
        assertEquals(
            ServerUrl.StoredValue("https://cast.koalastuff.net", true),
            ServerUrl.sanitizeStored(
                "http://10.0.2.2:3000",
                "https://cast.koalastuff.net",
                debugBuild = false,
            ),
        )
    }
}
