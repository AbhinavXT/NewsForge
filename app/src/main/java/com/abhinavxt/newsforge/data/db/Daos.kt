package com.abhinavxt.newsforge.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Projection used to rebuild [com.abhinavxt.newsforge.core.dedupe.ClusterMember] rows. */
data class ClusterWindowRow(
    val id: String,
    val clusterId: String,
    val canonicalUrl: String,
    val tokens: String,
    val symbols: String,
    val publishedAt: Long,
)

/** One story: the cluster anchor, plus how many outlets carried it and which. */
data class StoryRow(
    @Embedded val article: ArticleEntity,
    val clusterSize: Int,
    val sources: String?,
)

/**
 * Most stories one query will return.
 *
 * Not a preference — a limit on how much can be moved through a cursor at once. Android
 * hands a query's rows over in a two-megabyte window, and with a few hundred summaries in
 * the result that window runs out partway down: the read fails outright rather than
 * slowing down, which is what "Couldn't read row N from CursorWindow" means.
 *
 * Far more than the feed shows. It leads with two stories over capped sections, so the
 * hundreds below the cut were being read, ranked and filtered to be discarded. The one
 * thing lost is a story old enough to fall past the cut that would still have outranked
 * everything above it, which retention already makes unlikely.
 */
const val FEED_LIMIT = 400

/**
 * Outlets named per story before the list is cut off.
 *
 * `GROUP_CONCAT` has no bound of its own, and a Google News query feed can put dozens of
 * outlets in one cluster — which is the same unbounded-column shape that overflowed the
 * cursor window at scale. The card shows a count and the sheet lists names; past a dozen
 * neither is reading them.
 */
const val OUTLET_LIMIT = 12

/**
 * Bars returned for one chart.
 *
 * Two years of daily history, or a couple of sessions of one-minute bars — past which a
 * phone-sized chart is drawing several bars to the pixel and the reader cannot tell them
 * apart anyway. The same cursor-window ceiling that bounds the story queries applies, and
 * these rows are small but numerous.
 */
const val CANDLE_LIMIT = 800

@Dao
interface ArticleDao {

    /**
     * Ignores rather than replaces on conflict: an article already stored keeps its
     * cluster assignment and its read/saved flags, which a REPLACE would silently wipe
     * every time the same item reappeared in a feed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticles(articles: List<ArticleEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSymbols(rows: List<ArticleSymbolEntity>)

    @Query("SELECT id FROM article WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    @Query(
        "SELECT id, clusterId, canonicalUrl, tokens, symbols, publishedAt FROM article " +
            "WHERE publishedAt >= :since"
    )
    suspend fun clusterWindow(since: Long): List<ClusterWindowRow>

    /**
     * One row per story, newest first.
     *
     * Saved rows are included regardless of [since]. They were already exempt from
     * pruning, but this query was bounded by the same cutoff — so a starred story
     * survived in the database and silently disappeared from the Saved chip after a
     * week, which is the opposite of what starring it meant.
     *
     * `a.clusterId = a.id` selects the anchor. The correlated subqueries are cheap here
     * because clusterId is indexed and the window is a few days at most.
     */
    @Query(
        "SELECT a.*, " +
            "(SELECT COUNT(*) FROM article b WHERE b.clusterId = a.clusterId) AS clusterSize, " +
            "(SELECT GROUP_CONCAT(s) FROM (SELECT DISTINCT b.sourceName AS s " +
            " FROM article b WHERE b.clusterId = a.clusterId LIMIT :outlets)) AS sources " +
            "FROM article a " +
            "WHERE a.clusterId = a.id AND (a.publishedAt >= :since OR a.saved = 1) " +
            "ORDER BY a.publishedAt DESC LIMIT :limit"
    )
    fun stories(
        since: Long,
        limit: Int = FEED_LIMIT,
        outlets: Int = OUTLET_LIMIT,
    ): Flow<List<StoryRow>>

    /** As [stories], restricted to clusters that mention one of [symbols]. */
    @Query(
        "SELECT a.*, " +
            "(SELECT COUNT(*) FROM article b WHERE b.clusterId = a.clusterId) AS clusterSize, " +
            "(SELECT GROUP_CONCAT(s) FROM (SELECT DISTINCT b.sourceName AS s " +
            " FROM article b WHERE b.clusterId = a.clusterId LIMIT :outlets)) AS sources " +
            "FROM article a " +
            "WHERE a.clusterId = a.id AND (a.publishedAt >= :since OR a.saved = 1) " +
            "AND a.clusterId IN (" +
            "  SELECT c.clusterId FROM article c " +
            "  JOIN article_symbol s ON s.articleId = c.id WHERE s.symbol IN (:symbols)" +
            ") " +
            "ORDER BY a.publishedAt DESC LIMIT :limit"
    )
    fun storiesForSymbols(
        since: Long,
        symbols: List<String>,
        limit: Int = FEED_LIMIT,
        outlets: Int = OUTLET_LIMIT,
    ): Flow<List<StoryRow>>

