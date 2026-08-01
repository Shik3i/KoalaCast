package net.koalastuff.koalacast.core.player

import androidx.media3.common.MediaItem
import net.koalastuff.koalacast.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PodcastCastTransferCallbackTest {

    @Test
    fun `remote target receives enclosure URL instead of local download URI`() {
        val offlineUris = mutableMapOf<String, String>()
        val local = TrackMediaItem.from(track(), "https://server.test/art.jpg", "content://downloads/episode")

        val remote = mediaItemForTarget(local, targetIsRemote = true, offlineUris)

        assertEquals("https://publisher.test/episode.mp3", remote.localConfiguration?.uri.toString())
        assertFalse(TrackMediaItem.isOffline(remote))
        assertEquals("content://downloads/episode", offlineUris["episode-1"])
    }

    @Test
    fun `local target restores the remembered download URI`() {
        val offlineUris = mutableMapOf("episode-1" to "content://downloads/episode")
        val remote = TrackMediaItem.from(track(), "https://server.test/art.jpg")

        val local = mediaItemForTarget(remote, targetIsRemote = false, offlineUris)

        assertEquals("content://downloads/episode", local.localConfiguration?.uri.toString())
        assertTrue(TrackMediaItem.isOffline(local))
        assertTrue(offlineUris.isEmpty())
    }

    @Test
    fun `unknown media items pass through unchanged`() {
        val original = MediaItem.fromUri("https://publisher.test/plain.mp3")

        assertTrue(mediaItemForTarget(original, true, mutableMapOf()) === original)
    }

    private fun track() = Track(
        episodeId = "episode-1",
        podcastId = "podcast-1",
        title = "Episode",
        podcastTitle = "Podcast",
        artworkUrl = "https://publisher.test/art.jpg",
        enclosureUrl = "https://publisher.test/episode.mp3",
        durationMs = 60_000,
    )
}
