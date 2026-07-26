package net.koalastuff.koalacast.feature.inbox

import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.Subscription
import net.koalastuff.koalacast.core.model.Track

data class InboxEpisode(
    val episode: Episode,
    val subscription: Subscription,
) {
    val track = Track(
        episodeId = episode.id,
        podcastId = subscription.podcastId,
        title = episode.title,
        podcastTitle = subscription.title,
        artworkUrl = episode.artworkUrl.ifBlank { subscription.artworkUrl },
        enclosureUrl = episode.enclosureUrl,
        durationMs = episode.durationMs,
    )
}

/**
 * Applies the same ordering rules as the web inbox: the per-show mode first,
 * then the played filter, then a global newest-first sort.
 */
internal fun buildInboxFeed(
    episodes: List<InboxEpisode>,
    completedIds: Set<String>,
    unplayedOnly: Boolean,
): List<InboxEpisode> =
    episodes
        .groupBy { it.subscription.podcastId }
        .flatMap { (_, showEpisodes) ->
            val sorted = showEpisodes.sortedByDescending { it.episode.pubDateMs }
            if (showEpisodes.first().subscription.inboxMode == InboxMode.LATEST) {
                sorted.take(1)
            } else {
                sorted
            }
        }
        .let { feed ->
            if (unplayedOnly) feed.filterNot { it.episode.id in completedIds } else feed
        }
        .sortedByDescending { it.episode.pubDateMs }

internal fun episodesFrom(
    feed: List<InboxEpisode>,
    episodeId: String,
): List<InboxEpisode> {
    val index = feed.indexOfFirst { it.episode.id == episodeId }
    return if (index < 0) emptyList() else feed.drop(index)
}
