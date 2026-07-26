package net.koalastuff.koalacast.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.koalastuff.koalacast.core.data.repository.LibraryRepository
import net.koalastuff.koalacast.core.data.repository.PodcastRepository
import net.koalastuff.koalacast.core.data.repository.ProgressRepository
import net.koalastuff.koalacast.core.data.repository.QueueRepository
import net.koalastuff.koalacast.core.model.DataResult
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
    val showSettings: Boolean = false,
    val failedFeeds: Int = 0,
) {
    val feed: List<InboxEpisode>
        get() = buildInboxFeed(rawEpisodes, completedIds, unplayedOnly)
}

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val podcasts: PodcastRepository,
    private val progress: ProgressRepository,
    private val queue: QueueRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                library.allSubscriptions,
                progress.completedEpisodeIds,
            ) { subscriptions, completed -> subscriptions to completed }
                .collect { (subscriptions, completed) ->
                    val idsChanged =
                        subscriptions.map { it.podcastId } != _state.value.subscriptions.map { it.podcastId }
                    _state.update { it.copy(subscriptions = subscriptions, completedIds = completed) }
                    if (idsChanged) refresh()
                }
        }
    }

    fun refresh() {
        val subscriptions = _state.value.subscriptions
        _state.update { it.copy(loading = true, failedFeeds = 0) }
        if (subscriptions.isEmpty()) {
            _state.update { it.copy(loading = false, rawEpisodes = emptyList()) }
            return
        }

        viewModelScope.launch {
            val semaphore = Semaphore(MAX_CONCURRENT_FEEDS)
            val results = subscriptions.map { subscription ->
                async {
                    semaphore.withPermit {
                        when (
                            val result = podcasts.episodes(
                                podcastId = subscription.podcastId,
                                limit = PER_FEED,
                            )
                        ) {
                            is DataResult.Success ->
                                FeedResult(
                                    episodes = result.data.take(PER_FEED)
                                        .map { InboxEpisode(it, subscription) },
                                )
                            is DataResult.Failure -> FeedResult(failed = true)
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
