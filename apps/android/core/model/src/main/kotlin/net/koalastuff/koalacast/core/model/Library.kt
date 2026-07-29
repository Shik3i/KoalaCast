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

data class TimeBookmark(
    val id: String,
    val episodeId: String,
    val positionMs: Long,
    val label: String,
    val createdAtMs: Long,
)

data class NamedQueue(
    val id: String,
    val name: String,
    val itemCount: Int,
    val updatedAtMs: Long,
)

/** Per-show playback overrides. */
data class PodcastSettings(
    val podcastId: String,
    val skipIntroSeconds: Int = 0,
    val skipOutroSeconds: Int = 0,
    /** null means "use the global default speed". */
    val speed: Float? = null,
    /** null means "use the global audio-processing preference". */
    val volumeBoost: Boolean? = null,
    /** null means "use the global audio-processing preference". */
    val skipSilence: Boolean? = null,
    val autoQueueNew: Boolean = false,
    /** Notify when the background refresh finds genuinely new episodes. */
    val notifyNewEpisodes: Boolean = false,
    /**
     * Opt in, per show: fetch the newest episodes as they appear. Off by default
     * because downloading on someone's behalf spends their storage and, on a
     * metered connection, their money.
     */
    val autoDownload: Boolean = false,
    val updatedAt: Long = 0,
)

/**
 * When an automatically downloaded episode may be deleted again. Applies only to
 * downloads; a subscription or playback position is never touched by this.
 */
enum class DownloadRetention(val id: String) {
    /** Never delete automatically — the listener cleans up by hand. */
    KEEP("keep"),
    WHEN_FINISHED("finished"),
    AFTER_7_DAYS("7d"),
    AFTER_14_DAYS("14d"),
    AFTER_30_DAYS("30d"),
    ;

    /** Null when the rule is not time-based. */
    val maxAgeMs: Long?
        get() = when (this) {
            KEEP, WHEN_FINISHED -> null
            AFTER_7_DAYS -> 7L * 24 * 60 * 60 * 1000
            AFTER_14_DAYS -> 14L * 24 * 60 * 60 * 1000
            AFTER_30_DAYS -> 30L * 24 * 60 * 60 * 1000
        }

    companion object {
        val DEFAULT = KEEP

        fun fromId(value: String?): DownloadRetention =
            entries.firstOrNull { it.id == value } ?: DEFAULT
    }
}

enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED }

data class EpisodeDownload(
    val episodeId: String,
    val track: Track,
    val state: DownloadState,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val localPath: String?,
    val error: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) {
            ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
}

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
