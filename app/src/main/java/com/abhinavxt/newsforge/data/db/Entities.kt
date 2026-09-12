package com.abhinavxt.newsforge.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One fetched article.
 *
 * [clusterId] points at the anchor article of its story group and equals [id] for the
 * anchor itself, which is what lets the feed query select one row per story with a plain
 * `WHERE clusterId = id` rather than a window function.
 *
 * [tokens] and [symbols] are stored space-joined and denormalised. The join table below
 * is the queryable form; these columns exist so the clustering window can be loaded in
 * one projection without a join on the hot insert path.
 */
@Entity(
    tableName = "article",
    indices = [
        Index("clusterId"),
        Index("publishedAt"),
        Index("feedId"),
    ],
)
data class ArticleEntity(
    @PrimaryKey val id: String,
    val clusterId: String,
    val feedId: String,
    val title: String,
    val summary: String?,
    val link: String,
    val canonicalUrl: String,
    val sourceName: String,
    val category: String,
    val tier: String,
    val publishedAt: Long,
    val fetchedAt: Long,
    val hadPublishedDate: Boolean,
    val tokens: String,
    val symbols: String,
    val sectors: String = "",
    val read: Boolean = false,
    val saved: Boolean = false,
)

/**
 * Article-to-symbol edges.
 *
 * A separate table rather than a delimited column so that watchlist filtering is an
 * indexed join. The delimited alternative forces `LIKE '%RELIANCE%'`, which scans the
 * whole table and also matches RELIANCEIND.
 */
@Entity(
    tableName = "article_symbol",
    primaryKeys = ["articleId", "symbol"],
    indices = [Index("symbol")],
    foreignKeys = [
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["articleId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ArticleSymbolEntity(
    val articleId: String,
    val symbol: String,
)

/**
 * Per-feed polling state and health.
 *
 * The validators make conditional requests possible; the rest exists so a feed that has
 * quietly died is visible in settings instead of just being absent from the feed. Feed
 * URLs rot, and a reader that hides that is worse than one that has fewer feeds.
 */
@Entity(tableName = "feed_state")
data class FeedStateEntity(
    @PrimaryKey val feedId: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastStatus: Int? = null,
    val lastError: String? = null,
    val lastItemCount: Int = 0,
    val consecutiveFailures: Int = 0,
)

/**
 * Symbols the user is following.
 *
 * A table rather than a preference set so it joins directly against `article_symbol` and
 * survives the same backup rules as everything else.
 */
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val symbol: String,
    val addedAt: Long,
    /** [com.abhinavxt.newsforge.core.notify.WatchTier] name. */
    val tier: String = "WATCHING",
)

/**
 * Clusters that have already produced a notification.
 *
 * Keyed on the cluster, not the article, so nine outlets carrying one story cannot
 * produce nine buzzes. Pruned alongside articles.
 */
@Entity(tableName = "notified")
data class NotifiedEntity(
    @PrimaryKey val clusterId: String,
    val notifiedAt: Long,
)

/**
 * A configured feed.
 *
 * Feeds moved out of code and into the database because feed URLs rot and the person who
 * notices is the one reading the app, not the one shipping it. [builtIn] marks the seeded
 * set: those can be disabled and edited but not deleted, so a mistyped edit is always
 * recoverable by resetting rather than reinstalling.
 */
@Entity(tableName = "feed")
data class FeedEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val tier: String,
    val kind: String = "RSS",
    val categoryHint: String?,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val position: Int = 0,
)

/**
 * A dated future obligation — results, dividend, board meeting, corporate action.
 *
 * Its own table, not a row in `article`. Everything in the article store is a report of
 * something that already happened and decays in relevance; a calendar entry becomes *more*
 * relevant as it approaches, and mixing the two would put a results date into the news
 * ranker where it does not belong.
 */
@Entity(
    tableName = "calendar_event",
    indices = [Index("symbol"), Index("dateMillis")],
)
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val type: String,
    val title: String,
    val dateMillis: Long,
    val sourceFeedId: String,
    val seenAt: Long,
)

/**
 * A message pushed from the user's own machine over ntfy.
 *
 * Its own table on purpose. Nothing here is ever joined to `article`, ranked by the news
 * ranker, or shown in the brief: an alert you asked your own system to send and a story a
 * publisher chose to print are different kinds of thing, and mixing them would rank yours
 * by a model built for headlines while displacing news it has nothing to do with.
 */
@Entity(
    tableName = "desk_message",
    indices = [Index("receivedAt")],
)
data class DeskMessageEntity(
    @PrimaryKey val id: String,
    val topic: String,
    val title: String?,
    val body: String,
    val priority: Int,
    val tags: String,
    val clickUrl: String?,
    val receivedAt: Long,
    val read: Boolean = false,
)

/** A suppression rule the user has set. */
@Entity(tableName = "mute_rule")
data class MuteRuleEntity(
    /** `KIND:lowercased value`, so the same rule cannot be added twice under two casings. */
    @PrimaryKey val key: String,
    val kind: String,
    val value: String,
    val addedAt: Long,
)
