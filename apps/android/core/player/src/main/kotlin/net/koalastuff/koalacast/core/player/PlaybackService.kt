package net.koalastuff.koalacast.core.player

import android.content.Intent
import android.os.Bundle
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.repository.DownloadRepository
import net.koalastuff.koalacast.core.data.repository.LibraryRepository
import net.koalastuff.koalacast.core.data.repository.ProgressRepository
import net.koalastuff.koalacast.core.data.repository.QueueRepository
import net.koalastuff.koalacast.core.data.server.ArtworkUrls
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.model.DownloadState
import net.koalastuff.koalacast.core.model.Track
import kotlin.math.abs
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
// The browse-tree half of Media3's session API is still @UnstableApi. Opt in
// here rather than annotating the class with @UnstableApi itself: that would
// propagate the requirement to every caller, starting with PlayerConnection.
@OptIn(markerClass = [UnstableApi::class])
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var progress: ProgressRepository
    @Inject lateinit var queue: QueueRepository
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var downloads: DownloadRepository
    @Inject lateinit var preferences: PreferencesRepository
    @Inject lateinit var artworkUrls: ArtworkUrls
    @Inject lateinit var clock: Clock

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = ListeningSessionRecorder()

    private var mediaSession: MediaLibrarySession? = null
    private var positionTicker: Job? = null
    private var outroHandledEpisodeId: String? = null
    private var outroSettingsPodcastId: String? = null
    private var cachedSkipOutroMs = 0L
    private var automaticSeekTargetMs: Long? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var boostedSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var boostWanted: Boolean = false
    private var activeTrack: Track? = null
    private var transitionOldPositionMs: Long? = null

    private var playerListener: PlayerListener? = null

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
            .setSkipSilenceEnabled(false)
            .build()

        val listener = PlayerListener(player)
        playerListener = listener
        player.addListener(listener)
        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .build()

        // Audio processing is a stored preference, so apply it as soon as it is
        // readable and whenever it changes rather than only at track start.
        scope.launch {
            preferences.preferences.collect { prefs ->
                val track = TrackMediaItem.toTrack(player.currentMediaItem)
                val show = track?.let { library.podcastSettingsSnapshot(it.podcastId) }
                player.skipSilenceEnabled = show?.skipSilence ?: prefs.skipSilence
                boostWanted = show?.volumeBoost ?: prefs.volumeBoost
                applyVolumeBoost(player, boostWanted)
            }
        }
    }

    /**
     * Quiet recordings are common in independent podcasting. `player.volume` cannot
     * do this — it is capped at unity and only ever attenuates — so the lift comes
     * from [LoudnessEnhancer], which applies gain on the audio session itself.
     *
     * Best effort: the effect is unavailable on some devices and on some routes
     * (certain Bluetooth stacks), where construction throws. Failing quietly is
     * right here — the episode should still play, just without the lift.
     */
    private fun applyVolumeBoost(player: ExoPlayer, enabled: Boolean) {
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return

        if (!enabled) {
            releaseLoudnessEnhancer()
            return
        }
        if (loudnessEnhancer?.id != null && boostedSessionId == sessionId) return

        releaseLoudnessEnhancer()
        loudnessEnhancer = runCatching {
            LoudnessEnhancer(sessionId).apply {
                setTargetGain(BOOST_GAIN_MILLIBEL)
                this.enabled = true
            }
        }.getOrNull()
        boostedSessionId = sessionId
    }

    private fun releaseLoudnessEnhancer() {
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        boostedSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    /**
     * What makes the system offer KoalaCast in the media area after a reboot,
     * before the app has been opened: Android asks the session what was playing,
     * and a session that cannot answer simply does not appear.
     */
    /**
     * The browse tree Android Auto, Wear OS and the Assistant read. Two shelves —
     * what is half-finished and what is downloaded — because those are the only
     * two things worth a glance while driving; deep browsing belongs on the phone.
     */
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /**
         * Advertise the speed command, otherwise a controller may not send it —
         * Media3 rejects commands a session never declared.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val default = super.onConnect(session, controller)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    default.availableSessionCommands.buildUpon()
                        .add(SessionCommand(ACTION_CYCLE_SPEED, Bundle.EMPTY))
                        .build(),
                )
                .setCustomLayout(ImmutableList.of(speedButton()))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != ACTION_CYCLE_SPEED) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            val player = session.player
            val next = SPEED_STEPS[(SPEED_STEPS.indexOfFirst {
                abs(it - player.playbackParameters.speed) < 0.01f
            } + 1).mod(SPEED_STEPS.size)]
            player.playbackParameters = PlaybackParameters(next)
            // The label carries the current value, so the button has to be re-sent.
            session.setCustomLayout(controller, ImmutableList.of(speedButton()))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browsableFolder(ROOT_ID, "KoalaCast"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val items: List<MediaItem> = when (parentId) {
                    ROOT_ID -> listOf(
                        browsableFolder(CONTINUE_ID, getString(R.string.browse_continue)),
                        browsableFolder(DOWNLOADS_ID, getString(R.string.browse_downloads)),
                    )
                    CONTINUE_ID -> progress.inProgress.first()
                        .mapNotNull { it.track }
                        .map { playableItem(it) }
                    DOWNLOADS_ID -> downloads.downloads.first()
                        .filter { it.state == DownloadState.DONE }
                        .map { playableItem(it.track) }
                    else -> emptyList()
                }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            scope.launch {
                val track = storedTrack(mediaId)
                future.set(
                    if (track == null) {
                        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    } else {
                        LibraryResult.ofItem(playableItem(track), null)
                    },
                )
            }
            return future
        }

        /**
         * A car head unit hands back only a media id, so the track has to be
         * rebuilt from what is stored on device rather than fetched.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val resolved = mediaItems.mapNotNull { item ->
                    storedTrack(item.mediaId)?.let { track -> playableItem(track) }
                }
                if (resolved.isEmpty()) {
                    future.setException(UnsupportedOperationException("unknown media id"))
                } else {
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs),
                    )
                }
            }
            return future
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            ResumptionCallback().onPlaybackResumption(mediaSession, controller)
    }

    /** Labelled with the speed it will show, so the notification reads as a value. */
    private fun speedButton(): CommandButton {
        val current = mediaSession?.player?.playbackParameters?.speed ?: 1f
        return CommandButton.Builder()
            .setDisplayName(getString(R.string.player_speed_label, current))
            .setIconResId(R.drawable.ic_playback_speed)
            .setSessionCommand(SessionCommand(ACTION_CYCLE_SPEED, Bundle.EMPTY))
            .build()
    }

    private fun browsableFolder(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    private suspend fun storedTrack(episodeId: String): Track? =
        progress.progressSnapshot(episodeId)?.track ?: downloads.completedTrack(episodeId)

    private suspend fun playableItem(track: Track): MediaItem {
        val localMediaUri = downloads.completedPath(track.episodeId)
            ?.let {
                if (it.startsWith("content://")) it
                else android.net.Uri.fromFile(java.io.File(it)).toString()
            }
        return TrackMediaItem.from(
            track,
            artworkUrls.forArtwork(track.artworkUrl, ARTWORK_PX),
            localMediaUri,
        )
    }

    private inner class ResumptionCallback : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val resumable = progress.mostRecentResumable()
                if (resumable == null) {
                    future.setException(UnsupportedOperationException("nothing to resume"))
                } else {
                    val (track, positionMs) = resumable
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            listOf(
                                TrackMediaItem.from(
                                    track,
                                    artworkUrls.forArtwork(track.artworkUrl, ARTWORK_PX),
                                ),
                            ),
                            /* startIndex = */ 0,
                            positionMs,
                        ),
                    )
                }
            }
            return future
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

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
        releaseLoudnessEnhancer()
        positionTicker?.cancel()
        val finalPlayback = capturePlayback(finalise = true)
        if (finalPlayback != null) {
            runBlocking(Dispatchers.IO) { persist(finalPlayback) }
        }
        mediaSession?.run {
            playerListener?.let { player.removeListener(it) }
            player.release()
            release()
        }
        playerListener = null
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class PlayerListener(private val player: ExoPlayer) : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publishWidgetState(player)
            if (isPlaying) {
                // The session id is unset until the audio track is created, so the
                // preference collector above may have run too early to attach.
                applyVolumeBoost(player, boostWanted)
                TrackMediaItem.toTrack(player.currentMediaItem)?.let { track ->
                    recorder.start(
                        track,
                        clock.nowMs(),
                        player.playbackParameters.speed,
                        player.currentPosition,
                    )
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

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            recorder.onSpeedChanged(
                playbackParameters.speed,
                clock.nowMs(),
                player.currentPosition,
            )?.let { session ->
                scope.launch { progress.recordListeningSession(session) }
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
            transitionOldPositionMs = oldPosition.positionMs.coerceAtLeast(0)
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            val automaticTarget = automaticSeekTargetMs
            automaticSeekTargetMs = null
            if (
                automaticTarget != null &&
                abs(newPosition.positionMs - automaticTarget) <= AUTOMATIC_SEEK_TOLERANCE_MS
            ) {
                return
            }
            recorder.onManualSkip(newPosition.positionMs - oldPosition.positionMs)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            publishWidgetState(player)
            val previousTrack = activeTrack
            val nextTrack = TrackMediaItem.toTrack(mediaItem)
            activeTrack = nextTrack
            nextTrack?.let { track ->
                scope.launch {
                    val global = preferences.preferences.first()
                    val show = library.podcastSettingsSnapshot(track.podcastId)
                    player.skipSilenceEnabled = show.skipSilence ?: global.skipSilence
                    boostWanted = show.volumeBoost ?: global.volumeBoost
                    applyVolumeBoost(player, boostWanted)
                }
            }
            // The player's current item is already the new one here. Persisting
            // through persistNow() would write the old segment against the new
            // episode, so close only the recorder and start the new segment.
            // The playhead already belongs to the new item, so the closing
            // segment gets no position: it would measure the wrong episode.
            val closedSession = recorder.stop(clock.nowMs())
            if (previousTrack != null && previousTrack.episodeId != nextTrack?.episodeId) {
                val position = transitionOldPositionMs ?: 0L
                scope.launch {
                    progress.savePosition(previousTrack, position, previousTrack.durationMs)
                    closedSession?.let { progress.recordListeningSession(it) }
                }
            } else if (closedSession != null) {
                scope.launch { progress.recordListeningSession(closedSession) }
            }
            transitionOldPositionMs = null
            outroHandledEpisodeId = null
            automaticSeekTargetMs = null
            if (player.isPlaying) {
                TrackMediaItem.toTrack(mediaItem)?.let { track ->
                    recorder.start(
                        track,
                        clock.nowMs(),
                        player.playbackParameters.speed,
                        player.currentPosition,
                    )
                }
            }
        }
    }

    private fun publishWidgetState(player: Player) {
        val track = TrackMediaItem.toTrack(player.currentMediaItem)
        getSharedPreferences(WIDGET_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(WIDGET_TITLE, track?.title)
            .putString(WIDGET_PODCAST, track?.podcastTitle)
            .putBoolean(WIDGET_PLAYING, player.isPlaying)
            .apply()
        sendBroadcast(
            Intent(WIDGET_STATE_CHANGED)
                .setPackage(packageName),
        )
    }

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            var secondsSinceSave = 0
            while (true) {
                delay(OUTRO_CHECK_INTERVAL_MS)
                maybeSkipOutro()
                secondsSinceSave++
                if (secondsSinceSave >= POSITION_SAVE_INTERVAL_SECONDS) {
                    persistNow(finalise = false)
                    secondsSinceSave = 0
                }
            }
        }
    }

    private suspend fun maybeSkipOutro() {
        val player = mediaSession?.player ?: return
        val track = TrackMediaItem.toTrack(player.currentMediaItem) ?: return
        if (outroHandledEpisodeId == track.episodeId) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (outroSettingsPodcastId != track.podcastId) {
            cachedSkipOutroMs =
                library.podcastSettingsSnapshot(track.podcastId).skipOutroSeconds * 1_000L
            outroSettingsPodcastId = track.podcastId
        }
        val skipMs = cachedSkipOutroMs
        if (skipMs <= 0) return
        val remaining = (duration - player.currentPosition).coerceAtLeast(0)
        if (remaining == 0L || remaining > skipMs) return

        outroHandledEpisodeId = track.episodeId
        recorder.onIntroOutroSkip(remaining)
        automaticSeekTargetMs = duration
        player.seekTo(duration)
    }

    /**
     * @param finalise also closes the listening segment. Periodic ticks only
     *   write the position, so a single long listen stays a single session.
     */
    private fun persistNow(finalise: Boolean) {
        val playback = capturePlayback(finalise) ?: return
        scope.launch { persist(playback) }
    }

    private fun capturePlayback(finalise: Boolean): PlaybackPersistence? {
        val player = mediaSession?.player ?: return null
        val track = TrackMediaItem.toTrack(player.currentMediaItem) ?: return null
        val positionMs = player.currentPosition
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: track.durationMs
        val session = if (finalise) recorder.stop(clock.nowMs(), positionMs) else null
        return PlaybackPersistence(track, positionMs, durationMs, session)
    }

    private suspend fun persist(playback: PlaybackPersistence) {
        progress.savePosition(playback.track, playback.positionMs, playback.durationMs)
        playback.session?.let { progress.recordListeningSession(it) }
    }

    /**
     * An episode that ran to the end is finished, and the queue moves on — the
     * same behaviour the web client has.
     */
    private fun onEpisodeFinished(player: ExoPlayer) {
        val finished = TrackMediaItem.toTrack(player.currentMediaItem)
        val session = recorder.stop(clock.nowMs(), player.currentPosition)

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
            startQueuedTrack(player, next.track)
        }
    }

    /**
     * Queue auto-advance has no Activity/PlayerConnection in the foreground, so
     * the service must apply the same resume, show speed, intro, artwork privacy
     * and offline-file rules itself.
     */
    private suspend fun startQueuedTrack(player: ExoPlayer, track: Track) {
        val settings = library.podcastSettingsSnapshot(track.podcastId)
        val savedPosition = progress.progressSnapshot(track.episodeId)
            ?.takeIf { !it.completed }
            ?.positionMs
            ?: 0L
        val startPosition = savedPosition.takeIf { it > 0 }
            ?: settings.skipIntroSeconds * 1_000L
        val speed = settings.speed ?: preferences.preferences.first().playbackSpeed
        val artwork = artworkUrls.forArtwork(track.artworkUrl, ARTWORK_PX)
        val localMediaUri = downloads.completedPath(track.episodeId)
            ?.let {
                if (it.startsWith("content://")) it
                else android.net.Uri.fromFile(java.io.File(it)).toString()
            }

        player.setMediaItem(
            TrackMediaItem.from(track, artwork, localMediaUri),
            startPosition,
        )
        player.playbackParameters = PlaybackParameters(speed)
        player.prepare()
        player.play()
    }

    private companion object {
        const val SEEK_BACK_MS = 15_000L
        const val SEEK_FORWARD_MS = 30_000L
        const val OUTRO_CHECK_INTERVAL_MS = 1_000L
        const val POSITION_SAVE_INTERVAL_SECONDS = 30
        const val AUTOMATIC_SEEK_TOLERANCE_MS = 1_000L
        const val ARTWORK_PX = 512
        const val ROOT_ID = "root"
        const val CONTINUE_ID = "continue"
        const val DOWNLOADS_ID = "downloads"
        const val ACTION_CYCLE_SPEED = "net.koalastuff.koalacast.CYCLE_SPEED"
        const val WIDGET_STATE_CHANGED = "net.koalastuff.koalacast.WIDGET_STATE_CHANGED"
        const val WIDGET_PREFERENCES = "playback_widget"
        const val WIDGET_TITLE = "title"
        const val WIDGET_PODCAST = "podcast"
        const val WIDGET_PLAYING = "playing"
        val SPEED_STEPS = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f).toList()
        /** Below unity headroom, so a loud episode cannot be pushed into clipping. */
        /** +10 dB, the usual lift for speech that was mastered too quietly. */
        const val BOOST_GAIN_MILLIBEL = 1_000
    }

    private data class PlaybackPersistence(
        val track: Track,
        val positionMs: Long,
        val durationMs: Long,
        val session: net.koalastuff.koalacast.core.model.ListeningSession?,
    )
}