    @Query("SELECT DISTINCT symbol FROM article_symbol ORDER BY symbol")
    fun knownSymbols(): Flow<List<String>>

    /**
     * Stories matching free text, across a wider window than the feed shows.
     *
     * A query, not a filter over what happens to be loaded. The feed reads the most recent
     * few hundred anchors, so filtering those in memory could only ever search what was
     * already on screen — a saved story from last week was findable by scrolling and not
     * by searching for it, which is the wrong way round.
     *
     * Matches the headline, the outlet and the tickers. A ticker is matched exactly rather
     * than as a substring: "BEL" appearing inside "NIFTYBEES" is not a story about BEL,
     * and substring matching on three-letter symbols would make the tag column noise.
     *
     * LIKE rather than a full-text index, deliberately. The corpus is a week of news plus
     * ninety days for followed companies — a few thousand rows, which SQLite scans in
     * milliseconds. FTS4 would mean a virtual table, a backfill migration and a second
     * copy of every headline, to win time that is not being lost.
     */
    @Query(
        "SELECT a.*, " +
            "(SELECT COUNT(*) FROM article b WHERE b.clusterId = a.clusterId) AS clusterSize, " +
            "(SELECT GROUP_CONCAT(s) FROM (SELECT DISTINCT b.sourceName AS s " +
            " FROM article b WHERE b.clusterId = a.clusterId LIMIT :outlets)) AS sources " +
            "FROM article a " +
            "WHERE a.clusterId = a.id AND (a.publishedAt >= :since OR a.saved = 1) " +
            "AND (a.title LIKE '%' || :text || '%' ESCAPE '\\' " +
            "  OR a.summary LIKE '%' || :text || '%' ESCAPE '\\' " +
            "  OR a.sourceName LIKE '%' || :text || '%' ESCAPE '\\' " +
            "  OR EXISTS (SELECT 1 FROM article_symbol s " +
            "             WHERE s.articleId = a.id AND s.symbol = :symbol)) " +
            "ORDER BY a.publishedAt DESC LIMIT :limit"
    )
    fun search(
        /** Already wildcard-escaped by the caller — see `NewsRepository.escapeLike`. */
        text: String,
        symbol: String,
        since: Long,
        limit: Int = FEED_LIMIT,
        outlets: Int = OUTLET_LIMIT,
    ): Flow<List<StoryRow>>

    /** One-shot version, for callers that want the set rather than to watch it. */
    @Query("SELECT DISTINCT symbol FROM article_symbol")
    suspend fun distinctSymbols(): List<String>

    @Query("UPDATE article SET read = :read WHERE clusterId = :clusterId")
    suspend fun setRead(clusterId: String, read: Boolean)

    @Query("UPDATE article SET saved = :saved WHERE id = :id")
    suspend fun setSaved(id: String, saved: Boolean)

    /**
     * Ordinary pruning.
     *
     * Saved articles are exempt — retention should never delete something kept on
     * purpose — and so is coverage of anything on the watchlist, which the timeline
     * needs depth of. Those get the longer floor in [pruneToHardFloor].
     */
    @Query(
        "DELETE FROM article WHERE publishedAt < :cutoff AND saved = 0 " +
            "AND id NOT IN (" +
            "  SELECT s.articleId FROM article_symbol s " +
            "  JOIN watchlist w ON w.symbol = s.symbol" +
            ")"
    )
    suspend fun pruneOlderThan(cutoff: Long): Int

    /**
     * The hard floor, applied to everything unsaved regardless of watchlist.
     *
     * Named for what it does rather than what it spares. The old name read as "prune the
     * followed rows", which is the opposite: this is the pass that finally collects a
     * followed company's coverage once even the long retention has run out.
     */
    @Query("DELETE FROM article WHERE publishedAt < :cutoff AND saved = 0")
    suspend fun pruneToHardFloor(cutoff: Long): Int

