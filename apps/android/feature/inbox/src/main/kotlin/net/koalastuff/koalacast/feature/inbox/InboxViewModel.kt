package net.koalastuff.koalacast.feature.inbox

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.koalastuff.koalacast.core.data.repository.LibraryRepository
import net.koalastuff.koalacast.core.data.repository.AccountRepository
import net.koalastuff.koalacast.core.data.repository.PodcastRepository
import net.koalastuff.koalacast.core.data.repository.ProgressRepository
import net.koalastuff.koalacast.core.data.repository.QueueRepository
import net.koalastuff.koalacast.core.data.repository.DownloadRepository
import net.koalastuff.koalacast.core.data.repository.ContentTtl
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.DownloadState
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.Subscription
import net.koalastuff.koalacast.core.player.PlayerConnection
import javax.inject.Inject

data class InboxUiState(
    val loading: Boolean = true,
    val subscriptions: List<Subscription> = emptyList(),
    val rawEpisodes: List<InboxEpisode> = emptyList(),
    val completedIds: Set<String> = emptySet(),
    val downloadedOnly: Boolean = false,
    val downloadedIds: Set<String> = emptySet(),
    val downloadStates: Map<String, DownloadState> = emptyMap(),
    val downloadProgress: Map<String, Int> = emptyMap(),
    val selectedPodcastId: String? = null,
    val dateRange: InboxDateRange = InboxDateRange.ALL,
    val mood: InboxMood = InboxMood.ALL,
    val hideSpecials: Boolean = false,
    val priorityPodcastIds: Set<String> = emptySet(),
    val sessionMinutes: Int? = null,
    val showSettings: Boolean = false,
    val failedFeeds: Int = 0,
    val progressByEpisode: Map<String, Int> = emptyMap(),
    val currentEpisodeId: String? = null,
    val currentEpisodePlaying: Boolean = false,
) {
    val feed: List<InboxEpisode>
        get() {
            val filtered = buildInboxFeed(
                rawEpisodes,
                completedIds,
                InboxFilter(
                    // Not a filter — the definition of the screen. "New" that can
                    // show episodes you have already heard is just the library
                    // sorted by date, and there is a tab for that.
                    unplayedOnly = true,
                    downloadedOnly = downloadedOnly,
                    podcastId = selectedPodcastId,
                    dateRange = dateRange,
                    mood = mood,
                    hideSpecials = hideSpecials,
                ),
                downloadedIds,
                priorityPodcastIds,
            )
            return sessionMinutes?.let { buildSessionPlan(filtered, it * 60_000L) } ?: filtered
        }
}

