package net.koalastuff.koalacast.core.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
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
import java.util.concurrent.atomic.AtomicLong

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
    val sleepAtChapterEnd: Boolean = false,
    val upNextCount: Int = 0,
    val playbackError: String? = null,
    val isOfflineSource: Boolean = false,
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
    private var sleepTargetPositionMs: Long? = null
    private val playGeneration = AtomicLong()
    private val syncLock = Any()
    private var syncRunning = false
    private var syncPending = false

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

    fun connect() {
        scope.launch { runCatching { controller() } }
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
        startPlayback(track, resume, explicitPositionMs = null)
    }

    /** Starts and seeks as one generation-safe controller operation. */
    fun playAt(track: Track, positionMs: Long) {
        startPlayback(track, resume = false, explicitPositionMs = positionMs.coerceAtLeast(0))
    }

    private fun startPlayback(
        track: Track,
        resume: Boolean,
        explicitPositionMs: Long?,
    ) {
        val generation = playGeneration.incrementAndGet()
        // Render the selected episode immediately. MediaController can briefly
        // report an empty timeline while it hands the item to the service; that
        // transition must not make the mini player flash and disappear.
        _state.update {
            it.copy(
                track = track,
                isBuffering = true,
                playbackError = null,
            )
        }
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
            val startPosition = explicitPositionMs ?: if (savedPosition == 0L) {
                    showSettings.skipIntroSeconds * 1_000L
                } else {
                    savedPosition
                }

            // Artwork goes through the listener's own instance when the proxy is
            // on, so the notification does not leak their IP to a publisher CDN.
            val artwork = artworkUrls.forArtwork(track.artworkUrl, ARTWORK_PX)
            val localMediaUri = downloads.completedPath(track.episodeId)
                ?.let {
                    if (it.startsWith("content://")) it
                    else android.net.Uri.fromFile(java.io.File(it)).toString()
                }
            _state.update { it.copy(isOfflineSource = localMediaUri != null) }

            withContext(Dispatchers.Main) {
                if (generation != playGeneration.get()) return@withContext
                controller.setMediaItem(
                    TrackMediaItem.from(track, artwork, localMediaUri),
                    startPosition,
                )
                controller.playbackParameters = PlaybackParameters(speed)
                controller.prepare()
                controller.play()
            }
            if (generation != playGeneration.get()) return@launch
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

    fun retry() {
        _state.update { it.copy(playbackError = null, isBuffering = true) }
        onController {
            it.prepare()
            it.play()
        }
    }

    fun seekTo(positionMs: Long) = onController { it.seekTo(positionMs.coerceAtLeast(0)) }

    fun seekBack() = onController { it.seekBack() }

    fun seekForward() = onController { it.seekForward() }

    /** Cycles 1 → 1.25 → 1.5 → 1.75 → 2 → 1, as the design specifies. */
    fun setSpeed(speed: Float) {
        // Same range as the web client. The narrower 0.5–3 window here silently
        // rewrote a synced 0.4× or 3.5× the moment this device touched the control.
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        onController { it.playbackParameters = PlaybackParameters(clamped) }
        _state.update { it.copy(speed = clamped) }
        scope.launch { preferences.setPlaybackSpeed(clamped) }
    }

    /**
     * @param minutes null clears the timer; [atEpisodeEnd] stops when the current
     *   episode finishes instead of at a wall-clock instant.
     */
    fun setSleepTimer(minutes: Int?, atEpisodeEnd: Boolean = false, atChapterEnd: Boolean = false) {
        sleepJob?.cancel()
        sleepTargetPositionMs = null
        sendSleepTimerCommand(atEpisodeEnd = atEpisodeEnd, positionMs = null)
        if (atChapterEnd) {
            _state.update {
                it.copy(sleepAtMs = null, sleepMinutes = null, sleepAtEpisodeEnd = false, sleepAtChapterEnd = true)
            }
            return
        }
        if (atEpisodeEnd) {
            _state.update {
                it.copy(sleepAtMs = null, sleepMinutes = null, sleepAtEpisodeEnd = true, sleepAtChapterEnd = false)
            }
            return
        }
        if (minutes == null) {
            _state.update {
                it.copy(sleepAtMs = null, sleepMinutes = null, sleepAtEpisodeEnd = false, sleepAtChapterEnd = false)
            }
            return
        }

        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.update {
            it.copy(sleepAtMs = endsAt, sleepMinutes = minutes, sleepAtEpisodeEnd = false, sleepAtChapterEnd = false)
        }
        // A single delay() spent the timer on wall-clock time, so pausing for a phone
        // call could burn the whole thing: playback resumed and stopped again seconds
        // later. "Thirty minutes" means thirty minutes of listening, so the countdown
        // only advances while audio is actually playing — which is what the web client
        // does as well.
        sleepJob = scope.launch {
            var remaining = minutes * 60_000L
            var last = System.currentTimeMillis()
            while (remaining > 0) {
                delay(SLEEP_TICK_MS)
                val now = System.currentTimeMillis()
                val elapsed = (now - last).coerceIn(0, MAX_SLEEP_TICK_MS)
                last = now
                if (!_state.value.isPlaying) continue
                remaining -= elapsed
                _state.update { it.copy(sleepAtMs = now + remaining.coerceAtLeast(0)) }
            }
            onController { it.pause() }
            _state.update { it.copy(sleepAtMs = null, sleepMinutes = null, sleepAtChapterEnd = false) }
        }
    }

    fun setSleepAtPosition(positionMs: Long) {
        sleepJob?.cancel()
        sleepTargetPositionMs = positionMs.coerceAtLeast(0)
        _state.update {
            it.copy(
                sleepAtMs = null,
                sleepMinutes = null,
                sleepAtEpisodeEnd = false,
                sleepAtChapterEnd = true,
            )
        }
        sendSleepTimerCommand(atEpisodeEnd = false, positionMs = sleepTargetPositionMs)
    }

    private fun sendSleepTimerCommand(atEpisodeEnd: Boolean, positionMs: Long?) {
        onController {
            val args = Bundle().apply {
                putBoolean(ARG_SLEEP_AT_EPISODE_END, atEpisodeEnd)
                putLong(ARG_SLEEP_AT_POSITION_MS, positionMs ?: C.TIME_UNSET)
            }
            it.sendCustomCommand(SessionCommand(ACTION_SET_SLEEP_TIMER, Bundle.EMPTY), args)
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
            if (playbackState == Player.STATE_ENDED && (_state.value.sleepAtEpisodeEnd || _state.value.sleepAtChapterEnd)) {
                setSleepTimer(minutes = null, atEpisodeEnd = false, atChapterEnd = false)
                onController { it.pause() }
            }
            syncFromController()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    playbackError = error.errorCodeName,
                )
            }
        }
    }

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            while (true) {
                syncFromController()
                val target = sleepTargetPositionMs
                if (target != null && _state.value.positionMs >= target) {
                    sleepTargetPositionMs = null
                    _state.update { it.copy(sleepAtChapterEnd = false) }
                }
                delay(POSITION_TICK_MS)
            }
        }
    }

    /**
     * Coalesces refreshes. `onEvents` fires several times for a single user action
     * and the position ticker adds two more per second; each one used to allocate a
     * coroutine and a main-thread hop, and they could land out of order. One
     * refresh runs at a time, and a request that arrives while it is running is
     * collapsed into a single follow-up — so the final state is never the stale one.
     */
    private fun syncFromController() {
        if (controller == null) return
        synchronized(syncLock) {
            if (syncRunning) {
                syncPending = true
                return
            }
            syncRunning = true
        }
        scope.launch {
            try {
                do {
                    readControllerState()
                } while (synchronized(syncLock) {
                        val again = syncPending
                        syncPending = false
                        if (!again) syncRunning = false
                        again
                    })
            } catch (error: Throwable) {
                synchronized(syncLock) {
                    syncRunning = false
                    syncPending = false
                }
                throw error
            }
        }
    }

    private suspend fun readControllerState() {
        val controller = controller ?: return
        withContext(Dispatchers.Main) {
            val currentItem = controller.currentMediaItem
            val track = TrackMediaItem.toTrack(currentItem)
                ?: _state.value.track?.takeIf {
                    currentItem == null || currentItem.mediaId == it.episodeId
                }
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
                    playbackError = if (controller.playerError == null) {
                        it.playbackError
                    } else {
                        controller.playerError?.errorCodeName
                    },
                    isOfflineSource = TrackMediaItem.isOffline(currentItem),
                )
            }
        }
    }

    private companion object {
        const val POSITION_TICK_MS = 500L
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        const val SLEEP_TICK_MS = 1_000L
        /** A doze or a stalled buffer must not charge minutes to a single tick. */
        const val MAX_SLEEP_TICK_MS = 5_000L
        const val ARTWORK_PX = 512
        const val ACTION_SET_SLEEP_TIMER = "net.koalastuff.koalacast.SET_SLEEP_TIMER"
        const val ARG_SLEEP_AT_EPISODE_END = "sleep_at_episode_end"
        const val ARG_SLEEP_AT_POSITION_MS = "sleep_at_position_ms"
    }
}
