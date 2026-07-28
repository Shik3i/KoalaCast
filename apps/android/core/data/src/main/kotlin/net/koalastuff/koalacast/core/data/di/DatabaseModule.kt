package net.koalastuff.koalacast.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.koalastuff.koalacast.core.data.db.FavoriteDao
import net.koalastuff.koalacast.core.data.db.KoalaCastDatabase
import net.koalastuff.koalacast.core.data.db.ListeningSessionDao
import net.koalastuff.koalacast.core.data.db.PlaybackStateDao
import net.koalastuff.koalacast.core.data.db.PodcastSettingsDao
import net.koalastuff.koalacast.core.data.db.QueueDao
import net.koalastuff.koalacast.core.data.db.SubscriptionDao
import net.koalastuff.koalacast.core.data.db.TombstoneDao
import net.koalastuff.koalacast.core.data.db.EpisodeDownloadDao
import net.koalastuff.koalacast.core.data.db.MIGRATION_1_2
import net.koalastuff.koalacast.core.data.db.MIGRATION_2_3
import net.koalastuff.koalacast.core.data.db.MIGRATION_3_4
import net.koalastuff.koalacast.core.data.db.MIGRATION_4_5
import net.koalastuff.koalacast.core.data.db.MIGRATION_5_6
import net.koalastuff.koalacast.core.data.db.MIGRATION_6_7
import net.koalastuff.koalacast.core.data.db.ContentCacheDao
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.data.util.SystemClock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KoalaCastDatabase =
        Room.databaseBuilder(context, KoalaCastDatabase::class.java, KoalaCastDatabase.NAME)
            // No fallbackToDestructiveMigration: this database holds the listener's
            // entire library on a device that may never have synced. Losing it to a
            // schema bump would be losing their data.
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .build()

    @Provides fun provideSubscriptionDao(db: KoalaCastDatabase): SubscriptionDao = db.subscriptionDao()
    @Provides fun providePlaybackStateDao(db: KoalaCastDatabase): PlaybackStateDao = db.playbackStateDao()
    @Provides fun provideQueueDao(db: KoalaCastDatabase): QueueDao = db.queueDao()
    @Provides fun provideFavoriteDao(db: KoalaCastDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun provideListeningSessionDao(db: KoalaCastDatabase): ListeningSessionDao = db.listeningSessionDao()
    @Provides fun provideTombstoneDao(db: KoalaCastDatabase): TombstoneDao = db.tombstoneDao()
    @Provides fun providePodcastSettingsDao(db: KoalaCastDatabase): PodcastSettingsDao = db.podcastSettingsDao()
    @Provides fun provideEpisodeDownloadDao(db: KoalaCastDatabase): EpisodeDownloadDao = db.episodeDownloadDao()
    @Provides fun provideContentCacheDao(db: KoalaCastDatabase): ContentCacheDao = db.contentCacheDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {

    @Binds
    @Singleton
    abstract fun bindClock(clock: SystemClock): Clock
}
