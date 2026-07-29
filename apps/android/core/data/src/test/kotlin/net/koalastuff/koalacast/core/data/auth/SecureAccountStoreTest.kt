package net.koalastuff.koalacast.core.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureAccountStoreTest {
    private lateinit var context: Context
    private lateinit var store: SecureAccountStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("secure_account", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = SecureAccountStore(context)
    }

    @Test
    fun `same user id on different servers gets different owner and sync state`() {
        store.setServerOrigin("https://one.example")
        val firstOwner = store.ownerIdFor("same-user")
        store.setCursor("same-user", 11)

        store.setServerOrigin("https://two.example")
        val secondOwner = store.ownerIdFor("same-user")
        assertNotEquals(firstOwner, secondOwner)
        assertEquals(0, store.cursor("same-user"))
        store.setCursor("same-user", 22)

        store.setServerOrigin("https://one.example")
        assertEquals(11, store.cursor("same-user"))
    }

    @Test
    fun `legacy sync state migrates only to the currently selected server`() {
        context.getSharedPreferences("secure_account", Context.MODE_PRIVATE)
            .edit()
            .putLong("cursor_same-user", 7)
            .commit()
        store.setServerOrigin("https://one.example")

        store.migrateLegacySyncState("same-user")

        assertEquals(7, store.cursor("same-user"))
        store.setServerOrigin("https://two.example")
        assertEquals(0, store.cursor("same-user"))
    }

    @Test
    fun `account transition updates observable generation`() {
        assertEquals(0, store.generation.value)
        assertEquals(1, store.beginAccountTransition())
        assertEquals(1, store.generation.value)
    }
}
