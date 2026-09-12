package com.abhinavxt.newsforge.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ArticleEntity::class,
        ArticleSymbolEntity::class,
        FeedStateEntity::class,
        WatchlistEntity::class,
        NotifiedEntity::class,
        FeedEntity::class,
        CalendarEventEntity::class,
        DeskMessageEntity::class,
        MuteRuleEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class NewsForgeDatabase : RoomDatabase() {

    abstract fun articleDao(): ArticleDao

    abstract fun feedStateDao(): FeedStateDao

    abstract fun watchlistDao(): WatchlistDao

    abstract fun notifiedDao(): NotifiedDao

    abstract fun feedDao(): FeedDao

    abstract fun calendarDao(): CalendarDao

    abstract fun deskDao(): DeskDao

    abstract fun muteDao(): MuteDao

    companion object {

        /**
         * Adds the watchlist table.
         *
         * Written out rather than leaning on the destructive fallback: articles are a
         * disposable cache, but a watchlist is something the user typed, and wiping it on
         * a schema bump would be a real loss.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watchlist` (" +
                        "`symbol` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`symbol`))"
                )
            }
        }

        /** Adds the notification-dedupe table. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notified` (" +
                        "`clusterId` TEXT NOT NULL, `notifiedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`clusterId`))"
                )
            }
        }

        /**
         * Adds the feed table.
         *
         * Left empty; the repository seeds it from DefaultFeeds when it finds no rows, so
         * the seed lives in one place rather than being duplicated as SQL here.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `feed` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                        "`tier` TEXT NOT NULL, `categoryHint` TEXT, " +
                        "`enabled` INTEGER NOT NULL, `builtIn` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * Adds the feed parser kind.
         *
         * Defaulted in SQL rather than backfilled: every feed that existed before this
         * was RSS by definition, so the column default is the correct historical value.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `feed` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'RSS'"
                )
            }
        }

        /**
         * Adds the watchlist tier.
         *
         * Defaulted in SQL: every symbol followed before tiers existed was, by
         * definition, just being watched.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `watchlist` ADD COLUMN `tier` TEXT NOT NULL " +
                        "DEFAULT 'WATCHING'"
                )
            }
        }

        /** Adds the forward-looking event calendar. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `calendar_event` (" +
                        "`id` TEXT NOT NULL, `symbol` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `dateMillis` INTEGER NOT NULL, " +
                        "`sourceFeedId` TEXT NOT NULL, `seenAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_calendar_event_symbol` " +
                        "ON `calendar_event` (`symbol`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_calendar_event_dateMillis` " +
                        "ON `calendar_event` (`dateMillis`)"
                )
            }
        }

        /** Adds the desk bridge's own table. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `desk_message` (" +
                        "`id` TEXT NOT NULL, `topic` TEXT NOT NULL, `title` TEXT, " +
                        "`body` TEXT NOT NULL, `priority` INTEGER NOT NULL, " +
                        "`tags` TEXT NOT NULL, `clickUrl` TEXT, " +
                        "`receivedAt` INTEGER NOT NULL, `read` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_desk_message_receivedAt` " +
                        "ON `desk_message` (`receivedAt`)"
                )
            }
        }

        /**
         * Adds sector tags to articles.
         *
         * Existing rows keep an empty value rather than being backfilled: sectors are
         * derived at ingest from text the row no longer needs to store, and a week of
         * untagged history costs nothing against re-deriving two thousand rows on upgrade.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `article` ADD COLUMN `sectors` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Adds user suppression rules. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mute_rule` (" +
                        "`key` TEXT NOT NULL, `kind` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))"
                )
            }
        }

        fun build(context: Context): NewsForgeDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NewsForgeDatabase::class.java,
                "newsforge.db",
            )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                )
                // Cascade deletes on article_symbol only fire with this on; SQLite has
                // foreign keys off by default and Room does not enable it for you.
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys=ON")
                    }
                })
                // The store is a cache of the last week of public news. If a future schema
                // change has no migration, throwing it away costs one refresh, whereas a
                // crash loop on launch costs the app.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
