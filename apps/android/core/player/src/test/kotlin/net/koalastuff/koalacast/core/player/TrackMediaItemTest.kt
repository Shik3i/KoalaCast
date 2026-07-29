package net.koalastuff.koalacast.core.player

import net.koalastuff.koalacast.core.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackMediaItemTest {
    private val track = Track(
        episodeId = "episode",
        podcastId = "podcast",
        title = "Episode",
        podcastTitle = "Podcast",
        artworkUrl = "",
        enclosureUrl = "https://cdn.example/episode.mp3",
        durationMs = 1_000,
    )

    @Test
    fun `marks local media sources for restored player UI`() {
        assertFalse(TrackMediaItem.isOffline(TrackMediaItem.from(track)))
        assertTrue(
            TrackMediaItem.isOffline(
                TrackMediaItem.from(track, mediaUri = "file:///data/episode.mp3"),
            ),
        )
    }
}
