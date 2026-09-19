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
        PriceSampleEntity::class,
        VolumeSampleEntity::class,
        ScreenResultEntity::class,
        CandleEntity::class,
    ],
    version = 18,
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

    abstract fun priceSampleDao(): PriceSampleDao

    abstract fun volumeSampleDao(): VolumeSampleDao

    abstract fun screenResultDao(): ScreenResultDao

    abstract fun candleDao(): CandleDao

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

        /**
         * Lets a symbol be followed by hand and held as a position at the same time.
         *
         * The primary key becomes (symbol, source), which SQLite cannot alter in place —
         * hence the rebuild. Every existing row is MANUAL by definition: there was no
         * other way for one to get there.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watchlist_new` (" +
                        "`symbol` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, `tier` TEXT NOT NULL DEFAULT 'WATCHING', " +
                        "PRIMARY KEY(`symbol`, `source`))"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO `watchlist_new` (`symbol`, `source`, `addedAt`, `tier`) " +
                        "SELECT `symbol`, 'MANUAL', `addedAt`, `tier` FROM `watchlist`"
                )
                db.execSQL("DROP TABLE `watchlist`")
                db.execSQL("ALTER TABLE `watchlist_new` RENAME TO `watchlist`")
            }
        }

        /** Remembers prices, so a story can be measured against the moment it broke. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_sample` (" +
                        "`symbol` TEXT NOT NULL, `atMillis` INTEGER NOT NULL, " +
                        "`price` REAL NOT NULL, PRIMARY KEY(`symbol`, `atMillis`))"
                )
            }
        }

        /** Lets an alert know a six per cent position from a half per cent one. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `watchlist` ADD COLUMN `weight` REAL")
            }
        }

        /** Learns each stock's own intraday volume shape, so "busy" can mean something. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `volume_sample` (" +
                        "`symbol` TEXT NOT NULL, `sessionDay` INTEGER NOT NULL, " +
                        "`bucket` INTEGER NOT NULL, `volume` REAL NOT NULL, " +
                        "PRIMARY KEY(`symbol`, `sessionDay`, `bucket`))"
                )
            }
        }

        /**
         * Truncates summaries already stored at full length.
         *
         * The cap at ingest only helps what arrives next. A database that already holds a
         * few hundred whole articles keeps failing its reads until the rows themselves
         * shrink, so this is the half of the fix that repairs rather than prevents.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE article SET summary = substr(summary, 1, 600) " +
                        "WHERE summary IS NOT NULL AND length(summary) > 600"
                )
            }
        }

        /** Screener output from the desk, as feed filters. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `screen_result` (" +
                        "`name` TEXT NOT NULL, `symbols` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`name`))"
                )
            }
        }

        /**
         * Lets a filing be told from a press release.
         *
         * Backfilled from the feed table, which is the only place the answer currently
         * lives. Rows whose feed has since been deleted keep the default and read as
         * ordinary news — the safe direction, since a story shown as a card is merely
         * roomier than it needed to be.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `article` ADD COLUMN `feedKind` TEXT NOT NULL DEFAULT 'RSS'"
                )
                db.execSQL(
                    "UPDATE article SET feedKind = COALESCE(" +
                            "(SELECT f.kind FROM feed f WHERE f.id = article.feedId), 'RSS')"
                )
            }
        }

        /**
         * Price history, so a company can be charted rather than only read about.
         *
         * Its own table rather than a widening of `price_sample`. The two look alike and
         * are not: a sample is one number this app happened to observe while polling, at
         * whatever moment the poll landed; a bar is a closed interval the exchange
         * defines, with an open, a high and a low the app could never have derived from
         * its own sampling. Folding them together would mean a table where half the rows
         * have three null columns and nothing can tell which half it is reading.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `candle` (" +
                        "`symbol` TEXT NOT NULL, `interval` TEXT NOT NULL, " +
                        "`openTimeMillis` INTEGER NOT NULL, `open` REAL NOT NULL, " +
                        "`high` REAL NOT NULL, `low` REAL NOT NULL, `close` REAL NOT NULL, " +
                        "`volume` REAL NOT NULL, " +
                        "PRIMARY KEY(`symbol`, `interval`, `openTimeMillis`))"
                )
            }
        }

        /**
         * Every migration, in one list.
         *
         * Named rather than inlined into [build] so the migration test runs exactly what
         * ships. A test with its own copy of the list passes happily while the app skips
         * the one migration somebody forgot to register — which is the failure worth
         * catching, because it is the one nobody notices until an upgrade.
         */
        val MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
            MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
            MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
            MIGRATION_17_18,
        )


        fun build(context: Context): NewsForgeDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NewsForgeDatabase::class.java,
                "newsforge.db",
            )
                .addMigrations(*MIGRATIONS)
                // Belt and braces. Room's generated open delegate already runs this
                // itself whenever the schema declares a @ForeignKey — check
                // NewsForgeDatabase_Impl if you ever doubt it — so this is redundant
                // today. It is kept because the generated pragma is conditional on a
                // foreign key existing, and the day article_symbol's is dropped or moved
                // the cascade would go quietly off with it.
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
