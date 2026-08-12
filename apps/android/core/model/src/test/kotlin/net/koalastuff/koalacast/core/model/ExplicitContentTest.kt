package net.koalastuff.koalacast.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitContentTest {
    @Test
    fun `nullable metadata maps to all three ratings`() {
        assertEquals(ExplicitRating.EXPLICIT, true.explicitRating)
        assertEquals(ExplicitRating.CLEAN, false.explicitRating)
        assertEquals(ExplicitRating.UNKNOWN, null.explicitRating)
    }

    @Test
    fun `disabled preference blocks only definite explicit metadata`() {
        assertFalse(true.isAllowedByExplicitPreference(false))
        assertTrue(false.isAllowedByExplicitPreference(false))
        assertTrue(null.isAllowedByExplicitPreference(false))
        assertTrue(true.isAllowedByExplicitPreference(true))
    }
}
