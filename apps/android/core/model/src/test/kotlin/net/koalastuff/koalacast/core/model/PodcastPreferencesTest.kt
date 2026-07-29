package net.koalastuff.koalacast.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastPreferencesTest {
    private val show = PodcastSummary(
        id = "42",
        title = "Northbound",
        author = "Koala",
        feedUrl = "HTTPS://EXAMPLE.COM/Feed.xml",
        artworkUrl = "",
        category = "Technology",
        categories = listOf("Technology", "Science"),
        description = "",
        language = "en",
    )

    @Test
    fun `genre hiding is case insensitive`() {
        assertTrue(show.isHiddenBy(setOf("science")))
        assertTrue(show.matchesGenres(setOf("science")))
        assertFalse(show.isHiddenBy(setOf("comedy")))
        assertFalse(show.matchesGenres(setOf("comedy")))
    }

    @Test
    fun `podcast hiding prefers normalized feed url`() {
        assertTrue(
            show.isHiddenByPodcast(
                setOf(HiddenPodcast("feed:https://example.com/feed.xml", "Northbound")),
            ),
        )
        assertFalse(show.isHiddenByPodcast(setOf(HiddenPodcast("id:42", "Northbound"))))
    }
}
