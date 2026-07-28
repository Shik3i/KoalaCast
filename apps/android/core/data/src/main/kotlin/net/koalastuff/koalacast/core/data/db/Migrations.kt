package net.koalastuff.koalacast.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `content_cache` (`cacheKey` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `storedAt` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_content_cache_storedAt` ON `content_cache` (`storedAt`)",
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `account_data_archives` (`ownerKey` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, PRIMARY KEY(`ownerKey`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `account_namespace_state` (`id` INTEGER NOT NULL, `activeOwnerKey` TEXT NOT NULL, `guestMerged` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_playback_states_completed_lastPlayedAt` ON `playback_states` (`completed`, `lastPlayedAt`)",
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Opt-in per show, so the default has to be off for everyone who already
        // had settings rows before auto-download existed.
        db.execSQL(
            "ALTER TABLE `podcast_settings` ADD COLUMN `autoDownload` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `episode_downloads` (
                `episodeId` TEXT NOT NULL,
                `podcastId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `podcastTitle` TEXT NOT NULL,
                `artworkUrl` TEXT NOT NULL,
                `enclosureUrl` TEXT NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `categories` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `bytesDownloaded` INTEGER NOT NULL,
                `totalBytes` INTEGER NOT NULL,
                `localPath` TEXT,
                `error` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`episodeId`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_downloads_state` ON `episode_downloads` (`state`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_downloads_updatedAt` ON `episode_downloads` (`updatedAt`)")
    }
}
