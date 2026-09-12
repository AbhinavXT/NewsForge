package com.abhinavxt.newsforge.core.feed

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.FeedSource
import com.abhinavxt.newsforge.core.model.SourceTier
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The feed list the app starts with.
 *
 * Two kinds of source, for two different reasons.
 *
 * **Google News query feeds** are the market-wide backbone. A publisher's own RSS gives
 * you that publisher; a standing query gives you every outlet Google indexes for that
 * event type, which is what "whole market" actually requires. They are also the only way
 * to cover regulators and ministries without hard-coding government RSS endpoints that
 * change without notice.
 *
 * **Publisher feeds** exist for latency. Google's index lags the source by minutes, which
 * is irrelevant for an overnight brief and very relevant at 09:20.
 *
 * Feed URLs rot constantly, so nothing here is treated as permanent: the storage layer
 * records per-feed success, item count and last error, and the settings screen surfaces
 * them. A feed that dies should be visible, not silently absent.
 */
object DefaultFeeds {

    private const val GOOGLE_NEWS = "https://news.google.com/rss/search"

    /**
     * @param window Google News time operator. One day keeps the feed small and current;
     *   the app's own store is what provides history.
     */
    fun googleNews(query: String, window: String = "1d"): String {
        val encoded = URLEncoder.encode("$query when:$window", StandardCharsets.UTF_8.name())
        return "$GOOGLE_NEWS?q=$encoded&hl=en-IN&gl=IN&ceid=IN%3Aen"
    }

    /**
     * Standing queries, one per event type.
     *
     * Split by event rather than by publisher because the categoriser then has a strong
     * prior for free, and because a query that misses can be fixed without affecting the
     * others.
     */
    val QUERY_FEEDS: List<FeedSource> = listOf(
        FeedSource(
            "gn-results", "Results wire",
            googleNews("NSE quarterly results OR Q1 OR Q2 OR Q3 OR Q4 net profit"),
            SourceTier.AGGREGATOR, Category.RESULTS,
        ),
        FeedSource(
            "gn-orders", "Order wins",
            googleNews("company bags order OR wins contract crore India"),
            SourceTier.AGGREGATOR, Category.ORDER_WIN,
        ),
        FeedSource(
            "gn-ma", "Deals and M&A",
            googleNews("India acquisition OR merger OR stake sale crore"),
            SourceTier.AGGREGATOR, Category.MERGER,
        ),
        FeedSource(
            "gn-blocks", "Block and bulk deals",
            googleNews("block deal OR bulk deal NSE BSE"),
            SourceTier.AGGREGATOR, Category.BLOCK_DEAL,
        ),
        FeedSource(
            "gn-sebi", "SEBI",
            googleNews("site:sebi.gov.in OR SEBI order OR SEBI probe"),
            SourceTier.OFFICIAL, Category.REGULATORY,
        ),
        FeedSource(
            "gn-enforcement", "Investigations",
            googleNews("Enforcement Directorate OR CBI OR income tax raid company India"),
            SourceTier.WIRE, Category.REGULATORY,
        ),
        FeedSource(
            "gn-rbi", "RBI",
            googleNews("site:rbi.org.in OR RBI monetary policy OR repo rate"),
            SourceTier.OFFICIAL, Category.MACRO,
        ),
        FeedSource(
            "gn-pib", "Government releases",
            googleNews("site:pib.gov.in cabinet OR scheme OR policy"),
            SourceTier.OFFICIAL, Category.POLICY,
        ),
        FeedSource(
            "gn-policy", "Policy and tariffs",
            googleNews("India import duty OR export ban OR PLI scheme OR GST council"),
            SourceTier.WIRE, Category.POLICY,
        ),
        FeedSource(
            "gn-fundraise", "IPO and fundraise",
            googleNews("India IPO OR QIP OR buyback OR rights issue"),
            SourceTier.AGGREGATOR, Category.FUNDRAISE,
        ),
        FeedSource(
            "gn-ratings", "Rating actions",
            googleNews("CRISIL OR ICRA OR Moody's rating upgrade OR downgrade India"),
            SourceTier.AGGREGATOR, Category.RATING,
        ),
        FeedSource(
            "gn-global", "Global markets",
            googleNews("Fed OR FOMC OR Wall Street OR crude oil OR dollar index"),
            SourceTier.AGGREGATOR, Category.GLOBAL,
        ),
        FeedSource(
            "gn-geo", "Geopolitics",
            googleNews("sanctions OR ceasefire OR conflict oil supply trade"),
            SourceTier.AGGREGATOR, Category.GEOPOLITICS,
        ),
    )

