package net.koalastuff.koalacast.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.koalastuff.koalacast.core.data.db.FavoriteEntity
import net.koalastuff.koalacast.core.data.db.KoalaCastDatabase
import net.koalastuff.koalacast.core.data.db.ListeningSessionEntity
import net.koalastuff.koalacast.core.data.db.PlaybackStateEntity
import net.koalastuff.koalacast.core.data.db.SubscriptionEntity
import net.koalastuff.koalacast.core.data.db.TombstoneEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountDataNamespaceTest {
    private lateinit var database: KoalaCastDatabase
    private lateinit var namespace: AccountDataNamespace

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KoalaCastDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        namespace = AccountDataNamespace(database, Json)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `logout and login as B cannot expose or push A records`() = runTest {
        namespace.initialize("A")
        database.subscriptionDao().upsert(
            SubscriptionEntity("show-a", "https://a/feed", "A", "", 1),
        )
        database.favoriteDao().upsert(FavoriteEntity("favorite-a", 2))
        database.playbackStateDao().upsert(
            PlaybackStateEntity("episode-a", "show-a", 3, false, 1, 4),
        )
        database.listeningSessionDao().upsert(
            ListeningSessionEntity(
                id = "listen-a",
                episodeId = "episode-a",
                podcastId = "show-a",
                title = "Episode A",
                podcastTitle = "A",
                startedAt = 5,
                endedAt = 6,
                wallClockMs = 1,
                audioListenedMs = 1,
            ),
        )
        database.tombstoneDao().upsert(
            TombstoneEntity("favorite:deleted-a", "favorite", "deleted-a", 7),
        )

        namespace.switchTo(null)
        assertActiveTablesEmpty()
        namespace.switchTo("B")
        assertActiveTablesEmpty()

        namespace.switchTo(null)
        namespace.switchTo("A")
        assertEquals(listOf("show-a"), database.subscriptionDao().getAll().map { it.podcastId })
        assertEquals(listOf("favorite-a"), database.favoriteDao().getAll().map { it.episodeId })
        assertEquals(listOf("episode-a"), database.playbackStateDao().getAll().map { it.episodeId })
        assertEquals(listOf("listen-a"), database.listeningSessionDao().getAll().map { it.id })
        assertEquals(listOf("deleted-a"), database.tombstoneDao().getAll().map { it.entityId })
    }

    private suspend fun assertActiveTablesEmpty() {
        assertTrue(database.subscriptionDao().getAll().isEmpty())
        assertTrue(database.favoriteDao().getAll().isEmpty())
        assertTrue(database.playbackStateDao().getAll().isEmpty())
        assertTrue(database.listeningSessionDao().getAll().isEmpty())
        assertTrue(database.tombstoneDao().getAll().isEmpty())
    }
}
