package net.koalastuff.koalacast.core.data.repository

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncedSettingsTest {

    /** `date_format` and `ui_language` are the web client's, and only its. */
    @Test
    fun `keys this client does not own are recognised as foreign`() {
        val payload = buildJsonObject {
            put("theme_mode", "dark")
            put("start_screen", "inbox")
            put("date_format", "relative")
            put("ui_language", "de")
            put("updated_at", 1_000L)
        }

        val foreign = SyncedSettings.foreignOf(payload)

        assertEquals(setOf("date_format", "ui_language"), foreign.keys)
    }

    /** A key neither client knows yet is somebody else's too, not ours to drop. */
    @Test
    fun `unknown future keys count as foreign`() {
        val payload = buildJsonObject { put("something_added_later", true) }

        assertEquals(setOf("something_added_later"), SyncedSettings.foreignOf(payload).keys)
    }

    @Test
    fun `updated_at belongs to this client and is never treated as foreign`() {
        val payload = buildJsonObject { put("updated_at", 5L) }

        assertTrue(SyncedSettings.foreignOf(payload).isEmpty())
    }

    /**
     * The regression this guards: a push rebuilt from known fields used to drop the
     * other client's keys, so a fresh install restored only half its settings.
     */
    @Test
    fun `merge hands the other client its keys back`() {
        val owned = buildJsonObject {
            put("theme_mode", "light")
            put("updated_at", 2_000L)
        }
        val foreign = buildJsonObject {
            put("date_format", "relative")
            put("ui_language", "de")
        }

        val merged = SyncedSettings.merge(owned, foreign)

        assertEquals("relative", merged["date_format"]?.jsonPrimitive?.content)
        assertEquals("de", merged["ui_language"]?.jsonPrimitive?.content)
        assertEquals("light", merged["theme_mode"]?.jsonPrimitive?.content)
    }

    /**
     * If the other client ever writes a key we own, our value has to win — otherwise
     * a stale foreign snapshot would silently undo a setting the listener just made.
     */
    @Test
    fun `owned keys win over a foreign snapshot claiming them`() {
        val owned = buildJsonObject { put("theme_mode", "light") }
        val foreign = buildJsonObject { put("theme_mode", "dark") }

        val merged = SyncedSettings.merge(owned, foreign)

        assertEquals("light", merged["theme_mode"]?.jsonPrimitive?.content)
        assertEquals(1, merged.keys.count { it == "theme_mode" })
    }

    @Test
    fun `merging without foreign keys changes nothing`() {
        val owned = buildJsonObject {
            put("theme_mode", "dark")
            put("updated_at", 7L)
        }

        assertEquals(owned, SyncedSettings.merge(owned, buildJsonObject { }))
    }

    /**
     * The one way this design rots: a key added to the payload but not to the owned
     * set is treated as foreign, so it gets stored as somebody else's and written
     * twice. Keeping the two in step is the whole contract.
     */
    @Test
    fun `every key the payload writes is declared as owned`() {
        val written = setOf(
            "theme_mode",
            "palette",
            "languages",
            "interests",
            "hidden_genres",
            "hidden_podcasts",
            "default_inbox_mode",
            "start_screen",
            "visualizer",
            "proxy_images",
            "playback_speed",
            "download_wifi_only",
            "skip_silence",
            "volume_boost",
            "auto_download_count",
            "download_retention",
            "download_concurrency",
            "download_budget_bytes",
            "updated_at",
            "field_updated_at",
        )

        assertEquals(written, SyncedSettings.ownedKeys)
        assertFalse("date_format" in SyncedSettings.ownedKeys)
        assertFalse("ui_language" in SyncedSettings.ownedKeys)
    }

    @Test
    fun `keeps both halves of two concurrent edits`() {
        // The phone changed the language at 10:00, the browser the palette at 10:01.
        // The browser's blob is newer, and used to revert the language wholesale.
        val accepted = SyncedSettings.decide(
            incoming = mapOf("palette" to 1_001L, "languages" to 900L),
            incomingUpdatedAt = 1_001L,
            local = mapOf("languages" to 1_000L, "palette" to 900L),
            localUpdatedAt = 1_000L,
        )
        assertTrue("palette" in accepted)
        assertFalse("languages" in accepted)
    }

    @Test
    fun `accepts an older blob that still carries a newer field`() {
        val accepted = SyncedSettings.decide(
            incoming = mapOf("languages" to 2_000L),
            incomingUpdatedAt = 500L,
            local = emptyMap(),
            localUpdatedAt = 1_000L,
        )
        assertEquals(2_000L, accepted["languages"])
    }

    @Test
    fun `reads a payload without stamps the old way`() {
        val newer = SyncedSettings.decide(emptyMap(), 2_000L, emptyMap(), 1_000L)
        assertEquals(SyncedSettings.mergeableFields, newer.keys)

        val older = SyncedSettings.decide(emptyMap(), 500L, emptyMap(), 1_000L)
        assertTrue(older.isEmpty())
    }

    @Test
    fun `an upgrading installation does not lose its untouched fields`() {
        val accepted = SyncedSettings.decide(
            incoming = mapOf("languages" to 10L),
            incomingUpdatedAt = 10L,
            local = emptyMap(),
            localUpdatedAt = 5_000L,
        )
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `an exact tie changes nothing, so a re-pulled own write is a no-op`() {
        val accepted = SyncedSettings.decide(
            incoming = mapOf("palette" to 1_000L),
            incomingUpdatedAt = 1_000L,
            local = mapOf("palette" to 1_000L),
            localUpdatedAt = 1_000L,
        )
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `field stamps never leak into the foreign bucket`() {
        assertFalse(SyncedSettings.FIELD_UPDATED_AT in SyncedSettings.mergeableFields)
        assertTrue(SyncedSettings.FIELD_UPDATED_AT in SyncedSettings.ownedKeys)
    }
}