    /**
     * Publisher feeds, for speed.
     *
     * These URLs are the well-known ones and should be treated as unverified until the
     * health screen has shown them succeeding once against the live sites. Wrong entries
     * here are cheap: a failing feed is reported and skipped, never fatal.
     */
    val PUBLISHER_FEEDS: List<FeedSource> = listOf(
        FeedSource(
            "mc-markets", "Moneycontrol Markets",
            "https://www.moneycontrol.com/rss/marketreports.xml",
            SourceTier.WIRE,
        ),
        FeedSource(
            "mc-business", "Moneycontrol Business",
            "https://www.moneycontrol.com/rss/business.xml",
            SourceTier.WIRE,
        ),
        FeedSource(
            "mc-results", "Moneycontrol Results",
            "https://www.moneycontrol.com/rss/results.xml",
            SourceTier.WIRE, Category.RESULTS,
        ),
        FeedSource(
            "et-markets", "ET Markets",
            "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms",
            SourceTier.WIRE,
        ),
        FeedSource(
            "mint-markets", "Mint Markets",
            "https://www.livemint.com/rss/markets",
            SourceTier.WIRE,
        ),
        FeedSource(
            "bl-markets", "BusinessLine Markets",
            "https://www.thehindubusinessline.com/markets/feeder/default.rss",
            SourceTier.WIRE,
        ),
        FeedSource(
            "fe-markets", "Financial Express Markets",
            "https://www.financialexpress.com/market/feed/",
            SourceTier.WIRE,
        ),
    )

    /**
     * NSE's own filing feeds — the primary record, ahead of any media report of it.
     *
     * Shipped **disabled**. The response schemas these are parsed against are known
     * (see NseFilings), but the endpoint URLs are not verified from inside this app, and
     * NSE blocks requests that arrive without a primed session. Enable one, press Test,
     * and fix the URL there if it fails — which is exactly what the feed editor is for.
     */
    val NSE_FEEDS: List<FeedSource> = listOf(
        FeedSource(
            "nse-announcements", "NSE announcements",
            "https://www.nseindia.com/api/corporate-announcements?index=equities",
            SourceTier.OFFICIAL, enabled = false, kind = FeedKind.NSE_ANNOUNCEMENT,
        ),
        FeedSource(
            "nse-corp-actions", "NSE corporate actions",
            "https://www.nseindia.com/api/corporates-corporateActions?index=equities",
            SourceTier.OFFICIAL, Category.DIVIDEND, enabled = false,
            kind = FeedKind.NSE_CORP_ACTION,
        ),
        FeedSource(
            "nse-board-meetings", "NSE board meetings",
            "https://www.nseindia.com/api/corporate-board-meetings?index=equities",
            SourceTier.OFFICIAL, Category.RESULTS, enabled = false,
            kind = FeedKind.NSE_BOARD_MEETING,
        ),
    )

    val ALL: List<FeedSource> = QUERY_FEEDS + PUBLISHER_FEEDS + NSE_FEEDS

    private val BY_ID: Map<String, FeedSource> by lazy { ALL.associateBy { it.id } }

    /**
     * The shipped definition of a built-in feed.
     *
     * Exists so an edit to a built-in is always undoable. Without it, mistyping a URL on
     * a seeded feed leaves the only recovery path as clearing app data.
     */
    fun byId(id: String): FeedSource? = BY_ID[id]
}
