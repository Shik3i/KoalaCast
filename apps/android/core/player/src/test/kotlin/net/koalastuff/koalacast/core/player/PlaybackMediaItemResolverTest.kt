package net.koalastuff.koalacast.core.player

import androidx.media3.common.MediaItem
import kotlinx.coroutines.runBlocking
import net.koalastuff.koalacast.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackMediaItemResolverTest {

    @Test
    fun `fresh app media item plays without a stored progress row`() = runBlocking {
        val track = track("fresh")
        var storedLookups = 0

        val resolved = resolveSessionMediaItem(
            item = TrackMediaItem.from(track),
            storedTrack = {
                storedLookups++
                null
            },
            playableItem = { TrackMediaItem.from(it) },
        )

        assertNotNull(resolved)
        assertEquals(0, storedLookups)
        assertEquals(track.enclosureUrl, resolved?.localConfiguration?.uri?.toString())
    }

    @Test
    fun `id only controller request falls back to stored track`() = runBlocking {
        val track = track("stored")

        val resolved = resolveSessionMediaItem(
            item = MediaItem.Builder().setMediaId(track.episodeId).build(),
            storedTrack = { id -> track.takeIf { id == track.episodeId } },
            playableItem = { TrackMediaItem.from(it) },
        )

        assertEquals(track.enclosureUrl, resolved?.localConfiguration?.uri?.toString())
    }

    @Test
    fun `unknown id only controller request stays unresolved`() = runBlocking {
        val resolved = resolveSessionMediaItem(
            item = MediaItem.Builder().setMediaId("missing").build(),
            storedTrack = { null },
            playableItem = { TrackMediaItem.from(it) },
        )

        assertNull(resolved)
    }

    private fun track(id: String) = Track(
        episodeId = id,
        podcastId = "show",
        title = "Episode $id",
        podcastTitle = "Show",
        artworkUrl = "",
        enclosureUrl = "https://cdn.example/$id.mp3",
        durationMs = 60_000,
        categories = emptyList(),
    )
}
