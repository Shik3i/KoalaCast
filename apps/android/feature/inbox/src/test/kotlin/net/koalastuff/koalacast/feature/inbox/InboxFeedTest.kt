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

    private fun item(
        id: String,
        podcastId: String,
        publishedAt: Long,
        mode: InboxMode = InboxMode.ALL,
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
            durationMs = 1_000,
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
