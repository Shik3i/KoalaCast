package net.koalastuff.koalacast.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetActionAuthorizationTest {
    @Test fun missingStoredTokenRejectsEvenMissingInput() {
        assertFalse(isTrustedWidgetAction(null, null))
        assertFalse(isTrustedWidgetAction("forged", null))
        assertFalse(isTrustedWidgetAction("", ""))
        assertFalse(isTrustedWidgetAction(" ", " "))
    }

    @Test fun missingOrIncorrectInputRejects() {
        assertFalse(isTrustedWidgetAction(null, "private-token"))
        assertFalse(isTrustedWidgetAction("forged", "private-token"))
    }

    @Test fun exactPrivateTokenAccepts() {
        assertTrue(isTrustedWidgetAction("private-token", "private-token"))
    }
}