@HiltViewModel
class InboxViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val account: AccountRepository,
    private val library: LibraryRepository,
    private val podcasts: PodcastRepository,
    private val progress: ProgressRepository,
    private val queue: QueueRepository,
    private val downloads: DownloadRepository,
    private val preferences: PreferencesRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    private val filterPrefs = context.getSharedPreferences("inbox-filters", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        InboxUiState(
            downloadedOnly = filterPrefs.getBoolean("downloaded", false),
            selectedPodcastId = filterPrefs.getString("podcast", null),
            dateRange = enumPreference("date", InboxDateRange.ALL),
            mood = enumPreference("mood", InboxMood.ALL),
            sessionMinutes = filterPrefs.getInt("session", 0).takeIf { it > 0 },
            hideSpecials = filterPrefs.getBoolean("hide_specials", false),
            priorityPodcastIds = filterPrefs.getStringSet("priority", emptySet()).orEmpty().toSet(),
        ),
    )
    val state: StateFlow<InboxUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private val pendingInboxModes = mutableMapOf<String, InboxMode>()

    init {
        viewModelScope.launch {
            preferences.preferences
                .map { it.allowExplicitContent }
                .distinctUntilChanged()
                .drop(1)
                .collect { refreshFromCache() }
        }
        viewModelScope.launch {
            downloads.downloads.collect { items ->
                _state.update {
                    it.copy(
                        downloadedIds = items.filter { item -> item.state == DownloadState.DONE }
                            .mapTo(mutableSetOf()) { item -> item.episodeId },
                        downloadStates = items.associate { item -> item.episodeId to item.state },
                        downloadProgress = items.associate {
                            item -> item.episodeId to item.progressPercent
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            // Same two sources as the podcast screen: stored positions, overlaid
            // with the live player so the running episode's ring keeps moving.
            combine(progress.allProgress, player.state) { stored, playback ->
                val map = stored.associate { it.episodeId to it.progressPercent }.toMutableMap()
                val track = playback.track
                if (track != null && playback.durationMs > 0) {
                    map[track.episodeId] = ((playback.positionMs * 100) / playback.durationMs)
                        .toInt().coerceIn(0, 100)
                }
                Triple(map, track?.episodeId, playback.isPlaying)
            }.collect { (map, currentId, playing) ->
                _state.update {
                    it.copy(
                        progressByEpisode = map,
                        currentEpisodeId = currentId,
                        currentEpisodePlaying = playing,
                    )
                }
            }
        }
        viewModelScope.launch {
            // `seeded` matters for the empty library: the first emission is an empty
            // list, which compares equal to the initial state, so a pure change check
            // would never call refresh() and the screen would skeleton forever.
            var seeded = false
            combine(
                library.allSubscriptions,
                progress.completedEpisodeIds,
            ) { subscriptions, completed -> subscriptions to completed }
                .collect { (subscriptions, completed) ->
                    pendingInboxModes.entries.removeAll { (podcastId, mode) ->
                        subscriptions.firstOrNull { it.podcastId == podcastId }?.inboxMode == mode
                    }
                    val visibleSubscriptions = subscriptions.map { subscription ->
                        pendingInboxModes[subscription.podcastId]?.let { pendingMode ->
                            subscription.copy(inboxMode = pendingMode)
                        } ?: subscription
                    }
                    val idsChanged =
                        visibleSubscriptions.map { it.podcastId } !=
                            _state.value.subscriptions.map { it.podcastId }
                    _state.update {
                        it.copy(subscriptions = visibleSubscriptions, completedIds = completed)
                    }
                    if (idsChanged || !seeded) {
                        seeded = true
                        refreshFromCache()
                    }
                }
        }
    }

    fun refresh() = refresh(force = true)

    private fun refreshFromCache() = refresh(force = false)

    private fun refresh(force: Boolean) {
        _state.update { it.copy(loading = it.rawEpisodes.isEmpty(), failedFeeds = 0) }

        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            var subscriptions = _state.value.subscriptions
            val semaphore = Semaphore(MAX_CONCURRENT_FEEDS)
            val cachedByPodcast = subscriptions.associate { subscription ->
                subscription.podcastId to podcasts.cachedEpisodes(
                    podcastId = subscription.podcastId,
                    limit = PER_FEED,
                )
            }
            val cachedResults = subscriptions.map { subscription ->
                async {
                    semaphore.withPermit {
                        cachedByPodcast[subscription.podcastId]
                            ?.value.orEmpty()
                            .map { InboxEpisode(it, subscription) }
                    }
                }
            }.awaitAll()
            val cachedEpisodes = cachedResults.flatten()
            if (cachedByPodcast.values.any { it != null } || subscriptions.isEmpty()) {
                _state.update {
                    it.copy(loading = false, rawEpisodes = cachedEpisodes)
                }
            }

            account.resolvePendingSubscriptions()
            subscriptions = library.subscriptionsSnapshot()
            _state.update { it.copy(subscriptions = subscriptions) }
            if (subscriptions.isEmpty()) {
                _state.update { it.copy(loading = false, rawEpisodes = emptyList()) }
                return@launch
            }
            val results = subscriptions.map { subscription ->
                async {
                    semaphore.withPermit {
                        val cached = cachedByPodcast[subscription.podcastId]
                        if (!force && cached != null && podcasts.isFresh(cached, ContentTtl.INBOX)) {
                            return@withPermit FeedResult(
                                episodes = cached.value.map { InboxEpisode(it, subscription) },
                            )
                        }
                        when (
                            val result = podcasts.refreshEpisodesIncrementally(
                                podcastId = subscription.podcastId,
                                limit = PER_FEED,
                            )
                        ) {
                            is DataResult.Success ->
                                FeedResult(
                                    episodes = result.data.take(PER_FEED)
                                        .map { InboxEpisode(it, subscription) },
                                )
                            is DataResult.Failure -> FeedResult(
                                episodes = podcasts.cachedEpisodes(
                                    podcastId = subscription.podcastId,
                                    limit = PER_FEED,
                                )?.value.orEmpty().map { InboxEpisode(it, subscription) },
                                failed = true,
                            )
                        }
                    }
                }
            }.awaitAll()
            _state.update {
                it.copy(
                    loading = false,
                    rawEpisodes = results.flatMap(FeedResult::episodes),
                    failedFeeds = results.count(FeedResult::failed),
                )
            }
        }
    }

    fun setDownloadedOnly(enabled: Boolean) =
        _state.update { it.copy(downloadedOnly = enabled) }.also {
            filterPrefs.edit().putBoolean("downloaded", enabled).apply()
        }

    fun setPodcastFilter(podcastId: String?) =
        _state.update { it.copy(selectedPodcastId = podcastId) }.also {
            filterPrefs.edit().putString("podcast", podcastId).apply()
        }

    fun setDateRange(range: InboxDateRange) =
        _state.update { it.copy(dateRange = range) }.also {
            filterPrefs.edit().putString("date", range.name).apply()
        }

    fun setMood(mood: InboxMood) =
        _state.update { it.copy(mood = mood) }.also {
            filterPrefs.edit().putString("mood", mood.name).apply()
        }

    fun setSessionMinutes(minutes: Int?) =
        _state.update { it.copy(sessionMinutes = minutes) }.also {
            filterPrefs.edit().putInt("session", minutes ?: 0).apply()
        }

    fun setHideSpecials(enabled: Boolean) {
        _state.update { it.copy(hideSpecials = enabled) }
        filterPrefs.edit().putBoolean("hide_specials", enabled).apply()
    }

    fun togglePriority(podcastId: String) {
        _state.update { state ->
            val next = state.priorityPodcastIds.toMutableSet().apply {
                if (!add(podcastId)) remove(podcastId)
            }
            filterPrefs.edit().putStringSet("priority", next).apply()
            state.copy(priorityPodcastIds = next)
        }
    }

    fun queueSession() {
        val items = _state.value.feed
        viewModelScope.launch { items.forEach { queue.addToEnd(it.track) } }
    }

    fun toggleSettings() {
        _state.update { it.copy(showSettings = !it.showSettings) }
    }

    fun setInboxMode(podcastId: String, mode: InboxMode) {
        pendingInboxModes[podcastId] = mode
        _state.update { current ->
            current.copy(
                subscriptions = current.subscriptions.map {
                    if (it.podcastId == podcastId) it.copy(inboxMode = mode) else it
                },
                rawEpisodes = current.rawEpisodes.map {
                    if (it.subscription.podcastId == podcastId) {
                        it.copy(subscription = it.subscription.copy(inboxMode = mode))
                    } else {
                        it
                    }
                },
            )
        }
        viewModelScope.launch { library.setInboxMode(podcastId, mode) }
    }

    /**
     * Play, or pause when this is already the episode running. The row draws a
     * moving equaliser for the current episode, which reads as a pause control;
     * calling play again just stuttered and resumed the same audio.
     */
    fun play(item: InboxEpisode) {
        if (player.state.value.track?.episodeId == item.episode.id) {
            player.togglePlayPause()
        } else {
            player.play(item.track)
        }
    }

    fun addToQueue(item: InboxEpisode) {
        viewModelScope.launch { queue.addToEnd(item.track) }
    }

    fun toggleDownload(item: InboxEpisode) {
        viewModelScope.launch {
            when (_state.value.downloadStates[item.episode.id]) {
                null, DownloadState.PAUSED, DownloadState.FAILED -> {
                    val prefs = preferences.preferences.first()
                    downloads.enqueue(
                        item.track,
                        wifiOnly = prefs.downloadWifiOnly,
                        concurrency = prefs.downloadConcurrency,
                        storage = prefs.downloadStorage,
                        treeUri = prefs.downloadTreeUri,
                        budgetBytes = prefs.downloadBudgetBytes,
                    )
                }
                DownloadState.QUEUED, DownloadState.DOWNLOADING ->
                    downloads.pause(item.episode.id)
                DownloadState.DONE -> downloads.remove(item.episode.id)
            }
        }
    }

    fun togglePlayed(item: InboxEpisode) {
        val played = item.episode.id !in _state.value.completedIds
        viewModelScope.launch { progress.setPlayed(item.track, played) }
    }

    fun markAllPlayed() = mark(_state.value.feed, played = true)

    fun markThisAndOlder(episodeId: String, played: Boolean) =
        mark(episodesFrom(_state.value.feed, episodeId), played)

    private fun mark(items: List<InboxEpisode>, played: Boolean) {
        viewModelScope.launch {
            items.forEach { progress.setPlayed(it.track, played) }
        }
    }

    companion object {
        internal const val PER_FEED = 15
        private const val MAX_CONCURRENT_FEEDS = 6
    }

    private data class FeedResult(
        val episodes: List<InboxEpisode> = emptyList(),
        val failed: Boolean = false,
    )

    private inline fun <reified T : Enum<T>> enumPreference(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(filterPrefs.getString(key, null).orEmpty()) }
            .getOrDefault(fallback)
}
