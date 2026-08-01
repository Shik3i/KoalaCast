package net.koalastuff.koalacast.core.player

import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlayerTransferState
import androidx.media3.common.util.UnstableApi

/**
 * Transfers podcast playback between the local ExoPlayer and a Cast receiver.
 *
 * Downloaded episodes use a `content://` or `file://` URI locally. A receiver
 * cannot dereference either, so remote transfers rebuild the item with the
 * publisher enclosure URL kept in [TrackMediaItem]. The local URI is retained
 * only in this process and restored when playback returns to the device.
 */
@OptIn(UnstableApi::class)
internal class PodcastCastTransferCallback : CastPlayer.TransferCallback {
    private val offlineUris = mutableMapOf<String, String>()

    override fun transferState(sourcePlayer: Player, targetPlayer: Player) {
        val targetIsRemote =
            targetPlayer.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        val items = (0 until sourcePlayer.mediaItemCount).map { index ->
            mediaItemForTarget(
                item = sourcePlayer.getMediaItemAt(index),
                targetIsRemote = targetIsRemote,
                offlineUris = offlineUris,
            )
        }
        PlayerTransferState.builderFromPlayer(sourcePlayer)
            .setMediaItems(items)
            .build()
            .setToPlayer(targetPlayer)
    }
}

internal fun mediaItemForTarget(
    item: MediaItem,
    targetIsRemote: Boolean,
    offlineUris: MutableMap<String, String>,
): MediaItem {
    val track = TrackMediaItem.toTrack(item) ?: return item
    val artworkUri = item.mediaMetadata.artworkUri?.toString()

    if (targetIsRemote) {
        if (TrackMediaItem.isOffline(item)) {
            item.localConfiguration?.uri?.toString()?.let { offlineUris[item.mediaId] = it }
        }
        return TrackMediaItem.from(track, artworkUri)
    }

    val offlineUri = offlineUris.remove(item.mediaId)
    return TrackMediaItem.from(track, artworkUri, offlineUri)
}
