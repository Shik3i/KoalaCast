package net.koalastuff.koalacast.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.db.KoalaCastDatabase
import net.koalastuff.koalacast.core.data.db.SubscriptionEntity
import net.koalastuff.koalacast.core.data.db.TombstoneEntity
import net.koalastuff.koalacast.core.network.KoalaCastApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class SyncRepositoryTest {
    private lateinit var database: KoalaCastDatabase
    private lateinit var repository: SyncRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("secure_account", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, KoalaCastDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val api = Proxy.newProxyInstance(
            KoalaCastApi::class.java.classLoader,
            arrayOf(KoalaCastApi::class.java),
        ) { _, method, _ -> error("unexpected API call: ${method.name}") } as KoalaCastApi
        repository = SyncRepository(
            api = api,
            store = SecureAccountStore(context),
            database = database,
            subscriptions = database.subscriptionDao(),
            favorites = database.favoriteDao(),
            playbackStates = database.playbackStateDao(),
            listeningSessions = database.listeningSessionDao(),
            tombstones = database.tombstoneDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `push contains full records and deletion tombstones with stable ids`() = runTest {
        database.subscriptionDao().upsert(
            SubscriptionEntity(
                podcastId = "show",
                feedUrl = "https://example.com/feed.xml",
                title = "Show",
                artworkUrl = "https://example.com/cover.jpg",
                addedAt = 123,
                inboxMode = SubscriptionEntity.INBOX_MODE_LATEST,
            ),
        )
        database.tombstoneDao().upsert(
            TombstoneEntity(
                id = "favorite:gone",
                entityType = "favorite",
                entityId = "gone",
                deletedAt = 456,
            ),
        )

        val operations = repository.buildOperations("device")

        assertEquals(2, operations.size)
        val subscription = operations.first { it.entityType == "subscription" }
        assertEquals("s:show:123", subscription.clientOpId)
        assertEquals("latest", subscription.payload.toString()
            .substringAfter("\"inbox_mode\":\"").substringBefore('"'))
        val deletion = operations.first { it.action == "delete" }
        assertEquals("d:favorite:gone:456", deletion.clientOpId)
        assertEquals("gone", deletion.entityId)
        assertTrue(deletion.payload.toString() == "{}")
    }

    @Test
    fun `push skips unresolved OPML subscriptions`() = runTest {
        val feedUrl = "https://example.com/imported.xml"
        database.subscriptionDao().upsert(
            SubscriptionEntity(
                podcastId = feedUrl,
                feedUrl = feedUrl,
                title = "Imported",
                artworkUrl = "",
                addedAt = 123,
            ),
        )

        assertTrue(repository.buildOperations("device").isEmpty())
    }
}
