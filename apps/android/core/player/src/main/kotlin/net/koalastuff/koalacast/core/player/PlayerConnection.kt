package net.koalastuff.koalacast.core.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import net.koalastuff.koalacast.core.data.di.ApplicationScope
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.repository.LibraryRepository
import net.koalastuff.koalacast.core.data.repository.DownloadRepository
import net.koalastuff.koalacast.core.data.repository.ProgressRepository
import net.koalastuff.koalacast.core.data.repository.QueueRepository
import net.koalastuff.koalacast.core.data.server.ArtworkUrls
import net.koalastuff.koalacast.core.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/** What every player surface renders from. */
data class PlaybackUiState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    /** Wall-clock instant the sleep timer fires, or null when it is off. */
    val sleepAtMs: Long? = null,
    /** The preset the listener picked, so the control can show which one is on. */
    val sleepMinutes: Int? = null,
    val sleepAtEpisodeEnd: Boolean = false,
    val upNextCount: Int = 0,
) {
    val isActive: Boolean get() = track != null
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
    val progressPercent: Int
        get() = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0
}

/**
 * The app's handle on the playback service. Everything the UI does goes through
 * here, so screens never touch ExoPlayer and never have to care whether the
 * service is running yet.
 *
 * The sleep timer lives here rather than in the service because it only has to
 * outlive the *screen*, and the process stays alive as long as playback does.
 */
@Singleton
class PlayerConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val queue: QueueRepository,
    private val progress: ProgressRepository,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val preferences: PreferencesRepository,
    private val artworkUrls: ArtworkUrls,
) {

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private val connectMutex = Mutex()
    private var positionTicker: Job? = null
    private var sleepJob: Job? = null

    init {
        scope.launch {
            queue.entries.collect { entries ->
                _state.update { it.copy(upNextCount = entries.size) }
            }
        }
    }

    /**
     * Connecting is idempotent and lazy: nothing binds to the service until the
     * listener actually asks for audio.
     */
    private suspend fun controller(): MediaController {
        controller?.let { return it }
        return connectMutex.withLock {
            controller ?: withContext(Dispatchers.Main) {
                val token = SessionToken(
                    context,
                    ComponentName(context, PlaybackService::class.java),
                )
                val created = suspendGet(MediaController.Builder(context, token).buildAsync())
                created.addListener(ControllerListener())
                controller = created
                created
            }.also { syncFromController() }
        }
    }

    private suspend fun <T> suspendGet(
        future: com.google.common.util.concurrent.ListenableFuture<T>,
    ): T = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { continuation.resumeWith(Result.success(it)) }
                    .onFailure { continuation.resumeWith(Result.failure(it)) }
            },
            MoreExecutors.directExecutor(),
        )
        continuation.invokeOnCancellation { future.cancel(false) }
    }

    /**
     * Starts a track, resuming where the listener left off. The per-show speed
     * overrides the global default, matching the web client.
     */
    fun play(track: Track, resume: Boolean = true) {
        scope.launch {
            val controller = controller()
            val savedPosition = if (resume) {
                progress.progressSnapshot(track.episodeId)
                    ?.takeIf { !it.completed }
                    ?.positionMs
                    ?: 0L
            } else {
                0L
            }
            val showSettings = library.podcastSettingsSnapshot(track.podcastId)
            val speed = showSettings.speed ?: preferences.preferences.first().playbackSpeed
            val startPosition = if (savedPosition == 0L) {
                showSettings.skipIntroSeconds * 1_000L
            } else {
                savedPosition
            }

            // Artwork goes through the listener's own instance when the proxy is
            // on, so the notification does not leak their IP to a publisher CDN.
            val artwork = artworkUrls.forArtwork(track.artworkUrl, ARTWORK_PX)
            val localMediaUri = downloads.completedPath(track.episodeId)
                ?.let { android.net.Uri.fromFile(java.io.File(it)).toString() }

            withContext(Dispatchers.Main) {
                controller.setMediaItem(
                    TrackMediaItem.from(track, artwork, localMediaUri),
                    startPosition,
                )
                controller.playbackParameters = PlaybackParameters(speed)
                controller.prepare()
                controller.play()
            }
            queue.remove(track.episodeId)
        }
    }

    /** Plays the head of the queue, if there is one. */
    fun playNextFromQueue() {
        scope.launch {
            val next = queue.head() ?: return@launch
            play(next.track)
        }
    }

    fun togglePlayPause() = onController { if (it.isPlaying) it.pause() else it.play() }

    fun seekTo(positionMs: Long) = onController { it.seekTo(positionMs.coerceAtLeast(0)) }

    fun seekBack() = onController { it.seekBack() }

    fun seekForward() = onController { it.seekForward() }

    /** Cycles 1 → 1.25 → 1.5 → 1.75 → 2 → 1, as the design specifies. */
    fun cycleSpeed() {
        val next = when (_state.value.speed) {
            in 0f..1.0f -> 1.25f
            in 1.0f..1.25f -> 1.5f
            in 1.25f..1.5f -> 1.75f
            in 1.5f..1.75f -> 2f
            else -> 1f
        }
        setSpeed(next)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3f)
        onController { it.playbackParameters = PlaybackParameters(clamped) }
        _state.update { it.copy(speed = clamped) }
        scope.launch { preferences.setPlaybackSpeed(clamped) }
    }

    /**
     * @param minutes null clears the timer; [atEpisodeEnd] stops when the current
     *   episode finishes instead of at a wall-clock instant.
     */
    fun setSleepTimer(minutes: Int?, atEpisodeEnd: Boolean = false) {
        sleepJob?.cancel()
        if (atEpisodeEnd) {
            _state.update {
                it.copy(sleepAtMs = null, sleepMinutes = null, sleepAtEpisodeEnd = true)
            }
            return
        }
        if (minutes == null) {
            _state.update {
                it.copy(sleepAtMs = null, sleepMinutes = null, sleepAtEpisodeEnd = false)
            }
            return
        }

        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.update {
            it.copy(sleepAtMs = endsAt, sleepMinutes = minutes, sleepAtEpisodeEnd = false)
        }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            onController { it.pause() }
            _state.update { it.copy(sleepAtMs = null, sleepMinutes = null) }
        }
    }

    fun stop() = onController {
        it.pause()
        it.seekTo(0)
    }

    private fun onController(block: (MediaController) -> Unit) {
        scope.launch {
            val controller = controller()
            withContext(Dispatchers.Main) { block(controller) }
        }
    }

    private inner class ControllerListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncFromController()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startPositionTicker() else positionTicker?.cancel()
            syncFromController()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && _state.value.sleepAtEpisodeEnd) {
                setSleepTimer(minutes = null, atEpisodeEnd = false)
                onController { it.pause() }
            }
            syncFromController()
        }
    }

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            while (true) {
                syncFromController()
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun syncFromController() {
        val controller = controller ?: return
        scope.launch {
            withContext(Dispatchers.Main) {
                val track = TrackMediaItem.toTrack(controller.currentMediaItem)
                val duration = controller.duration
                    .takeIf { it != C.TIME_UNSET && it > 0 }
                    ?: track?.durationMs
                    ?: 0L
                _state.update {
                    it.copy(
                        track = track,
                        isPlaying = controller.isPlaying,
                        isBuffering = controller.playbackState == Player.STATE_BUFFERING,
                        positionMs = controller.currentPosition.coerceAtLeast(0),
                        durationMs = duration,
                        speed = controller.playbackParameters.speed,
                    )
                }
            }
        }
    }

    private companion object {
        const val POSITION_TICK_MS = 500L
        const val ARTWORK_PX = 512
    }
}
