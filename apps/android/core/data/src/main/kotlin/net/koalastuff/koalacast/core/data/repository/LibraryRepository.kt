package net.koalastuff.koalacast.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.koalastuff.koalacast.core.data.db.FavoriteEntity
import net.koalastuff.koalacast.core.data.db.FavoriteDao
import net.koalastuff.koalacast.core.data.db.PodcastSettingsDao
import net.koalastuff.koalacast.core.data.db.SubscriptionDao
import net.koalastuff.koalacast.core.data.db.SubscriptionEntity
import net.koalastuff.koalacast.core.data.db.TombstoneDao
import net.koalastuff.koalacast.core.data.db.TombstoneEntity
import net.koalastuff.koalacast.core.data.db.TimeBookmarkDao
import net.koalastuff.koalacast.core.data.db.TimeBookmarkEntity
import net.koalastuff.koalacast.core.data.mapper.toEntity
import net.koalastuff.koalacast.core.data.mapper.toModel
import net.koalastuff.koalacast.core.data.mapper.toSubscription
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.model.Favorite
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.Podcast
import net.koalastuff.koalacast.core.model.PodcastSettings
import net.koalastuff.koalacast.core.model.Subscription
import net.koalastuff.koalacast.core.model.Track
import net.koalastuff.koalacast.core.model.TimeBookmark
import net.koalastuff.koalacast.core.network.dto.OpmlImportedPodcast
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscriptions, favourites and per-show settings — all on-device, all usable
 * with no account. Every write that *removes* something also leaves a tombstone,
 * because an upsert-only sync would otherwise resurrect it from another device.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val subscriptions: SubscriptionDao,
    private val favorites: FavoriteDao,
    private val timeBookmarks: TimeBookmarkDao,
    private val tombstones: TombstoneDao,
    private val podcastSettings: PodcastSettingsDao,
    private val clock: Clock,
) {

    // ---- Subscriptions ----

    val allSubscriptions: Flow<List<Subscription>> =
        subscriptions.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun subscriptionsSnapshot(): List<Subscription> =
        subscriptions.getAll().map { it.toModel() }

    fun isSubscribed(podcastId: String): Flow<Boolean> =
        subscriptions.observeIsSubscribed(podcastId)

    suspend fun subscribe(podcast: Podcast) {
        subscriptions.upsert(podcast.toSubscription(clock.nowMs()).toEntity())
        // Re-subscribing must clear the old tombstone, or the next sync would
        // delete the subscription again.
        tombstones.delete(TombstoneEntity.idFor(TombstoneEntity.TYPE_SUBSCRIPTION, podcast.id))
    }

    suspend fun subscribeImported(
        podcastId: String,
        feedUrl: String,
        title: String,
        artworkUrl: String,
    ) {
        subscriptions.upsert(
            SubscriptionEntity(
                podcastId = podcastId,
                feedUrl = feedUrl,
                title = title.ifBlank { "Podcast" },
                artworkUrl = artworkUrl,
                addedAt = clock.nowMs(),
            ),
        )
        tombstones.delete(TombstoneEntity.idFor(TombstoneEntity.TYPE_SUBSCRIPTION, podcastId))
    }

    suspend fun subscribeImported(feeds: List<Pair<String, String>>) {
        if (feeds.isEmpty()) return
        val now = clock.nowMs()
        feeds.forEachIndexed { index, (feedUrl, title) ->
            if (subscriptions.getByFeedUrl(feedUrl) == null) {
                subscriptions.upsert(
                    SubscriptionEntity(
                        podcastId = feedUrl,
                        feedUrl = feedUrl,
                        title = title.ifBlank { "Podcast" },
                        artworkUrl = "",
                        addedAt = now + index,
                    ),
                )
            }
        }
        tombstones.deleteAll(
            feeds.map { (feedUrl, _) ->
                TombstoneEntity.idFor(TombstoneEntity.TYPE_SUBSCRIPTION, feedUrl)
            },
        )
    }

    suspend fun subscribeResolvedImports(podcasts: List<OpmlImportedPodcast>) {
        if (podcasts.isEmpty()) return
        val now = clock.nowMs()
        podcasts.forEachIndexed { index, podcast ->
            subscriptions.upsertResolvedImport(
                sourceFeedUrl = podcast.sourceUrl.ifBlank { podcast.feedUrl },
                subscription = SubscriptionEntity(
                    podcastId = podcast.id,
                    feedUrl = podcast.feedUrl,
                    title = podcast.title.ifBlank { "Podcast" },
                    artworkUrl = podcast.artworkUrl,
                    addedAt = now + index,
                ),
            )
        }
        tombstones.deleteAll(
            podcasts.flatMap {
                listOf(
                    TombstoneEntity.idFor(TombstoneEntity.TYPE_SUBSCRIPTION, it.id),
                    TombstoneEntity.idFor(
                        TombstoneEntity.TYPE_SUBSCRIPTION,
                        it.sourceUrl.ifBlank { it.feedUrl },
                    ),
                )
            }.distinct(),
        )
    }

    suspend fun canonicalizeImportedSubscription(sourceFeedUrl: String, podcast: Podcast) {
        subscriptions.upsertResolvedImport(
            sourceFeedUrl = sourceFeedUrl,
            subscription = SubscriptionEntity(
                podcastId = podcast.id,
                feedUrl = podcast.feedUrl,
                title = podcast.title,
                artworkUrl = podcast.artworkUrl,
                addedAt = clock.nowMs(),
            ),
        )
    }

    suspend fun unsubscribe(podcastId: String) {
        subscriptions.delete(podcastId)
        tombstones.upsert(
            TombstoneEntity(
                id = TombstoneEntity.idFor(TombstoneEntity.TYPE_SUBSCRIPTION, podcastId),
                entityType = TombstoneEntity.TYPE_SUBSCRIPTION,
                entityId = podcastId,
                deletedAt = clock.nowMs(),
            ),
        )
    }

    suspend fun setInboxMode(podcastId: String, mode: InboxMode) {
        subscriptions.setInboxMode(
            podcastId,
            when (mode) {
                InboxMode.LATEST -> SubscriptionEntity.INBOX_MODE_LATEST
                InboxMode.ALL -> SubscriptionEntity.INBOX_MODE_ALL
            },
        )
    }

    // ---- Favourites ----

    val allFavorites: Flow<List<Favorite>> =
        favorites.observeAll().map { list -> list.map { it.toModel() } }

    val favoriteEpisodeIds: Flow<Set<String>> =
        favorites.observeEpisodeIds().map { it.toSet() }

    fun isFavorite(episodeId: String): Flow<Boolean> = favorites.observeIsFavorite(episodeId)

    suspend fun addFavorite(track: Track) {
        favorites.upsert(
            FavoriteEntity(
                episodeId = track.episodeId,
                addedAt = clock.nowMs(),
                podcastId = track.podcastId,
                title = track.title,
                podcastTitle = track.podcastTitle,
                artworkUrl = track.artworkUrl,
                enclosureUrl = track.enclosureUrl,
                durationMs = track.durationMs,
                categories = track.categories,
            ),
        )
        tombstones.delete(TombstoneEntity.idFor(TombstoneEntity.TYPE_FAVORITE, track.episodeId))
    }

    suspend fun removeFavorite(episodeId: String) {
        favorites.delete(episodeId)
        tombstones.upsert(
            TombstoneEntity(
                id = TombstoneEntity.idFor(TombstoneEntity.TYPE_FAVORITE, episodeId),
                entityType = TombstoneEntity.TYPE_FAVORITE,
                entityId = episodeId,
                deletedAt = clock.nowMs(),
            ),
        )
    }

    suspend fun toggleFavorite(track: Track): Boolean {
        val nowFavorite = !favorites.contains(track.episodeId)
        if (nowFavorite) addFavorite(track) else removeFavorite(track.episodeId)
        return nowFavorite
    }

    // ---- Timestamp bookmarks ----

    fun timeBookmarks(episodeId: String): Flow<List<TimeBookmark>> =
        timeBookmarks.observeForEpisode(episodeId).map { bookmarks ->
            bookmarks.map {
                TimeBookmark(
                    id = it.id,
                    episodeId = it.episodeId,
                    positionMs = it.positionMs,
                    label = it.label,
                    createdAtMs = it.createdAt,
                )
            }
        }

    suspend fun addTimeBookmark(episodeId: String, positionMs: Long, label: String = "") {
        timeBookmarks.upsert(
            TimeBookmarkEntity(
                id = java.util.UUID.randomUUID().toString(),
                episodeId = episodeId,
                positionMs = positionMs.coerceAtLeast(0),
                label = label.trim(),
                createdAt = clock.nowMs(),
            ),
        )
    }

    suspend fun removeTimeBookmark(id: String) {
        timeBookmarks.delete(id)
    }

    // ---- Per-show settings ----

    fun podcastSettings(podcastId: String): Flow<PodcastSettings> =
        podcastSettings.observe(podcastId).map {
            it?.toModel() ?: PodcastSettings(podcastId = podcastId)
        }

    suspend fun podcastSettingsSnapshot(podcastId: String): PodcastSettings =
        podcastSettings.get(podcastId)?.toModel() ?: PodcastSettings(podcastId = podcastId)

    suspend fun savePodcastSettings(settings: PodcastSettings) {
        podcastSettings.upsert(settings.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }
}
