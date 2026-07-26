package net.koalastuff.koalacast.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.koalastuff.koalacast.core.data.db.KoalaCastDatabase
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.model.ListeningSession
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
class ProgressRepositoryTest {

    private lateinit var db: KoalaCastDatabase
    private lateinit var repository: ProgressRepository
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KoalaCastDatabase::class.java,
        ).allowMainThreadQueries().build()

        repository = ProgressRepository(
            playbackStates = db.playbackStateDao(),
            listeningSessions = db.listeningSessionDao(),
            clock = object : Clock {
                override fun nowMs() = now
            },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun track(id: String = "e1", durationMs: Long = 1_000_000L) = Track(
        episodeId = id,
        podcastId = "p1",
        title = "Quiet rooms, loud data",
        podcastTitle = "Northbound Signal",
        artworkUrl = "https://cdn.example/a.jpg",
        enclosureUrl = "https://cdn.example/$id.mp3",
        durationMs = durationMs,
    )

    @Test
    fun `a saved position keeps the metadata needed to resume offline`() = runTest {
        repository.savePosition(track(), positionMs = 250_000, durationMs = 1_000_000)

        val progress = repository.progressSnapshot("e1")!!
        assertEquals(250_000L, progress.positionMs)
        assertEquals(25, progress.progressPercent)
        assertFalse(progress.completed)
        assertEquals("https://cdn.example/e1.mp3", progress.track?.enclosureUrl)
        assertEquals("Northbound Signal", progress.track?.podcastTitle)
    }

    @Test
    fun `the last two percent count as finished, because publishers pad the tail`() = runTest {
        repository.savePosition(track(), positionMs = 985_000, durationMs = 1_000_000)

        assertTrue(repository.progressSnapshot("e1")!!.completed)
    }

    @Test
    fun `in-progress excludes finished episodes and stray taps`() = runTest {
        repository.savePosition(track("finished"), positionMs = 999_000, durationMs = 1_000_000)
        repository.savePosition(track("scrubbed"), positionMs = 2_000, durationMs = 1_000_000)
        repository.savePosition(track("real"), positionMs = 300_000, durationMs = 1_000_000)

        assertEquals(listOf("real"), repository.inProgress.first().map { it.episodeId })
    }

    @Test
    fun `in-progress is most recent first`() = runTest {
        now = 1_000
        repository.savePosition(track("older"), positionMs = 300_000, durationMs = 1_000_000)
        now = 2_000
        repository.savePosition(track("newer"), positionMs = 300_000, durationMs = 1_000_000)

        assertEquals(listOf("newer", "older"), repository.inProgress.first().map { it.episodeId })
    }

    @Test
    fun `marking played keeps the position so resume still works`() = runTest {
        repository.savePosition(track(), positionMs = 400_000, durationMs = 1_000_000)
        repository.setPlayed(track(), played = true)

        val progress = repository.progressSnapshot("e1")!!
        assertTrue(progress.completed)
        assertEquals(100, progress.progressPercent)
        assertEquals(400_000L, progress.positionMs)
    }

    @Test
    fun `marking unplayed rewinds to the start`() = runTest {
        repository.savePosition(track(), positionMs = 400_000, durationMs = 1_000_000)
        repository.setPlayed(track(), played = false)

        val progress = repository.progressSnapshot("e1")!!
        assertFalse(progress.completed)
        assertEquals(0L, progress.positionMs)
        assertTrue(repository.completedIdsSnapshot().isEmpty())
    }

    @Test
    fun `a sub-second segment is a scrub and is not recorded as listening`() = runTest {
        repository.recordListeningSession(session(wallClockMs = 400))
        assertTrue(repository.listeningHistorySnapshot().isEmpty())

        repository.recordListeningSession(session(wallClockMs = 60_000))
        assertEquals(1, repository.listeningHistorySnapshot().size)
    }

    private fun session(wallClockMs: Long) = ListeningSession(
        id = "s-$wallClockMs",
        episodeId = "e1",
        podcastId = "p1",
        title = "Quiet rooms, loud data",
        podcastTitle = "Northbound Signal",
        categories = listOf("Science"),
        startedAtMs = now,
        endedAtMs = now + wallClockMs,
        wallClockMs = wallClockMs,
        audioListenedMs = wallClockMs,
        speedSavedMs = 0,
        silenceSavedMs = 0,
        manualSkippedMs = 0,
        introOutroSkippedMs = 0,
        speedWeightedMs = wallClockMs,
    )
}
