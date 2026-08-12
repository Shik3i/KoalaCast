package net.koalastuff.koalacast.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.koalastuff.koalacast.core.data.db.KoalaCastDatabase
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.model.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueRepositoryTest {

    private lateinit var db: KoalaCastDatabase
    private lateinit var repository: QueueRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KoalaCastDatabase::class.java,
        ).allowMainThreadQueries().build()

        repository = QueueRepository(
            db.queueDao(),
            object : Clock {
                override fun nowMs() = 1_700_000_000_000L
            },
            SecureAccountStore(ApplicationProvider.getApplicationContext()),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun track(id: String, durationMs: Long = 30 * 60_000L) = Track(
        episodeId = id,
        podcastId = "p1",
        title = "Episode $id",
        podcastTitle = "Northbound Signal",
        artworkUrl = "",
        enclosureUrl = "https://cdn.example/$id.mp3",
        durationMs = durationMs,
    )

    private suspend fun queuedIds() = repository.entries.first().map { it.track.episodeId }

    @Test
    fun `items keep the order they were appended in`() = runTest {
        repository.addToEnd(track("a"))
        repository.addToEnd(track("b"))
        repository.addToEnd(track("c"))

        assertEquals(listOf("a", "b", "c"), queuedIds())
    }

    @Test
    fun `play next jumps the queue without disturbing the rest`() = runTest {
        repository.addToEnd(track("a"))
        repository.addToEnd(track("b"))
        repository.addToFront(track("c"))

        assertEquals(listOf("c", "a", "b"), queuedIds())
    }

    @Test
    fun `adding the same episode twice does not duplicate it`() = runTest {
        repository.addToEnd(track("a"))
        repository.addToEnd(track("a"))

        assertEquals(listOf("a"), queuedIds())
    }

    @Test
    fun `explicit episodes are rejected while unknown episodes remain queueable by default`() = runTest {
        assertFalse(repository.addToEnd(track("explicit").copy(explicit = true)))
        assertTrue(repository.addToEnd(track("unknown").copy(explicit = null)))

        assertEquals(listOf("unknown"), queuedIds())
    }

    @Test
    fun `reorder rewrites the whole sequence`() = runTest {
        repository.addToEnd(track("a"))
        repository.addToEnd(track("b"))
        repository.addToEnd(track("c"))

        repository.reorder(listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), queuedIds())
    }

    @Test
    fun `head is the next thing to play and clear empties the queue`() = runTest {
        repository.addToEnd(track("a"))
        repository.addToEnd(track("b"))

        assertEquals("a", repository.head()?.track?.episodeId)
        repository.clear()
        assertTrue(queuedIds().isEmpty())
    }

    @Test
    fun `trim drops from the end until the queue fits the budget`() = runTest {
        // 30 + 30 + 30 minutes at 1x against a 40-minute budget.
        repository.addToEnd(track("a"))
        repository.addToEnd(track("b"))
        repository.addToEnd(track("c"))

        val dropped = repository.trimTo(budgetMs = 40 * 60_000L, speed = 1f)

        assertEquals(listOf("b", "c"), dropped)
        assertEquals(listOf("a"), queuedIds())
    }

    @Test
    fun `trim counts playback time, so a higher speed fits more in`() = runTest {
        repository.addToEnd(track("a"))
        repository.addToEnd(track("b"))

        // At 1.5x, two 30-minute episodes take 40 minutes of real time.
        val dropped = repository.trimTo(budgetMs = 40 * 60_000L, speed = 1.5f)

        assertTrue(dropped.isEmpty())
        assertEquals(listOf("a", "b"), queuedIds())
    }

    @Test
    fun `trim always keeps at least one item rather than emptying the queue`() = runTest {
        repository.addToEnd(track("a", durationMs = 90 * 60_000L))

        val dropped = repository.trimTo(budgetMs = 25 * 60_000L, speed = 1f)

        assertTrue(dropped.isEmpty())
        assertEquals(listOf("a"), queuedIds())
    }
}
