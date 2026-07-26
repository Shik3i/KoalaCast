package net.koalastuff.koalacast.core.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.data.repository.ProgressRepository
import net.koalastuff.koalacast.core.data.repository.QueueRepository
import net.koalastuff.koalacast.core.data.util.Clock
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/**
 * Playback lives in a service, not in a screen: audio has to keep going when the
 * app is backgrounded, the screen is off, or the listener is in the car. The
 * session also gives the notification, lock screen, Bluetooth buttons and Android
 * Auto a single owner.
 *
 * The service — not the UI — persists progress and advances the queue, because
 * both must keep happening after the last Activity is gone.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var progress: ProgressRepository
    @Inject lateinit var queue: QueueRepository
    @Inject lateinit var clock: Clock

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = ListeningSessionRecorder()

    private var mediaSession: MediaSession? = null
    private var positionTicker: Job? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            // ±15/30 s, which Media3 renders as the notification's seek buttons.
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                // Duck for a navigation prompt, pause for a phone call.
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(PlayerListener(player))
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Swiping the app away while paused should not leave a dead notification, but
     * swiping it away while playing should not stop the audio either.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistNow(finalise = true)
        positionTicker?.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class PlayerListener(private val player: ExoPlayer) : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                TrackMediaItem.toTrack(player.currentMediaItem)?.let { track ->
                    recorder.start(track, clock.nowMs(), player.playbackParameters.speed)
                }
                startPositionTicker()
            } else {
                positionTicker?.cancel()
                persistNow(finalise = true)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                onEpisodeFinished(player)
            }
        }

        /**
         * A seek is not listening. Forward jumps are attributed to "manual
         * fast-forward" on the Profile screen; the segment itself keeps running,
         * so a scrub does not fragment one listen into dozens of sessions.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            recorder.onManualSkip(newPosition.positionMs - oldPosition.positionMs)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Close the previous segment before the new track's clock starts.
            persistNow(finalise = true)
        }
    }

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            while (true) {
                delay(POSITION_SAVE_INTERVAL_MS)
                persistNow(finalise = false)
            }
        }
    }

    /**
     * @param finalise also closes the listening segment. Periodic ticks only
     *   write the position, so a single long listen stays a single session.
     */
    private fun persistNow(finalise: Boolean) {
        val player = mediaSession?.player ?: return
        val track = TrackMediaItem.toTrack(player.currentMediaItem) ?: return
        val positionMs = player.currentPosition
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: track.durationMs
        val session = if (finalise) recorder.stop(clock.nowMs()) else null

        scope.launch {
            progress.savePosition(track, positionMs, durationMs)
            session?.let { progress.recordListeningSession(it) }
        }
    }

    /**
     * An episode that ran to the end is finished, and the queue moves on — the
     * same behaviour the web client has.
     */
    private fun onEpisodeFinished(player: ExoPlayer) {
        val finished = TrackMediaItem.toTrack(player.currentMediaItem)
        val session = recorder.stop(clock.nowMs())

        scope.launch {
            finished?.let {
                progress.setPlayed(it, played = true)
                queue.remove(it.episodeId)
            }
            session?.let { progress.recordListeningSession(it) }

            val next = queue.head()
            if (next == null) {
                player.pause()
                player.seekTo(0)
                return@launch
            }
            queue.remove(next.track.episodeId)
            player.setMediaItem(TrackMediaItem.from(next.track))
            player.prepare()
            player.play()
        }
    }

    private companion object {
        const val SEEK_BACK_MS = 15_000L
        const val SEEK_FORWARD_MS = 30_000L
        const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}
