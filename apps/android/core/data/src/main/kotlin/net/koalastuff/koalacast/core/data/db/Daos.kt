package net.koalastuff.koalacast.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {

    @Query("SELECT * FROM subscriptions ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY addedAt DESC")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE podcastId = :podcastId")
    suspend fun get(podcastId: String): SubscriptionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE podcastId = :podcastId)")
    fun observeIsSubscribed(podcastId: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(subscription: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE podcastId = :podcastId")
    suspend fun delete(podcastId: String)

    @Query("UPDATE subscriptions SET inboxMode = :mode WHERE podcastId = :podcastId")
    suspend fun setInboxMode(podcastId: String, mode: String)

    @Query("DELETE FROM subscriptions")
    suspend fun clear()
}

@Dao
interface PlaybackStateDao {

    @Query("SELECT * FROM playback_states WHERE episodeId = :episodeId")
    suspend fun get(episodeId: String): PlaybackStateEntity?

    @Query("SELECT * FROM playback_states WHERE episodeId = :episodeId")
    fun observe(episodeId: String): Flow<PlaybackStateEntity?>

    @Query("SELECT * FROM playback_states")
    suspend fun getAll(): List<PlaybackStateEntity>

    @Query("SELECT * FROM playback_states")
    fun observeAll(): Flow<List<PlaybackStateEntity>>

    @Query("SELECT episodeId FROM playback_states WHERE completed = 1")
    fun observeCompletedIds(): Flow<List<String>>

    @Query("SELECT episodeId FROM playback_states WHERE completed = 1")
    suspend fun completedIds(): List<String>

    /**
     * "Continue listening": started, not finished, and not a stray two-second
     * tap. Mirrors the web's `getRecentPlaybackStates` thresholds exactly.
     */
    @Query(
        """
        SELECT * FROM playback_states
        WHERE completed = 0 AND positionMs > 5000 AND progressPercent < 98
        ORDER BY lastPlayedAt DESC
        LIMIT :limit
        """,
    )
    fun observeInProgress(limit: Int = 12): Flow<List<PlaybackStateEntity>>

    @Upsert
    suspend fun upsert(state: PlaybackStateEntity)

    @Query("DELETE FROM playback_states")
    suspend fun clear()
}

@Dao
interface QueueDao {

    @Query("SELECT * FROM queue ORDER BY positionOrder ASC")
    fun observeAll(): Flow<List<QueueItemEntity>>

    @Query("SELECT * FROM queue ORDER BY positionOrder ASC")
    suspend fun getAll(): List<QueueItemEntity>

    @Query("SELECT * FROM queue WHERE episodeId = :episodeId")
    suspend fun getByEpisode(episodeId: String): QueueItemEntity?

    @Query("SELECT episodeId FROM queue")
    fun observeEpisodeIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: QueueItemEntity)

    @Query("DELETE FROM queue WHERE episodeId = :episodeId")
    suspend fun deleteByEpisode(episodeId: String)

    @Query("DELETE FROM queue")
    suspend fun clear()

    @Query("UPDATE queue SET positionOrder = :order WHERE episodeId = :episodeId")
    suspend fun setOrder(episodeId: String, order: Long)

    /** Drag-to-reorder: rewrite the whole order in one transaction. */
    @Transaction
    suspend fun reorder(orderedEpisodeIds: List<String>) {
        orderedEpisodeIds.forEachIndexed { index, episodeId ->
            setOrder(episodeId, index.toLong())
        }
    }

    @Query("SELECT COALESCE(MIN(positionOrder), 0) - 1 FROM queue")
    suspend fun headOrder(): Long

    @Query("SELECT COALESCE(MAX(positionOrder), 0) + 1 FROM queue")
    suspend fun tailOrder(): Long
}

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites")
    suspend fun getAll(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE episodeId = :episodeId")
    suspend fun get(episodeId: String): FavoriteEntity?

    @Query("SELECT episodeId FROM favorites")
    fun observeEpisodeIds(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE episodeId = :episodeId)")
    fun observeIsFavorite(episodeId: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: String)

    @Query("DELETE FROM favorites")
    suspend fun clear()
}

@Dao
interface ListeningSessionDao {

    @Query("SELECT * FROM listening_sessions ORDER BY startedAt ASC")
    fun observeAll(): Flow<List<ListeningSessionEntity>>

    @Query("SELECT * FROM listening_sessions ORDER BY startedAt ASC")
    suspend fun getAll(): List<ListeningSessionEntity>

    @Query("SELECT * FROM listening_sessions WHERE id = :id")
    suspend fun get(id: String): ListeningSessionEntity?

    @Upsert
    suspend fun upsert(session: ListeningSessionEntity)

    @Query("DELETE FROM listening_sessions")
    suspend fun clear()
}

@Dao
interface TombstoneDao {

    @Query("SELECT * FROM tombstones")
    suspend fun getAll(): List<TombstoneEntity>

    @Upsert
    suspend fun upsert(tombstone: TombstoneEntity)

    @Query("DELETE FROM tombstones WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tombstones")
    suspend fun clear()
}

@Dao
interface PodcastSettingsDao {

    @Query("SELECT * FROM podcast_settings WHERE podcastId = :podcastId")
    suspend fun get(podcastId: String): PodcastSettingsEntity?

    @Query("SELECT * FROM podcast_settings WHERE podcastId = :podcastId")
    fun observe(podcastId: String): Flow<PodcastSettingsEntity?>

    @Upsert
    suspend fun upsert(settings: PodcastSettingsEntity)

    @Query("DELETE FROM podcast_settings")
    suspend fun clear()
}

@Dao
interface EpisodeDownloadDao {
    @Query("SELECT * FROM episode_downloads ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<EpisodeDownloadEntity>>

    @Query("SELECT * FROM episode_downloads WHERE episodeId = :episodeId")
    fun observe(episodeId: String): Flow<EpisodeDownloadEntity?>

    @Query("SELECT * FROM episode_downloads WHERE episodeId = :episodeId")
    suspend fun get(episodeId: String): EpisodeDownloadEntity?

    @Upsert
    suspend fun upsert(download: EpisodeDownloadEntity)

    @Query(
        "UPDATE episode_downloads SET state = :state, bytesDownloaded = :bytes, " +
            "totalBytes = :total, localPath = :path, error = :error, updatedAt = :updatedAt " +
            "WHERE episodeId = :episodeId",
    )
    suspend fun updateProgress(
        episodeId: String,
        state: String,
        bytes: Long,
        total: Long,
        path: String?,
        error: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM episode_downloads WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: String)
}
