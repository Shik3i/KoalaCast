package net.koalastuff.koalacast.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Room mirrors the web client's IndexedDB stores field for field
 * (`apps/web/src/lib/idb/db.ts`), because the two clients sync the same records
 * through the same protocol. A field that exists there and not here would be a
 * field that silently disappears when a listener switches device.
 *
 * Every timestamp and duration is milliseconds, as everywhere else in KoalaCast.
 */

@Entity(tableName = "subscriptions")
@Serializable
data class SubscriptionEntity(
    @PrimaryKey val podcastId: String,
    val feedUrl: String,
    val title: String,
    val artworkUrl: String,
    val addedAt: Long,
    /**
     * How this show appears in the inbox: `all` lists every recent episode,
     * `latest` only the newest one — which is what makes an hourly news show
     * usable instead of a flood.
     */
    val inboxMode: String = INBOX_MODE_ALL,
) {
    companion object {
        const val INBOX_MODE_ALL = "all"
        const val INBOX_MODE_LATEST = "latest"
    }
}

/**
 * Progress, plus enough denormalised track metadata to render "continue
 * listening" and resume playback with no network round-trip — which is what
 * makes resume work offline.
 */
@Entity(
    tableName = "playback_states",
    indices = [
        Index("lastPlayedAt"),
        Index(value = ["completed", "lastPlayedAt"]),
    ],
)
@Serializable
data class PlaybackStateEntity(
    @PrimaryKey val episodeId: String,
    val podcastId: String,
    val positionMs: Long,
    val completed: Boolean,
    val progressPercent: Int,
    val lastPlayedAt: Long,
    val title: String? = null,
    val podcastTitle: String? = null,
    val artworkUrl: String? = null,
    val enclosureUrl: String? = null,
    val durationMs: Long? = null,
    val categories: List<String> = emptyList(),
)

@Entity(tableName = "queue", indices = [Index("positionOrder"), Index(value = ["episodeId"], unique = true)])
@Serializable
data class QueueItemEntity(
    @PrimaryKey val id: String,
    val episodeId: String,
    val podcastId: String,
    val title: String,
    val podcastTitle: String = "",
    val artworkUrl: String = "",
    val enclosureUrl: String = "",
    val durationMs: Long = 0,
    val positionOrder: Long,
    val addedAt: Long,
    val categories: List<String> = emptyList(),
)

@Entity(tableName = "favorites", indices = [Index("addedAt")])
@Serializable
data class FavoriteEntity(
    @PrimaryKey val episodeId: String,
    val addedAt: Long,
    val podcastId: String? = null,
    val title: String? = null,
    val podcastTitle: String? = null,
    val artworkUrl: String? = null,
    val enclosureUrl: String? = null,
    val durationMs: Long? = null,
    val categories: List<String> = emptyList(),
)

/**
 * One uninterrupted play segment (play → pause/end). These are the raw records
 * the Profile screen aggregates; they never leave the device unless the listener
 * signs in and syncs.
 */
@Entity(
    tableName = "listening_sessions",
    indices = [Index("startedAt"), Index("podcastId")],
)
@Serializable
data class ListeningSessionEntity(
    @PrimaryKey val id: String,
    val episodeId: String,
    val podcastId: String,
    val title: String,
    val podcastTitle: String,
    val categories: List<String> = emptyList(),
    val startedAt: Long,
    val endedAt: Long,
    /** Real time elapsed. */
    val wallClockMs: Long,
    /** Audio consumed, i.e. wall clock × speed. */
    val audioListenedMs: Long,
    val speedSavedMs: Long = 0,
    val silenceSavedMs: Long = 0,
    val manualSkippedMs: Long = 0,
    val introOutroSkippedMs: Long = 0,
    /** Σ(wall × speed), so an average speed can be derived without the segments. */
    val speedWeightedMs: Long = 0,
)

/**
 * Deletion markers. An upsert-only sync would resurrect anything the listener
 * removed on another device, so unsubscribe and unfavourite leave a tombstone.
 */
@Entity(tableName = "tombstones")
@Serializable
data class TombstoneEntity(
    /** `entityType:entityId`. */
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val deletedAt: Long,
) {
    companion object {
        const val TYPE_SUBSCRIPTION = "subscription"
        const val TYPE_FAVORITE = "favorite"

        fun idFor(entityType: String, entityId: String) = "$entityType:$entityId"
    }
}

/** Per-show playback overrides. The web keeps these in localStorage. */
@Entity(tableName = "podcast_settings")
@Serializable
data class PodcastSettingsEntity(
    @PrimaryKey val podcastId: String,
    @ColumnInfo(defaultValue = "0") val skipIntroSeconds: Int = 0,
    @ColumnInfo(defaultValue = "0") val skipOutroSeconds: Int = 0,
    /** null means "use the global default speed". */
    val speed: Float? = null,
    @ColumnInfo(defaultValue = "0") val autoQueueNew: Boolean = false,
    @ColumnInfo(defaultValue = "0") val autoDownload: Boolean = false,
)

@Entity(tableName = "account_data_archives")
data class AccountDataArchiveEntity(
    @PrimaryKey val ownerKey: String,
    val payloadJson: String,
)

@Entity(tableName = "account_namespace_state")
data class AccountNamespaceStateEntity(
    @PrimaryKey val id: Int = 1,
    val activeOwnerKey: String,
    val guestMerged: Boolean = false,
)

@Entity(tableName = "episode_downloads", indices = [Index("state"), Index("updatedAt")])
data class EpisodeDownloadEntity(
    @PrimaryKey val episodeId: String,
    val podcastId: String,
    val title: String,
    val podcastTitle: String,
    val artworkUrl: String,
    val enclosureUrl: String,
    val durationMs: Long,
    val categories: List<String>,
    val state: String,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val localPath: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
