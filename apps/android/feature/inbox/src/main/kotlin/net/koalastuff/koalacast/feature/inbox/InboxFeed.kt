package net.koalastuff.koalacast.feature.inbox

import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.Subscription
import net.koalastuff.koalacast.core.model.Track

enum class InboxDateRange(val ageMs: Long?) {
    ALL(null),
    TODAY(24L * 60 * 60 * 1_000),
    WEEK(7L * 24 * 60 * 60 * 1_000),
    MONTH(31L * 24 * 60 * 60 * 1_000),
}

enum class InboxMood { ALL, FOCUS, LEARN, UNWIND, ENERGIZE }

data class InboxFilter(
    val unplayedOnly: Boolean = true,
    val downloadedOnly: Boolean = false,
    val podcastId: String? = null,
    val dateRange: InboxDateRange = InboxDateRange.ALL,
    val mood: InboxMood = InboxMood.ALL,
    val hideSpecials: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
)

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
    filter: InboxFilter,
    downloadedIds: Set<String> = emptySet(),
    priorityPodcastIds: Set<String> = emptySet(),
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
            if (filter.unplayedOnly) feed.filterNot { it.episode.id in completedIds } else feed
        }
        .filter { !filter.downloadedOnly || it.episode.id in downloadedIds }
        .filter { filter.podcastId == null || it.subscription.podcastId == filter.podcastId }
        .filter {
            val age = filter.dateRange.ageMs
            age == null || (it.episode.hasPubDate && it.episode.pubDateMs >= filter.nowMs - age)
        }
        .filter { filter.mood == InboxMood.ALL || filter.mood in moodsFor(it) }
        .filter { !filter.hideSpecials || !it.episode.isSpecial() }
        .sortedWith(
            compareByDescending<InboxEpisode> {
                it.subscription.podcastId in priorityPodcastIds
            }.thenByDescending { it.episode.pubDateMs },
        )

internal fun buildInboxFeed(
    episodes: List<InboxEpisode>,
    completedIds: Set<String>,
    unplayedOnly: Boolean,
): List<InboxEpisode> = buildInboxFeed(
    episodes,
    completedIds,
    InboxFilter(unplayedOnly = unplayedOnly),
)

internal fun buildSessionPlan(feed: List<InboxEpisode>, budgetMs: Long): List<InboxEpisode> {
    if (budgetMs <= 0 || feed.isEmpty()) return feed
    val selected = mutableListOf<InboxEpisode>()
    var remaining = budgetMs
    feed.forEach { item ->
        val duration = item.episode.durationMs
        if (duration in 1..remaining) {
            selected += item
            remaining -= duration
        }
    }
    return selected.ifEmpty { listOf(feed.minBy { it.episode.durationMs.coerceAtLeast(1) }) }
}

internal fun moodsFor(item: InboxEpisode): Set<InboxMood> {
    val text = "${item.episode.title} ${item.episode.description} ${item.subscription.title}".lowercase()
    val matches = buildSet {
        if (listOf("tech", "science", "business", "focus", "productiv").any(text::contains)) {
            add(InboxMood.FOCUS)
        }
        if (listOf("learn", "history", "wissen", "erklär", "education").any(text::contains)) {
            add(InboxMood.LEARN)
        }
        if (listOf("comedy", "story", "sleep", "entspann", "meditation").any(text::contains)) {
            add(InboxMood.UNWIND)
        }
        if (listOf("sport", "news", "fitness", "daily", "morgen").any(text::contains)) {
            add(InboxMood.ENERGIZE)
        }
    }
    return matches.ifEmpty { setOf(InboxMood.FOCUS, InboxMood.UNWIND) }
}

private fun Episode.isSpecial(): Boolean {
    val text = "$title $episodeType".lowercase()
    return episodeType.lowercase() in setOf("trailer", "bonus") ||
        listOf("trailer", "teaser", "preview", "bonus", "vorschau").any(text::contains)
}

internal fun episodesFrom(
    feed: List<InboxEpisode>,
    episodeId: String,
): List<InboxEpisode> {
    val index = feed.indexOfFirst { it.episode.id == episodeId }
    return if (index < 0) emptyList() else feed.drop(index)
}
