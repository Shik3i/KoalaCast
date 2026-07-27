package net.koalastuff.koalacast.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
