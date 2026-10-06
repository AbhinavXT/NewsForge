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
     * These shipped disabled while the session handling was unreliable, which left the
     * app reading about filings in the press instead of reading the filings. They are on
     * now: the gate is handled in NseSession, a refused poll re-primes and retries once,
     * and a poll that is still refused says so in the health screen rather than
     * masquerading as a bad URL.
     *
     * A failing feed here remains cheap. It is reported and skipped, never fatal, and
     * the feed editor's Test button reports the difference between a wrong URL and a
     * rejected session.
     */
    val NSE_FEEDS: List<FeedSource> = listOf(
        FeedSource(
            "nse-announcements", "NSE announcements",
            "https://www.nseindia.com/api/corporate-announcements?index=equities",
            SourceTier.OFFICIAL, kind = FeedKind.NSE_ANNOUNCEMENT,
        ),
        FeedSource(
            "nse-corp-actions", "NSE corporate actions",
            "https://www.nseindia.com/api/corporates-corporateActions?index=equities",
            SourceTier.OFFICIAL, Category.DIVIDEND,
            kind = FeedKind.NSE_CORP_ACTION,
        ),
        FeedSource(
            "nse-board-meetings", "NSE board meetings",
            "https://www.nseindia.com/api/corporate-board-meetings?index=equities",
            SourceTier.OFFICIAL, Category.RESULTS,
            kind = FeedKind.NSE_BOARD_MEETING,
        ),
    )

    /**
     * General news for the World tab: politics, science, international and the rest.
     *
     * Every one carries a world-desk [Category] as its hint, and that hint is what puts
     * its stories on the World tab rather than in the market feed — see `Ingest`. A query
     * feed and at least one publisher feed per topic, for the same reasons as on the
     * market side: the query is broad, the publisher is fast, and either can rot without
     * leaving a topic empty.
     *
     * Publisher URLs are the well-known public ones and, like the market publishers, are
     * unverified until the health screen has shown them succeeding. Tiers are WIRE for
     * the established newsrooms and AGGREGATOR for the query feeds; nothing here is a
     * primary record in the sense an exchange filing is.
     */
    val WORLD_FEEDS: List<FeedSource> = listOf(
        // India
        FeedSource(
            "gn-india", "India top stories",
            googleNews("India news"),
            SourceTier.AGGREGATOR, Category.INDIA,
        ),
        FeedSource(
            "hindu-national", "The Hindu National",
            "https://www.thehindu.com/news/national/feeder/default.rss",
            SourceTier.WIRE, Category.INDIA,
        ),
        FeedSource(
            "ie-india", "Indian Express India",
            "https://indianexpress.com/section/india/feed/",
            SourceTier.WIRE, Category.INDIA,
        ),

        // Politics
        FeedSource(
            "gn-politics", "Politics",
            googleNews("Lok Sabha OR Parliament OR election OR BJP OR Congress politics"),
            SourceTier.AGGREGATOR, Category.POLITICS,
        ),
        FeedSource(
            "ie-politics", "Indian Express Politics",
            "https://indianexpress.com/section/political-pulse/feed/",
            SourceTier.WIRE, Category.POLITICS,
        ),
        FeedSource(
            "guardian-politics", "The Guardian Politics",
            "https://www.theguardian.com/politics/rss",
            SourceTier.WIRE, Category.POLITICS,
        ),

        // World
        FeedSource(
            "gn-world", "World news",
            googleNews("world news OR international OR UN OR summit OR diplomacy"),
            SourceTier.AGGREGATOR, Category.WORLD,
        ),
        FeedSource(
            "bbc-world", "BBC World",
            "https://feeds.bbci.co.uk/news/world/rss.xml",
            SourceTier.WIRE, Category.WORLD,
        ),
        FeedSource(
            "aljazeera", "Al Jazeera",
            "https://www.aljazeera.com/xml/rss/all.xml",
            SourceTier.WIRE, Category.WORLD,
        ),
        FeedSource(
            "guardian-world", "The Guardian World",
            "https://www.theguardian.com/world/rss",
            SourceTier.WIRE, Category.WORLD,
        ),
        FeedSource(
            "hindu-international", "The Hindu International",
            "https://www.thehindu.com/news/international/feeder/default.rss",
            SourceTier.WIRE, Category.WORLD,
        ),

        // Science
        FeedSource(
            "gn-science", "Science",
            googleNews("science research OR study OR ISRO OR NASA OR space"),
            SourceTier.AGGREGATOR, Category.SCIENCE,
        ),
        FeedSource(
            "bbc-science", "BBC Science",
            "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",
            SourceTier.WIRE, Category.SCIENCE,
        ),
        FeedSource(
            "sciencedaily", "ScienceDaily",
            "https://www.sciencedaily.com/rss/top/science.xml",
            SourceTier.WIRE, Category.SCIENCE,
        ),
        FeedSource(
            "nasa", "NASA",
            "https://www.nasa.gov/news-release/feed/",
            SourceTier.OFFICIAL, Category.SCIENCE,
        ),

        // Technology
        FeedSource(
            "gn-tech", "Technology",
            googleNews("technology OR AI OR smartphone OR startup OR cybersecurity"),
            SourceTier.AGGREGATOR, Category.TECHNOLOGY,
        ),
        FeedSource(
            "ars", "Ars Technica",
            "https://feeds.arstechnica.com/arstechnica/index",
            SourceTier.WIRE, Category.TECHNOLOGY,
        ),
        FeedSource(
            "verge", "The Verge",
            "https://www.theverge.com/rss/index.xml",
            SourceTier.WIRE, Category.TECHNOLOGY,
        ),

        // Health
        FeedSource(
            "gn-health", "Health",
            googleNews("health OR medicine OR WHO OR outbreak OR vaccine"),
            SourceTier.AGGREGATOR, Category.HEALTH,
        ),
        FeedSource(
            "bbc-health", "BBC Health",
            "https://feeds.bbci.co.uk/news/health/rss.xml",
            SourceTier.WIRE, Category.HEALTH,
        ),

        // Climate and environment
        FeedSource(
            "gn-climate", "Climate",
            googleNews("climate change OR monsoon OR heatwave OR pollution OR environment"),
            SourceTier.AGGREGATOR, Category.ENVIRONMENT,
        ),
        FeedSource(
            "guardian-environment", "The Guardian Environment",
            "https://www.theguardian.com/environment/rss",
            SourceTier.WIRE, Category.ENVIRONMENT,
        ),

        // Sports
        FeedSource(
            "gn-sports", "Sports",
            googleNews("cricket OR football OR Olympics OR tennis OR IPL"),
            SourceTier.AGGREGATOR, Category.SPORTS,
        ),
        FeedSource(
            "bbc-sport", "BBC Sport",
            "https://feeds.bbci.co.uk/sport/rss.xml",
            SourceTier.WIRE, Category.SPORTS,
        ),
        FeedSource(
            "cricinfo", "ESPNcricinfo",
            "https://www.espncricinfo.com/rss/content/story/feeds/0.xml",
            SourceTier.WIRE, Category.SPORTS,
        ),

        // Culture
        FeedSource(
            "gn-culture", "Culture",
            googleNews("film OR box office OR music OR books OR art festival"),
            SourceTier.AGGREGATOR, Category.CULTURE,
        ),
        FeedSource(
            "guardian-culture", "The Guardian Culture",
            "https://www.theguardian.com/culture/rss",
            SourceTier.WIRE, Category.CULTURE,
        ),
    )

    val ALL: List<FeedSource> = QUERY_FEEDS + PUBLISHER_FEEDS + NSE_FEEDS + WORLD_FEEDS

    private val BY_ID: Map<String, FeedSource> by lazy { ALL.associateBy { it.id } }

    /**
     * The shipped definition of a built-in feed.
     *
     * Exists so an edit to a built-in is always undoable. Without it, mistyping a URL on
     * a seeded feed leaves the only recovery path as clearing app data.
     */
    fun byId(id: String): FeedSource? = BY_ID[id]
}
