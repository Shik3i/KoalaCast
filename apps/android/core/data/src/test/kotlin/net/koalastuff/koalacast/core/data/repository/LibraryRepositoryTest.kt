package net.koalastuff.koalacast.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.db.KoalaCastDatabase
import net.koalastuff.koalacast.core.data.db.QueueItemEntity
import net.koalastuff.koalacast.core.data.db.TombstoneEntity
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.Podcast
import net.koalastuff.koalacast.core.model.Track
import net.koalastuff.koalacast.core.network.dto.OpmlImportedPodcast
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryTest {

    private lateinit var db: KoalaCastDatabase
    private lateinit var repository: LibraryRepository
    private var now = 1_700_000_000_000L

    private val clock = object : Clock {
        override fun nowMs(): Long = now
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KoalaCastDatabase::class.java,
        ).allowMainThreadQueries().build()

        repository = LibraryRepository(
            subscriptions = db.subscriptionDao(),
            favorites = db.favoriteDao(),
            timeBookmarks = db.timeBookmarkDao(),
            tombstones = db.tombstoneDao(),
            podcastSettings = db.podcastSettingsDao(),
            clock = clock,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun podcast(id: String = "p1") = Podcast(
        id = id,
        feedUrl = "https://example.org/$id.xml",
        title = "Northbound Signal",
        description = "",
        author = "Marlow & Vey",
        artworkUrl = "https://cdn.example/$id.jpg",
        link = "",
        language = "en",
        explicit = false,
        copyright = "",
        lastSuccessfulFetchAtMs = 0,
        episodeCount = 41,
    )

    private fun track(id: String = "e1") = Track(
        episodeId = id,
        podcastId = "p1",
        title = "Quiet rooms, loud data",
        podcastTitle = "Northbound Signal",
        artworkUrl = "https://cdn.example/a.jpg",
        enclosureUrl = "https://cdn.example/$id.mp3",
        durationMs = 2_947_000,
    )

    @Test
    fun `subscribing stores the show and reports it as subscribed`() = runTest {
        repository.subscribe(podcast())

        assertTrue(repository.isSubscribed("p1").first())
        val stored = repository.subscriptionsSnapshot().single()
        assertEquals("https://example.org/p1.xml", stored.feedUrl)
        assertEquals(now, stored.addedAtMs)
        assertEquals(InboxMode.ALL, stored.inboxMode)
    }

    @Test
    fun `unsubscribing leaves a tombstone so sync cannot resurrect it`() = runTest {
        repository.subscribe(podcast())
        repository.unsubscribe("p1")

        assertFalse(repository.isSubscribed("p1").first())
        val tombstone = db.tombstoneDao().getAll().single()
        assertEquals(TombstoneEntity.TYPE_SUBSCRIPTION, tombstone.entityType)
        assertEquals("p1", tombstone.entityId)
    }

    @Test
    fun `re-subscribing clears the tombstone`() = runTest {
        repository.subscribe(podcast())
        repository.unsubscribe("p1")
        repository.subscribe(podcast())

        assertTrue(db.tombstoneDao().getAll().isEmpty())
        assertTrue(repository.isSubscribed("p1").first())
    }

    @Test
    fun `inbox mode survives a round trip`() = runTest {
        repository.subscribe(podcast())
        repository.setInboxMode("p1", InboxMode.LATEST)

        assertEquals(InboxMode.LATEST, repository.subscriptionsSnapshot().single().inboxMode)
    }

    @Test
    fun `resolved OPML metadata replaces placeholder and preserves local settings`() = runTest {
        val sourceUrl = "https://example.org/imported.xml"
        repository.subscribeImported(listOf(sourceUrl to "Imported title"))
        repository.setInboxMode(sourceUrl, InboxMode.LATEST)
        val addedAt = repository.subscriptionsSnapshot().single().addedAtMs

        repository.subscribeResolvedImports(
            listOf(
                OpmlImportedPodcast(
                    id = "canonical-id",
                    title = "Resolved title",
                    sourceUrl = sourceUrl,
                    feedUrl = "https://cdn.example.org/canonical.xml",
                    artworkUrl = "https://cdn.example/resolved.jpg",
                ),
            ),
        )

        val stored = repository.subscriptionsSnapshot().single()
        assertEquals("canonical-id", stored.podcastId)
        assertEquals("https://cdn.example.org/canonical.xml", stored.feedUrl)
        assertEquals("Resolved title", stored.title)
        assertEquals("https://cdn.example/resolved.jpg", stored.artworkUrl)
        assertEquals(addedAt, stored.addedAtMs)
        assertEquals(InboxMode.LATEST, stored.inboxMode)
    }

    @Test
    fun `time bookmarks are ordered and removable`() = runTest {
        repository.addTimeBookmark("e1", 90_000)
        now++
        repository.addTimeBookmark("e1", 15_000)

        val bookmarks = repository.timeBookmarks("e1").first()
        assertEquals(listOf(15_000L, 90_000L), bookmarks.map { it.positionMs })

        repository.removeTimeBookmark(bookmarks.first().id)
        assertEquals(listOf(90_000L), repository.timeBookmarks("e1").first().map { it.positionMs })
    }

    @Test
    fun `named queue restores an independent snapshot`() = runTest {
        db.queueDao().insert(
            QueueItemEntity(
                id = "q1",
                episodeId = "e1",
                podcastId = "p1",
                title = "Episode",
                enclosureUrl = "https://example.com/e1.mp3",
                positionOrder = 0,
                addedAt = now,
            ),
        )
        val namedQueues = NamedQueueRepository(
            namedQueues = db.namedQueueDao(),
            queue = db.queueDao(),
            json = Json,
            clock = clock,
            syncMetadata = SecureAccountStore(ApplicationProvider.getApplicationContext()),
        )

        namedQueues.save("Commute")
        db.queueDao().clear()
        namedQueues.restore(namedQueues.all.first().single().id)

        assertEquals(listOf("e1"), db.queueDao().getAll().map { it.episodeId })
    }

    @Test
    fun `resolving a podcast without an imported placeholder does not subscribe`() = runTest {
        repository.canonicalizeImportedSubscription(
            sourceFeedUrl = "https://example.org/not-imported.xml",
            podcast = podcast(),
        )

        assertTrue(repository.subscriptionsSnapshot().isEmpty())
        assertFalse(repository.isSubscribed("p1").first())
    }

    @Test
    fun `favouriting keeps enough metadata to play without a refetch`() = runTest {
        repository.addFavorite(track())

        val favorite = repository.allFavorites.first().single()
        assertEquals("e1", favorite.episodeId)
        assertEquals("https://cdn.example/e1.mp3", favorite.track?.enclosureUrl)
        assertEquals(2_947_000L, favorite.track?.durationMs)
    }

    @Test
    fun `toggling a favourite twice ends where it started and tombstones the removal`() = runTest {
        assertTrue(repository.toggleFavorite(track()))
        assertFalse(repository.toggleFavorite(track()))

        assertTrue(repository.allFavorites.first().isEmpty())
        assertEquals(
            TombstoneEntity.TYPE_FAVORITE,
            db.tombstoneDao().getAll().single().entityType,
        )
    }

    @Test
    fun `an unset show reports default playback settings`() = runTest {
        val settings = repository.podcastSettingsSnapshot("p1")
        assertEquals(0, settings.skipIntroSeconds)
        assertEquals(null, settings.speed)
        assertFalse(settings.autoQueueNew)
    }

    @Test
    fun `per-show settings round trip`() = runTest {
        repository.savePodcastSettings(
            repository.podcastSettingsSnapshot("p1")
                .copy(skipIntroSeconds = 62, speed = 1.3f, autoQueueNew = true),
        )

        val settings = repository.podcastSettings("p1").first()
        assertEquals(62, settings.skipIntroSeconds)
        assertEquals(1.3f, settings.speed!!, 0.001f)
        assertTrue(settings.autoQueueNew)
    }
}
