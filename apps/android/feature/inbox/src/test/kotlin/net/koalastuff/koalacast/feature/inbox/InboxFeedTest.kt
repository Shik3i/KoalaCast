package net.koalastuff.koalacast.feature.inbox

import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.Subscription
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxFeedTest {

    @Test
    fun `latest keeps newest per show before global sorting`() {
        val feed = buildInboxFeed(
            episodes = listOf(
                item("a-old", "a", 100, InboxMode.LATEST),
                item("b", "b", 250, InboxMode.ALL),
                item("a-new", "a", 300, InboxMode.LATEST),
            ),
            completedIds = emptySet(),
            unplayedOnly = false,
        )

        assertEquals(listOf("a-new", "b"), feed.map { it.episode.id })
    }

    @Test
    fun `unplayed filter is applied after latest selection`() {
        val feed = buildInboxFeed(
            episodes = listOf(
                item("new", "a", 300, InboxMode.LATEST),
                item("old", "a", 100, InboxMode.LATEST),
            ),
            completedIds = setOf("new"),
            unplayedOnly = true,
        )

        assertEquals(emptyList<InboxEpisode>(), feed)
    }

    @Test
    fun `this and older follows visible newest-first feed`() {
        val feed = listOf(
            item("new", "a", 300),
            item("middle", "b", 200),
            item("old", "a", 100),
        )

        assertEquals(
            listOf("middle", "old"),
            episodesFrom(feed, "middle").map { it.episode.id },
        )
    }

    @Test
    fun `download podcast and date filters compose`() {
        val now = 10L * 24 * 60 * 60 * 1_000
        val feed = buildInboxFeed(
            episodes = listOf(
                item("wanted", "a", now - 1_000),
                item("other-show", "b", now - 1_000),
                item("too-old", "a", now - 9L * 24 * 60 * 60 * 1_000),
            ),
            completedIds = emptySet(),
            filter = InboxFilter(
                unplayedOnly = false,
                downloadedOnly = true,
                podcastId = "a",
                dateRange = InboxDateRange.WEEK,
                nowMs = now,
            ),
            downloadedIds = setOf("wanted", "too-old"),
        )

        assertEquals(listOf("wanted"), feed.map { it.episode.id })
    }

    @Test
    fun `session plan stays inside budget when episodes fit`() {
        val feed = listOf(
            item("twenty", "a", 300, durationMs = 20 * 60_000L),
            item("fifteen", "b", 200, durationMs = 15 * 60_000L),
            item("ten", "c", 100, durationMs = 10 * 60_000L),
        )

        assertEquals(
            listOf("twenty", "ten"),
            buildSessionPlan(feed, 30 * 60_000L).map { it.episode.id },
        )
    }

    @Test
    fun `priority shows sort first and special episodes can be hidden`() {
        val feed = buildInboxFeed(
            episodes = listOf(
                item("regular", "normal", 300),
                item("bonus trailer", "priority", 250),
                item("priority regular", "priority", 200),
            ),
            completedIds = emptySet(),
            filter = InboxFilter(unplayedOnly = false, hideSpecials = true),
            priorityPodcastIds = setOf("priority"),
        )

        assertEquals(listOf("priority regular", "regular"), feed.map { it.episode.id })
    }

    private fun item(
        id: String,
        podcastId: String,
        publishedAt: Long,
        mode: InboxMode = InboxMode.ALL,
        durationMs: Long = 1_000,
    ) = InboxEpisode(
        episode = Episode(
            id = id,
            podcastId = podcastId,
            guid = id,
            title = id,
            description = "",
            contentEncoded = "",
            pubDateMs = publishedAt,
            hasPubDate = true,
            durationMs = durationMs,
            enclosureUrl = "https://example.com/$id.mp3",
            enclosureType = "audio/mpeg",
            enclosureLengthBytes = 1,
            artworkUrl = "",
            episodeNumber = 0,
            seasonNumber = 0,
            episodeType = "",
            explicit = false,
            link = "",
            transcripts = emptyList(),
        ),
        subscription = Subscription(
            podcastId = podcastId,
            feedUrl = "https://example.com/$podcastId.xml",
            title = podcastId,
            artworkUrl = "",
            addedAtMs = 0,
            inboxMode = mode,
        ),
    )
}
