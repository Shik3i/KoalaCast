package net.koalastuff.koalacast.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplicitSettingActionTest {
    @Test
    fun `enabling requests confirmation and disabling is immediate`() {
        assertEquals(
            ExplicitSettingAction.REQUEST_CONFIRMATION,
            explicitSettingAction(requested = true),
        )
        assertEquals(
            ExplicitSettingAction.DISABLE,
            explicitSettingAction(requested = false),
        )
    }
}
