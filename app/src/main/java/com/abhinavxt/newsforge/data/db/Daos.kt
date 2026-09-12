package com.abhinavxt.newsforge.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
            "(SELECT GROUP_CONCAT(DISTINCT b.sourceName) FROM article b " +
            " WHERE b.clusterId = a.clusterId) AS sources " +
            "FROM article a " +
            "WHERE a.clusterId = a.id AND (a.publishedAt >= :since OR a.saved = 1) " +
            "ORDER BY a.publishedAt DESC"
    )
    fun stories(since: Long): Flow<List<StoryRow>>

    /** As [stories], restricted to clusters that mention one of [symbols]. */
    @Query(
        "SELECT a.*, " +
            "(SELECT COUNT(*) FROM article b WHERE b.clusterId = a.clusterId) AS clusterSize, " +
            "(SELECT GROUP_CONCAT(DISTINCT b.sourceName) FROM article b " +
            " WHERE b.clusterId = a.clusterId) AS sources " +
            "FROM article a " +
            "WHERE a.clusterId = a.id AND (a.publishedAt >= :since OR a.saved = 1) " +
            "AND a.clusterId IN (" +
            "  SELECT c.clusterId FROM article c " +
            "  JOIN article_symbol s ON s.articleId = c.id WHERE s.symbol IN (:symbols)" +
            ") " +
            "ORDER BY a.publishedAt DESC"
    )
    fun storiesForSymbols(since: Long, symbols: List<String>): Flow<List<StoryRow>>

    @Query("SELECT DISTINCT symbol FROM article_symbol ORDER BY symbol")
    fun knownSymbols(): Flow<List<String>>

    @Query("UPDATE article SET read = :read WHERE clusterId = :clusterId")
    suspend fun setRead(clusterId: String, read: Boolean)

    @Query("UPDATE article SET saved = :saved WHERE id = :id")
    suspend fun setSaved(id: String, saved: Boolean)

    /**
     * Ordinary pruning.
     *
     * Saved articles are exempt — retention should never delete something kept on
     * purpose — and so is coverage of anything on the watchlist, which the timeline
     * needs depth of. Those get the longer floor in [pruneFollowedOlderThan].
     */
    @Query(
        "DELETE FROM article WHERE publishedAt < :cutoff AND saved = 0 " +
            "AND id NOT IN (" +
            "  SELECT s.articleId FROM article_symbol s " +
            "  JOIN watchlist w ON w.symbol = s.symbol" +
            ")"
    )
    suspend fun pruneOlderThan(cutoff: Long): Int

    /** The hard floor, applied to everything unsaved regardless of watchlist. */
    @Query("DELETE FROM article WHERE publishedAt < :cutoff AND saved = 0")
    suspend fun pruneFollowedOlderThan(cutoff: Long): Int

    @Query(
        "SELECT a.*, " +
            "(SELECT COUNT(*) FROM article b WHERE b.clusterId = a.clusterId) AS clusterSize, " +
            "(SELECT GROUP_CONCAT(DISTINCT b.sourceName) FROM article b " +
            " WHERE b.clusterId = a.clusterId) AS sources " +
            "FROM article a " +
            "WHERE a.clusterId = a.id " +
            "AND a.clusterId IN (" +
            "  SELECT c.clusterId FROM article c " +
            "  JOIN article_symbol s ON s.articleId = c.id WHERE s.symbol = :symbol" +
            ") " +
            "ORDER BY a.publishedAt DESC"
    )
    fun storiesForSymbol(symbol: String): Flow<List<StoryRow>>

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

    @Query("SELECT symbol FROM watchlist ORDER BY symbol")
    fun observeSymbols(): Flow<List<String>>

    @Query("SELECT symbol FROM watchlist")
    suspend fun symbols(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entry: WatchlistEntity)

    @Query("SELECT * FROM watchlist")
    suspend fun entries(): List<WatchlistEntity>

    @Query("UPDATE watchlist SET tier = :tier WHERE symbol = :symbol")
    suspend fun setTier(symbol: String, tier: String)

    @Query("DELETE FROM watchlist WHERE symbol = :symbol")
    suspend fun remove(symbol: String)
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