    /**
     * Bounded like every other story query, and for the same reason.
     *
     * This one was not, which made it the last place the cursor window could still be
     * overflowed: ninety days of coverage for a heavily-reported name is exactly the
     * shape of result [FEED_LIMIT] exists to prevent, and it fails as a hard read error
     * partway down rather than as a slow screen.
     */
    @Query(
        "SELECT a.*, " +
            "(SELECT COUNT(*) FROM article b WHERE b.clusterId = a.clusterId) AS clusterSize, " +
            "(SELECT GROUP_CONCAT(s) FROM (SELECT DISTINCT b.sourceName AS s " +
            " FROM article b WHERE b.clusterId = a.clusterId LIMIT :outlets)) AS sources " +
            "FROM article a " +
            "WHERE a.clusterId = a.id " +
            "AND a.clusterId IN (" +
            "  SELECT c.clusterId FROM article c " +
            "  JOIN article_symbol s ON s.articleId = c.id WHERE s.symbol = :symbol" +
            ") " +
            "ORDER BY a.publishedAt DESC LIMIT :limit"
    )
    fun storiesForSymbol(
        symbol: String,
        limit: Int = FEED_LIMIT,
        outlets: Int = OUTLET_LIMIT,
    ): Flow<List<StoryRow>>

    @Query("SELECT COUNT(*) FROM article")
    suspend fun count(): Int
}

@Dao
interface FeedStateDao {

    @Query("SELECT * FROM feed_state WHERE feedId = :feedId")
    suspend fun get(feedId: String): FeedStateEntity?

    @Query("SELECT * FROM feed_state")
    fun observeAll(): Flow<List<FeedStateEntity>>

    @Upsert
    suspend fun upsert(state: FeedStateEntity)
}

@Dao
interface WatchlistDao {

    @Query("SELECT * FROM watchlist ORDER BY symbol")
    fun observeAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT DISTINCT symbol FROM watchlist ORDER BY symbol")
    fun observeSymbols(): Flow<List<String>>

    @Query("SELECT DISTINCT symbol FROM watchlist")
    suspend fun symbols(): List<String>

    // REPLACE, not IGNORE: a snapshot re-stating a symbol at a new tier has to land, and
    // a manual add of a symbol already followed by hand is an edit, not a duplicate.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entry: WatchlistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAll(entries: List<WatchlistEntity>)

    @Query("SELECT * FROM watchlist")
    suspend fun entries(): List<WatchlistEntity>

    @Query("SELECT * FROM watchlist WHERE source = :source")
    suspend fun entriesFrom(source: String): List<WatchlistEntity>

    @Query("UPDATE watchlist SET tier = :tier WHERE symbol = :symbol AND source = :source")
    suspend fun setTier(symbol: String, tier: String, source: String)

    @Query("DELETE FROM watchlist WHERE symbol = :symbol AND source = :source")
    suspend fun remove(symbol: String, source: String)

    /** Clears one source wholesale. A snapshot replaces its rows rather than diffing them. */
    @Query("DELETE FROM watchlist WHERE source = :source")
    suspend fun clearSource(source: String)

    /**
     * Swaps in a whole source's rows in one transaction.
     *
     * Delete-then-insert rather than a diff: the snapshot is the complete truth about
     * that source, and a half-applied one — old positions cleared, new ones not yet
     * written — would silence every alert for as long as it lasted.
     */
    @Transaction
    suspend fun replaceSource(source: String, entries: List<WatchlistEntity>) {
        clearSource(source)
        if (entries.isNotEmpty()) addAll(entries)
    }
}

@Dao
interface NotifiedDao {

