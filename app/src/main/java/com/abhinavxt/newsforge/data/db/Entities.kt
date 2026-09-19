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
    /**
     * [com.abhinavxt.newsforge.core.feed.FeedKind] name of the feed this came from.
     *
     * Denormalised on purpose. Whether a row is an exchange filing decides how it is
     * rendered, and the feed it came from can be renamed, re-kinded or deleted while the
     * article outlives it — so joining back would make an old row's appearance depend on
     * a table that has moved on.
     */
    val feedKind: String = "RSS",
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
@Entity(tableName = "watchlist", primaryKeys = ["symbol", "source"])
data class WatchlistEntity(
    val symbol: String,
    /**
     * [com.abhinavxt.newsforge.core.notify.WatchSource] name, and half the key.
     *
     * A symbol can be held by both sources at once at different tiers — followed by hand
     * for a year, and bought on margin this morning — which is one fact about you, not a
     * conflict to resolve at write time. Storing both and taking the stronger at read
     * time means a closed position can never silence something you asked to follow, and
     * a desk snapshot never has to guess whether a row is safe to overwrite.
     */
    val source: String = "MANUAL",
    val addedAt: Long,
    /** [com.abhinavxt.newsforge.core.notify.WatchTier] name. */
    val tier: String = "WATCHING",
    /**
     * Share of the portfolio as a percentage, when the desk reported one.
     *
     * Null for manual rows and for a desk that sends no weights, and treated as "unknown"
     * rather than "zero" — a missing weight must not quietly demote a real position.
     */
    val weight: Double? = null,
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

/**
 * A price this app saw, and when.
 *
 * The only reason to keep these is [com.abhinavxt.newsforge.core.quote.PriceReaction]:
 * answering "what was this worth when the story broke" needs a record of the past, and
 * neither the desk nor the exchange endpoint will answer a question about 10:42 once it
 * is 11:15.
 *
 * Persisted, unlike the quotes themselves. A quote is worth showing for twenty minutes
 * and is cheap to re-fetch; this is a morning's worth of context that cannot be
 * reconstructed at all if the process is killed at lunch — which is exactly when a
 * foreground service gets evicted.
 *
 * Deliberately two columns and a key. Storing the whole quote would quadruple the table
 * to carry fields nothing reads back.
 */
@Entity(tableName = "price_sample", primaryKeys = ["symbol", "atMillis"])
data class PriceSampleEntity(
    val symbol: String,
    val atMillis: Long,
    val price: Double,
)

/**
 * Cumulative traded volume for one symbol at one mark of one session.
 *
 * Kept so that today's volume can be compared against the same point on previous days —
 * see [com.abhinavxt.newsforge.core.quote.VolumeProfile] for why a daily average cannot
 * answer the question intraday.
 *
 * Fifteen-minute marks, a couple of weeks of sessions: about twenty-five rows per symbol
 * per day, which for a feed's worth of names is a few thousand rows in total.
 */
@Entity(tableName = "volume_sample", primaryKeys = ["symbol", "sessionDay", "bucket"])
data class VolumeSampleEntity(
    val symbol: String,
    /** Epoch day, so sessions group and sort without parsing a date. */
    val sessionDay: Long,
    val bucket: Int,
    val volume: Double,
)

/**
 * One screener run's output, as the desk last reported it.
 *
 * Replaced whole per screen rather than merged: a run is a complete answer, and a name
 * missing from it is one that no longer passes. Symbols are stored delimited rather than
 * as rows because nothing ever queries by symbol here — the list is read, turned into a
 * filter, and discarded.
 */
@Entity(tableName = "screen_result")
data class ScreenResultEntity(
    @PrimaryKey val name: String,
    val symbols: String,
    val updatedAt: Long,
)

/**
 * One price bar, as the desk reported it.
 *
 * Persisted, unlike the quotes. A quote is a claim about now that is cheap to re-fetch and
 * worthless once it ages; a bar is closed history that will never change again, and
 * re-fetching it means a round trip through the bridge and a wait on the desk's next poll.
 * Caching turns reopening a company from a wait into a draw.
 *
 * Keyed on the size as well as the symbol, because daily and five-minute bars for the same
 * morning are different rows about the same minutes and neither supersedes the other.
 * REPLACE on insert for the bar still forming: a later reading of today's candle is a
 * correction, not a duplicate.
 */
@Entity(
    tableName = "candle",
    primaryKeys = ["symbol", "interval", "openTimeMillis"],
)
data class CandleEntity(
    val symbol: String,
    /** [com.abhinavxt.newsforge.core.quote.CandleInterval] wire name. */
    val interval: String,
    val openTimeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)
