package net.koalastuff.koalacast.core.data.mapper

import net.koalastuff.koalacast.core.data.db.FavoriteEntity
import net.koalastuff.koalacast.core.data.db.ListeningSessionEntity
import net.koalastuff.koalacast.core.data.db.PlaybackStateEntity
import net.koalastuff.koalacast.core.data.db.PodcastSettingsEntity
import net.koalastuff.koalacast.core.data.db.QueueItemEntity
import net.koalastuff.koalacast.core.data.db.SubscriptionEntity
import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.Favorite
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.ListeningSession
import net.koalastuff.koalacast.core.model.PlaybackProgress
import net.koalastuff.koalacast.core.model.Podcast
import net.koalastuff.koalacast.core.model.PodcastSettings
import net.koalastuff.koalacast.core.model.QueueEntry
import net.koalastuff.koalacast.core.model.Subscription
import net.koalastuff.koalacast.core.model.Track

fun SubscriptionEntity.toModel() = Subscription(
    podcastId = podcastId,
    feedUrl = feedUrl,
    title = title,
    artworkUrl = artworkUrl,
    addedAtMs = addedAt,
    inboxMode = when (inboxMode) {
        SubscriptionEntity.INBOX_MODE_LATEST -> InboxMode.LATEST
        else -> InboxMode.ALL
    },
)

fun Subscription.toEntity() = SubscriptionEntity(
    podcastId = podcastId,
    feedUrl = feedUrl,
    title = title,
    artworkUrl = artworkUrl,
    addedAt = addedAtMs,
    inboxMode = when (inboxMode) {
        InboxMode.LATEST -> SubscriptionEntity.INBOX_MODE_LATEST
        InboxMode.ALL -> SubscriptionEntity.INBOX_MODE_ALL
    },
)

fun Podcast.toSubscription(nowMs: Long) = Subscription(
    podcastId = id,
    feedUrl = feedUrl,
    title = title,
    artworkUrl = artworkUrl,
    addedAtMs = nowMs,
)

/**
 * The denormalised copy of an episode that library rows and the player read.
 * @param podcastTitle the show name, which the episode payload does not carry.
 */
fun Episode.toTrack(podcastTitle: String, fallbackArtworkUrl: String = ""): Track = Track(
    episodeId = id,
    podcastId = podcastId,
    title = title,
    podcastTitle = podcastTitle,
    artworkUrl = artworkUrl.ifBlank { fallbackArtworkUrl },
    enclosureUrl = enclosureUrl,
    durationMs = durationMs,
)

fun PlaybackStateEntity.toModel() = PlaybackProgress(
    episodeId = episodeId,
    podcastId = podcastId,
    positionMs = positionMs,
    completed = completed,
    progressPercent = progressPercent,
    lastPlayedAtMs = lastPlayedAt,
    track = enclosureUrl?.let {
        Track(
            episodeId = episodeId,
            podcastId = podcastId,
            title = title.orEmpty(),
            podcastTitle = podcastTitle.orEmpty(),
            artworkUrl = artworkUrl.orEmpty(),
            enclosureUrl = it,
            durationMs = durationMs ?: 0L,
            categories = categories,
        )
    },
)

fun QueueItemEntity.toModel() = QueueEntry(
    id = id,
    positionOrder = positionOrder,
    addedAtMs = addedAt,
    track = Track(
        episodeId = episodeId,
        podcastId = podcastId,
        title = title,
        podcastTitle = podcastTitle,
        artworkUrl = artworkUrl,
        enclosureUrl = enclosureUrl,
        durationMs = durationMs,
        categories = categories,
    ),
)

fun FavoriteEntity.toModel() = Favorite(
    episodeId = episodeId,
    addedAtMs = addedAt,
    track = enclosureUrl?.let {
        Track(
            episodeId = episodeId,
            podcastId = podcastId.orEmpty(),
            title = title.orEmpty(),
            podcastTitle = podcastTitle.orEmpty(),
            artworkUrl = artworkUrl.orEmpty(),
            enclosureUrl = it,
            durationMs = durationMs ?: 0L,
            categories = categories,
        )
    },
)

fun PodcastSettingsEntity.toModel() = PodcastSettings(
    podcastId = podcastId,
    skipIntroSeconds = skipIntroSeconds,
    skipOutroSeconds = skipOutroSeconds,
    speed = speed,
    autoQueueNew = autoQueueNew,
)

fun PodcastSettings.toEntity() = PodcastSettingsEntity(
    podcastId = podcastId,
    skipIntroSeconds = skipIntroSeconds,
    skipOutroSeconds = skipOutroSeconds,
    speed = speed,
    autoQueueNew = autoQueueNew,
)

fun ListeningSessionEntity.toModel() = ListeningSession(
    id = id,
    episodeId = episodeId,
    podcastId = podcastId,
    title = title,
    podcastTitle = podcastTitle,
    categories = categories,
    startedAtMs = startedAt,
    endedAtMs = endedAt,
    wallClockMs = wallClockMs,
    audioListenedMs = audioListenedMs,
    speedSavedMs = speedSavedMs,
    silenceSavedMs = silenceSavedMs,
    manualSkippedMs = manualSkippedMs,
    introOutroSkippedMs = introOutroSkippedMs,
    speedWeightedMs = speedWeightedMs,
)

fun ListeningSession.toEntity() = ListeningSessionEntity(
    id = id,
    episodeId = episodeId,
    podcastId = podcastId,
    title = title,
    podcastTitle = podcastTitle,
    categories = categories,
    startedAt = startedAtMs,
    endedAt = endedAtMs,
    wallClockMs = wallClockMs,
    audioListenedMs = audioListenedMs,
    speedSavedMs = speedSavedMs,
    silenceSavedMs = silenceSavedMs,
    manualSkippedMs = manualSkippedMs,
    introOutroSkippedMs = introOutroSkippedMs,
    speedWeightedMs = speedWeightedMs,
)