    @Query("SELECT clusterId FROM notified WHERE notifiedAt >= :since")
    suspend fun recent(since: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun mark(rows: List<NotifiedEntity>)

    @Query("DELETE FROM notified WHERE notifiedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int
}

@Dao
interface FeedDao {

    @Query("SELECT * FROM feed ORDER BY position, name")
    fun observeAll(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feed ORDER BY position, name")
    suspend fun all(): List<FeedEntity>

    @Query("SELECT COUNT(*) FROM feed")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(feed: FeedEntity)

    @Upsert
    suspend fun upsertAll(feeds: List<FeedEntity>)

    @Query("UPDATE feed SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** Built-in feeds are protected here as well as in the UI. */
    @Query("DELETE FROM feed WHERE id = :id AND builtIn = 0")
    suspend fun deleteCustom(id: String): Int
}

@Dao
interface CalendarDao {

    @Query("SELECT * FROM calendar_event WHERE dateMillis >= :from ORDER BY dateMillis, symbol")
    fun observeFrom(from: Long): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_event WHERE symbol = :symbol ORDER BY dateMillis")
    fun observeForSymbol(symbol: String): Flow<List<CalendarEventEntity>>

    /**
     * Upsert rather than insert-ignore: an exchange revises a filing's wording in place,
     * and the row should follow. A *rescheduled* meeting is a different id, so it arrives
     * as a new row instead of silently replacing a date already relied on.
     */
    @Upsert
    suspend fun upsertAll(events: List<CalendarEventEntity>)

    @Query("DELETE FROM calendar_event WHERE dateMillis < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int
}

@Dao
interface DeskDao {

    @Query("SELECT * FROM desk_message ORDER BY receivedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DeskMessageEntity>>

    @Query("SELECT COUNT(*) FROM desk_message WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    /** Ignore, not replace: a redelivered message must not clear its read flag. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<DeskMessageEntity>): List<Long>

    @Query("SELECT MAX(receivedAt) FROM desk_message")
    suspend fun latestReceivedAt(): Long?

    @Query("UPDATE desk_message SET read = 1 WHERE read = 0")
    suspend fun markAllRead()

    @Query("DELETE FROM desk_message WHERE receivedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int
}

@Dao
interface MuteDao {

    @Query("SELECT * FROM mute_rule ORDER BY kind, value")
    fun observeAll(): Flow<List<MuteRuleEntity>>

    @Query("SELECT * FROM mute_rule")
    suspend fun all(): List<MuteRuleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(rule: MuteRuleEntity)

    @Query("DELETE FROM mute_rule WHERE key = :key")
    suspend fun remove(key: String)
}

@Dao
interface PriceSampleDao {

    /**
     * IGNORE rather than REPLACE: two sources can report the same symbol in the same
     * second, and the first price recorded is as good as the second. Overwriting would
     * make the row a coin toss between the desk and the exchange.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(samples: List<PriceSampleEntity>)

    @Query("SELECT * FROM price_sample WHERE atMillis >= :since ORDER BY atMillis")
    fun observeSince(since: Long): Flow<List<PriceSampleEntity>>

    @Query("DELETE FROM price_sample WHERE atMillis < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int
}

@Dao
interface VolumeSampleDao {

    /**
     * REPLACE, unlike the price samples: this is cumulative volume, so a later reading at
     * the same mark supersedes an earlier one rather than duplicating it.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<VolumeSampleEntity>)

    @Query(
        "SELECT * FROM volume_sample WHERE bucket = :bucket AND sessionDay >= :sinceDay " +
            "ORDER BY sessionDay"
    )
    suspend fun atBucket(bucket: Int, sinceDay: Long): List<VolumeSampleEntity>

    @Query("DELETE FROM volume_sample WHERE sessionDay < :cutoffDay")
    suspend fun pruneBefore(cutoffDay: Long): Int
}

@Dao
interface ScreenResultDao {

    @Query("SELECT * FROM screen_result ORDER BY name")
    fun observeAll(): Flow<List<ScreenResultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(screens: List<ScreenResultEntity>)

    @Query("DELETE FROM screen_result WHERE name = :name")
    suspend fun remove(name: String)

    @Query("DELETE FROM screen_result WHERE updatedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int
}

@Dao
interface CandleDao {

    /**
     * Ordered oldest first, which every indicator assumes.
     *
     * Bounded, like the story queries: a year of daily bars is fine, but a day of
     * one-minute bars for a name the reader keeps reopening is several hundred rows and
     * the same cursor window applies here as anywhere else.
     */
    @Query(
        "SELECT * FROM candle WHERE symbol = :symbol AND interval = :interval " +
            "AND openTimeMillis >= :since ORDER BY openTimeMillis LIMIT :limit"
    )
    fun observeSeries(
        symbol: String,
        interval: String,
        since: Long,
        limit: Int = CANDLE_LIMIT,
    ): Flow<List<CandleEntity>>

    /** REPLACE: the bar still forming arrives again each time it is re-read. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(candles: List<CandleEntity>)

    @Query("SELECT MAX(openTimeMillis) FROM candle WHERE symbol = :symbol AND interval = :interval")
    suspend fun newestOpenTime(symbol: String, interval: String): Long?

    @Query("SELECT COUNT(*) FROM candle WHERE symbol = :symbol AND interval = :interval")
    suspend fun count(symbol: String, interval: String): Int

    @Query("DELETE FROM candle WHERE interval = :interval AND openTimeMillis < :cutoff")
    suspend fun pruneOlderThan(interval: String, cutoff: Long): Int
}
