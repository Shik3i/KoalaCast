package net.koalastuff.koalacast.core.model

/** How a subscribed show feeds the inbox. */
enum class InboxMode { ALL, LATEST }

data class Subscription(
    val podcastId: String,
    val feedUrl: String,
    val title: String,
    val artworkUrl: String,
    val addedAtMs: Long,
    val inboxMode: InboxMode = InboxMode.ALL,
)

/**
 * Everything needed to render a row and start playback without another request.
 * Queue items, favourites and in-progress episodes all reduce to this, which is
 * what lets the library work with no network at all.
 */
data class Track(
    val episodeId: String,
    val podcastId: String,
    val title: String,
    val podcastTitle: String,
    val artworkUrl: String,
    val enclosureUrl: String,
    val durationMs: Long,
    val categories: List<String> = emptyList(),
)

data class PlaybackProgress(
    val episodeId: String,
    val podcastId: String,
    val positionMs: Long,
    val completed: Boolean,
    val progressPercent: Int,
    val lastPlayedAtMs: Long,
    val track: Track?,
) {
    val remainingMs: Long
        get() = ((track?.durationMs ?: 0L) - positionMs).coerceAtLeast(0L)
}

data class QueueEntry(
    val id: String,
    val track: Track,
    val positionOrder: Long,
    val addedAtMs: Long,
)

data class Favorite(
    val episodeId: String,
    val addedAtMs: Long,
    val track: Track?,
)

/** Per-show playback overrides. */
data class PodcastSettings(
    val podcastId: String,
    val skipIntroSeconds: Int = 0,
    val skipOutroSeconds: Int = 0,
    /** null means "use the global default speed". */
    val speed: Float? = null,
    val autoQueueNew: Boolean = false,
)

/** One uninterrupted play segment; the raw material for the Profile screen. */
data class ListeningSession(
    val id: String,
    val episodeId: String,
    val podcastId: String,
    val title: String,
    val podcastTitle: String,
    val categories: List<String>,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val wallClockMs: Long,
    val audioListenedMs: Long,
    val speedSavedMs: Long,
    val silenceSavedMs: Long,
    val manualSkippedMs: Long,
    val introOutroSkippedMs: Long,
    val speedWeightedMs: Long,
)
