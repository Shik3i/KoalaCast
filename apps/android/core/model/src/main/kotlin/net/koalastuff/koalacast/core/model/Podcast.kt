package net.koalastuff.koalacast.core.model

/**
 * A show as the KoalaCast server knows it — i.e. one that has been ingested from its
 * RSS feed and therefore has a stable, server-side id.
 */
data class Podcast(
    val id: String,
    val feedUrl: String,
    val title: String,
    val description: String,
    val author: String,
    val artworkUrl: String,
    val link: String,
    val language: String,
    val explicit: Boolean?,
    val copyright: String,
    val lastSuccessfulFetchAtMs: Long,
    val episodeCount: Int,
)

/**
 * A show as discovery and search return it: an iTunes/Podcast-Index record that is not
 * necessarily ingested yet. Its [id] is the *provider's* id, not a KoalaCast podcast id —
 * resolve it through the feed URL before loading episodes.
 */
data class PodcastSummary(
    val id: String,
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String,
    val category: String,
    val categories: List<String>,
    val description: String,
    val language: String,
    val explicit: Boolean? = null,
)

fun PodcastSummary.isHiddenBy(hiddenGenres: Set<String>): Boolean {
    if (hiddenGenres.isEmpty()) return false
    val hidden = hiddenGenres.mapTo(mutableSetOf()) { it.trim().lowercase() }
    val showCategories = categories.ifEmpty { listOf(category) }
    return showCategories.any { it.trim().lowercase() in hidden }
}

fun PodcastSummary.matchesGenres(genres: Set<String>): Boolean {
    if (genres.isEmpty()) return false
    val normalized = genres.mapTo(mutableSetOf()) { it.trim().lowercase() }
    val showCategories = categories.ifEmpty { listOf(category) }
    return showCategories.any { it.trim().lowercase() in normalized }
}

fun PodcastSummary.preferenceKey(): String = when {
    feedUrl.isNotBlank() -> "feed:${feedUrl.trim().lowercase()}"
    else -> "id:${id.trim().lowercase()}"
}

fun PodcastSummary.isHiddenByPodcast(hiddenPodcasts: Set<HiddenPodcast>): Boolean =
    hiddenPodcasts.any { it.key == preferenceKey() }
