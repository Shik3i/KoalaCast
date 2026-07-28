package net.koalastuff.koalacast.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val unplayedOnly: Boolean = true,
    val downloadedOnly: Boolean = false,
    val downloadedIds: Set<String> = emptySet(),
    val selectedPodcastId: String? = null,
    val dateRange: InboxDateRange = InboxDateRange.ALL,
    val mood: InboxMood = InboxMood.ALL,
    val sessionMinutes: Int? = null,
    val showSettings: Boolean = false,
    val failedFeeds: Int = 0,
    val progressByEpisode: Map<String, Int> = emptyMap(),
    val currentEpisodeId: String? = null,
) {
    val feed: List<InboxEpisode>
        get() {
            val filtered = buildInboxFeed(
                rawEpisodes,
                completedIds,
                InboxFilter(
                    unplayedOnly = unplayedOnly,
                    downloadedOnly = downloadedOnly,
                    podcastId = selectedPodcastId,
                    dateRange = dateRange,
                    mood = mood,
                ),
                downloadedIds,
            )
            return sessionMinutes?.let { buildSessionPlan(filtered, it * 60_000L) } ?: filtered
        }
}

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val account: AccountRepository,
    private val library: LibraryRepository,
    private val podcasts: PodcastRepository,
    private val progress: ProgressRepository,
    private val queue: QueueRepository,
    private val downloads: DownloadRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            downloads.downloads.collect { items ->
                _state.update {
                    it.copy(
                        downloadedIds = items.filter { item -> item.state == DownloadState.DONE }
                            .mapTo(mutableSetOf()) { item -> item.episodeId },
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
                map to track?.episodeId
            }.collect { (map, currentId) ->
                _state.update { it.copy(progressByEpisode = map, currentEpisodeId = currentId) }
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
                    val idsChanged =
                        subscriptions.map { it.podcastId } != _state.value.subscriptions.map { it.podcastId }
                    _state.update { it.copy(subscriptions = subscriptions, completedIds = completed) }
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

    fun setUnplayedOnly(enabled: Boolean) {
        _state.update { it.copy(unplayedOnly = enabled) }
    }

    fun setDownloadedOnly(enabled: Boolean) =
        _state.update { it.copy(downloadedOnly = enabled) }

    fun setPodcastFilter(podcastId: String?) =
        _state.update { it.copy(selectedPodcastId = podcastId) }

    fun setDateRange(range: InboxDateRange) =
        _state.update { it.copy(dateRange = range) }

    fun setMood(mood: InboxMood) =
        _state.update { it.copy(mood = mood) }

    fun setSessionMinutes(minutes: Int?) =
        _state.update { it.copy(sessionMinutes = minutes) }

    fun queueSession() {
        val items = _state.value.feed
        viewModelScope.launch { items.forEach { queue.addToEnd(it.track) } }
    }

    fun toggleSettings() {
        _state.update { it.copy(showSettings = !it.showSettings) }
    }

    fun setInboxMode(podcastId: String, mode: InboxMode) {
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

    fun play(item: InboxEpisode) = player.play(item.track)

    fun addToQueue(item: InboxEpisode) {
        viewModelScope.launch { queue.addToEnd(item.track) }
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
}
