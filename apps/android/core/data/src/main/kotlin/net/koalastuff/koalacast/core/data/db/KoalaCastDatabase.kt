package net.koalastuff.koalacast.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * Categories are a short, denormalised list carried alongside a track so the
 * Profile screen can bucket listening time without refetching every feed. A
 * separate table would be three joins for something that is never queried on its
 * own, so it is stored as a delimited string.
 */
class Converters {

    @TypeConverter
    fun fromCategories(value: List<String>): String =
        value.filter { it.isNotBlank() }.joinToString(SEPARATOR)

    @TypeConverter
    fun toCategories(value: String?): List<String> =
        value?.split(SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()

    private companion object {
        /** ASCII unit separator: it cannot occur in a feed's category text. */
        const val SEPARATOR = "\u001F"
    }
}

@Database(
    entities = [
        SubscriptionEntity::class,
        PlaybackStateEntity::class,
        QueueItemEntity::class,
        FavoriteEntity::class,
        ListeningSessionEntity::class,
        TombstoneEntity::class,
        PodcastSettingsEntity::class,
        EpisodeDownloadEntity::class,
        AccountDataArchiveEntity::class,
        AccountNamespaceStateEntity::class,
        ContentCacheEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KoalaCastDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun playbackStateDao(): PlaybackStateDao
    abstract fun queueDao(): QueueDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun listeningSessionDao(): ListeningSessionDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun podcastSettingsDao(): PodcastSettingsDao
    abstract fun episodeDownloadDao(): EpisodeDownloadDao
    abstract fun accountDataArchiveDao(): AccountDataArchiveDao
    abstract fun contentCacheDao(): ContentCacheDao

    companion object {
        const val NAME = "koalacast.db"
    }
}
