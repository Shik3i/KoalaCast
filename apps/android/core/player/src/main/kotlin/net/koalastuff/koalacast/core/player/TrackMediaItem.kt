package net.koalastuff.koalacast.core.player

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import net.koalastuff.koalacast.core.model.Track

/**
 * A [Track] survives the trip through the media session as extras on its
 * [MediaItem], so the service can write progress for the right episode even
 * after the UI process is gone and the controller has been rebuilt.
 *
 * Audio always streams from the publisher's enclosure URL: KoalaCast never
 * proxies it.
 */
object TrackMediaItem {

    private const val KEY_PODCAST_ID = "koalacast.podcastId"
    private const val KEY_PODCAST_TITLE = "koalacast.podcastTitle"
    private const val KEY_DURATION_MS = "koalacast.durationMs"
    private const val KEY_ARTWORK_URL = "koalacast.artworkUrl"
    private const val KEY_CATEGORIES = "koalacast.categories"
    private const val KEY_ENCLOSURE_URL = "koalacast.enclosureUrl"
    private const val KEY_OFFLINE_SOURCE = "koalacast.offlineSource"

    fun from(track: Track, artworkUri: String? = null, mediaUri: String? = null): MediaItem {
        val extras = Bundle().apply {
            putString(KEY_PODCAST_ID, track.podcastId)
            putString(KEY_PODCAST_TITLE, track.podcastTitle)
            putLong(KEY_DURATION_MS, track.durationMs)
            putString(KEY_ARTWORK_URL, track.artworkUrl)
            putStringArray(KEY_CATEGORIES, track.categories.toTypedArray())
            putString(KEY_ENCLOSURE_URL, track.enclosureUrl)
            putBoolean(KEY_OFFLINE_SOURCE, mediaUri != null)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.podcastTitle)
            .setAlbumTitle(track.podcastTitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setArtworkUri((artworkUri ?: track.artworkUrl).takeIf { it.isNotBlank() }?.toUri())
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(track.episodeId)
            .setUri(mediaUri ?: track.enclosureUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    fun toTrack(item: MediaItem?): Track? {
        if (item == null || item.mediaId.isBlank()) return null
        val metadata = item.mediaMetadata
        val extras = metadata.extras ?: return null
        return Track(
            episodeId = item.mediaId,
            podcastId = extras.getString(KEY_PODCAST_ID).orEmpty(),
            title = metadata.title?.toString().orEmpty(),
            podcastTitle = extras.getString(KEY_PODCAST_TITLE).orEmpty(),
            artworkUrl = extras.getString(KEY_ARTWORK_URL).orEmpty(),
            enclosureUrl = extras.getString(KEY_ENCLOSURE_URL)
                ?: item.localConfiguration?.uri?.toString().orEmpty(),
            durationMs = extras.getLong(KEY_DURATION_MS),
            categories = extras.getStringArray(KEY_CATEGORIES)?.toList().orEmpty(),
        )
    }

    fun isOffline(item: MediaItem?): Boolean =
        item?.mediaMetadata?.extras?.getBoolean(KEY_OFFLINE_SOURCE, false) == true
}
