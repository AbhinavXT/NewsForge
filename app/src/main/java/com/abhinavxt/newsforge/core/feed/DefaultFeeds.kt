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
     * A Google News topic section, India edition.
     *
     * Different from [googleNews]: a section is Google's own editorial grouping of the
     * day's biggest stories in that subject, where a query is every outlet matching some
     * words. The section is the better front page; the query reaches further down.
     *
     * @param section one of Google's fixed section ids — WORLD, NATION, BUSINESS,
     *   TECHNOLOGY, ENTERTAINMENT, SPORTS, SCIENCE, HEALTH — or null for the front page.
     */
    fun googleNewsTopic(section: String?): String {
        val edition = "hl=en-IN&gl=IN&ceid=IN%3Aen"
        return if (section == null) {
            "https://news.google.com/rss?$edition"
        } else {
            "https://news.google.com/rss/headlines/section/topic/$section?$edition"
        }
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
            googleNews("books OR literature OR art exhibition OR theatre OR museum"),
            SourceTier.AGGREGATOR, Category.CULTURE,
        ),
        FeedSource(
            "guardian-culture", "The Guardian Culture",
            "https://www.theguardian.com/culture/rss",
            SourceTier.WIRE, Category.CULTURE,
        ),
        FeedSource(
            "guardian-books", "The Guardian Books",
            "https://www.theguardian.com/books/rss",
            SourceTier.WIRE, Category.CULTURE,
        ),

        // More depth on the original topics
        FeedSource(
            "nyt-world", "New York Times World",
            "https://rss.nytimes.com/services/xml/rss/nyt/World.xml",
            SourceTier.WIRE, Category.WORLD,
        ),
        FeedSource(
            "nature", "Nature",
            "https://www.nature.com/nature.rss",
            SourceTier.WIRE, Category.SCIENCE,
        ),
        FeedSource(
            "hindu-scitech", "The Hindu Sci-Tech",
            "https://www.thehindu.com/sci-tech/feeder/default.rss",
            SourceTier.WIRE, Category.SCIENCE,
        ),
        FeedSource(
            "bbc-tech", "BBC Technology",
            "https://feeds.bbci.co.uk/news/technology/rss.xml",
            SourceTier.WIRE, Category.TECHNOLOGY,
        ),

        // Entertainment
        FeedSource(
            "gn-entertainment-q", "Entertainment",
            googleNews("Bollywood OR box office OR web series OR OTT OR Hollywood"),
            SourceTier.AGGREGATOR, Category.ENTERTAINMENT,
        ),
        FeedSource(
            "ie-entertainment", "Indian Express Entertainment",
            "https://indianexpress.com/section/entertainment/feed/",
            SourceTier.WIRE, Category.ENTERTAINMENT,
        ),
        FeedSource(
            "bollywood-hungama", "Bollywood Hungama",
            "https://www.bollywoodhungama.com/rss/news.xml",
            SourceTier.WIRE, Category.ENTERTAINMENT,
        ),
        FeedSource(
            "variety", "Variety",
            "https://variety.com/feed/",
            SourceTier.WIRE, Category.ENTERTAINMENT,
        ),
        FeedSource(
            "bbc-entertainment", "BBC Entertainment & Arts",
            "https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml",
            SourceTier.WIRE, Category.ENTERTAINMENT,
        ),
        FeedSource(
            "guardian-film", "The Guardian Film",
            "https://www.theguardian.com/film/rss",
            SourceTier.WIRE, Category.ENTERTAINMENT,
        ),
        FeedSource(
            "guardian-music", "The Guardian Music",
            "https://www.theguardian.com/music/rss",
            SourceTier.WIRE, Category.ENTERTAINMENT,
        ),

        // Education
        FeedSource(
            "gn-education", "Education",
            googleNews("CBSE OR NEET OR JEE OR UGC OR university OR board exam OR school education"),
            SourceTier.AGGREGATOR, Category.EDUCATION,
        ),
        FeedSource(
            "ie-education", "Indian Express Education",
            "https://indianexpress.com/section/education/feed/",
            SourceTier.WIRE, Category.EDUCATION,
        ),
        FeedSource(
            "hindu-education", "The Hindu Education",
            "https://www.thehindu.com/education/feeder/default.rss",
            SourceTier.WIRE, Category.EDUCATION,
        ),
        FeedSource(
            "bbc-education", "BBC Education",
            "https://feeds.bbci.co.uk/news/education/rss.xml",
            SourceTier.WIRE, Category.EDUCATION,
        ),
        FeedSource(
            "guardian-education", "The Guardian Education",
            "https://www.theguardian.com/education/rss",
            SourceTier.WIRE, Category.EDUCATION,
        ),

        // Law and crime
        FeedSource(
            "gn-law", "Law and crime",
            googleNews("Supreme Court OR High Court verdict OR police arrest OR crime India"),
            SourceTier.AGGREGATOR, Category.LAW,
        ),
        FeedSource(
            "livelaw", "LiveLaw",
            "https://www.livelaw.in/category/top-stories/google_feeds.xml",
            SourceTier.WIRE, Category.LAW,
        ),
        FeedSource(
            "guardian-law", "The Guardian Law",
            "https://www.theguardian.com/law/rss",
            SourceTier.WIRE, Category.LAW,
        ),

        // Cities
        FeedSource(
            "gn-cities", "City news",
            googleNews("Delhi OR Mumbai OR Bengaluru OR Chennai OR Kolkata OR Hyderabad civic OR metro OR traffic"),
            SourceTier.AGGREGATOR, Category.CITIES,
        ),
        FeedSource(
            "ie-cities", "Indian Express Cities",
            "https://indianexpress.com/section/cities/feed/",
            SourceTier.WIRE, Category.CITIES,
        ),
        FeedSource(
            "hindu-cities", "The Hindu Cities",
            "https://www.thehindu.com/news/cities/feeder/default.rss",
            SourceTier.WIRE, Category.CITIES,
        ),

        // Auto
        FeedSource(
            "gn-auto", "Auto",
            googleNews("car launch OR bike launch OR electric vehicle OR EV review India"),
            SourceTier.AGGREGATOR, Category.AUTO,
        ),
        FeedSource(
            "autocar", "Autocar",
            "https://www.autocar.co.uk/rss",
            SourceTier.WIRE, Category.AUTO,
        ),
        FeedSource(
            "motor1", "Motor1",
            "https://www.motor1.com/rss/news/all/",
            SourceTier.WIRE, Category.AUTO,
        ),
        FeedSource(
            "guardian-motoring", "The Guardian Motoring",
            "https://www.theguardian.com/technology/motoring/rss",
            SourceTier.WIRE, Category.AUTO,
        ),

        // Gaming
        FeedSource(
            "gn-gaming", "Gaming",
            googleNews("video game OR PlayStation OR Xbox OR Nintendo OR esports"),
            SourceTier.AGGREGATOR, Category.GAMING,
        ),
        FeedSource(
            "eurogamer", "Eurogamer",
            "https://www.eurogamer.net/feed",
            SourceTier.WIRE, Category.GAMING,
        ),
        FeedSource(
            "polygon", "Polygon",
            "https://www.polygon.com/rss/index.xml",
            SourceTier.WIRE, Category.GAMING,
        ),
        FeedSource(
            "gamespot", "GameSpot",
            "https://www.gamespot.com/feeds/news/",
            SourceTier.WIRE, Category.GAMING,
        ),

        // Travel
        FeedSource(
            "gn-travel", "Travel",
            googleNews("travel OR tourism OR visa OR airline OR destination"),
            SourceTier.AGGREGATOR, Category.TRAVEL,
        ),
        FeedSource(
            "guardian-travel", "The Guardian Travel",
            "https://www.theguardian.com/travel/rss",
            SourceTier.WIRE, Category.TRAVEL,
        ),
        FeedSource(
            "cntraveler", "Condé Nast Traveler",
            "https://www.cntraveler.com/feed/rss",
            SourceTier.WIRE, Category.TRAVEL,
        ),

        // Food
        FeedSource(
            "gn-food", "Food",
            googleNews("food OR recipe OR restaurant OR chef OR cuisine"),
            SourceTier.AGGREGATOR, Category.FOOD,
        ),
        FeedSource(
            "guardian-food", "The Guardian Food",
            "https://www.theguardian.com/food/rss",
            SourceTier.WIRE, Category.FOOD,
        ),

        // Lifestyle
        FeedSource(
            "gn-lifestyle", "Lifestyle",
            googleNews("lifestyle OR wellness OR fitness OR fashion OR relationships"),
            SourceTier.AGGREGATOR, Category.LIFESTYLE,
        ),
        FeedSource(
            "ie-lifestyle", "Indian Express Lifestyle",
            "https://indianexpress.com/section/lifestyle/feed/",
            SourceTier.WIRE, Category.LIFESTYLE,
        ),
        FeedSource(
            "hindu-lifestyle", "The Hindu Life & Style",
            "https://www.thehindu.com/life-and-style/feeder/default.rss",
            SourceTier.WIRE, Category.LIFESTYLE,
        ),
        FeedSource(
            "guardian-lifestyle", "The Guardian Life & Style",
            "https://www.theguardian.com/lifeandstyle/rss",
            SourceTier.WIRE, Category.LIFESTYLE,
        ),

        // Opinion
        FeedSource(
            "hindu-opinion", "The Hindu Opinion",
            "https://www.thehindu.com/opinion/feeder/default.rss",
            SourceTier.WIRE, Category.OPINION,
        ),
        FeedSource(
            "guardian-opinion", "The Guardian Opinion",
            "https://www.theguardian.com/commentisfree/rss",
            SourceTier.WIRE, Category.OPINION,
        ),
    )

    /**
     * Aggregators for the World tab: services that collect other outlets' stories.
     *
     * The publisher feeds above give one newsroom's view; these give the spread. Google's
     * topic sections are its editors' pick of the day in each subject, which is the
     * closest thing to a front page the app can get without choosing outlets for you.
     * Techmeme and Hacker News do the same for technology, from opposite ends — one an
     * edited river of the press, the other what engineers are reading.
     *
     * All AGGREGATOR tier: an aggregator repeats a story it did not report, and a story
     * carried by six outlets through one aggregator is still one report.
     */
    val WORLD_AGGREGATOR_FEEDS: List<FeedSource> = listOf(
        FeedSource(
            "gn-top", "Google News top stories",
            googleNewsTopic(null),
            SourceTier.AGGREGATOR, Category.HEADLINES,
        ),
        FeedSource(
            "npr-news", "NPR News",
            "https://feeds.npr.org/1001/rss.xml",
            SourceTier.WIRE, Category.HEADLINES,
        ),
        FeedSource(
            "gn-section-nation", "Google News India",
            googleNewsTopic("NATION"),
            SourceTier.AGGREGATOR, Category.INDIA,
        ),
        FeedSource(
            "gn-section-world", "Google News World",
            googleNewsTopic("WORLD"),
            SourceTier.AGGREGATOR, Category.WORLD,
        ),
        FeedSource(
            "gn-section-science", "Google News Science",
            googleNewsTopic("SCIENCE"),
            SourceTier.AGGREGATOR, Category.SCIENCE,
        ),
        FeedSource(
            "gn-section-tech", "Google News Technology",
            googleNewsTopic("TECHNOLOGY"),
            SourceTier.AGGREGATOR, Category.TECHNOLOGY,
        ),
        FeedSource(
            "gn-section-health", "Google News Health",
            googleNewsTopic("HEALTH"),
            SourceTier.AGGREGATOR, Category.HEALTH,
        ),
        FeedSource(
            "gn-section-sports", "Google News Sports",
            googleNewsTopic("SPORTS"),
            SourceTier.AGGREGATOR, Category.SPORTS,
        ),
        FeedSource(
            "gn-section-entertainment", "Google News Entertainment",
            googleNewsTopic("ENTERTAINMENT"),
            SourceTier.AGGREGATOR, Category.ENTERTAINMENT,
        ),
        FeedSource(
            "techmeme", "Techmeme",
            "https://www.techmeme.com/feed.xml",
            SourceTier.AGGREGATOR, Category.TECHNOLOGY,
        ),
        FeedSource(
            "hn-frontpage", "Hacker News",
            "https://hnrss.org/frontpage",
            SourceTier.AGGREGATOR, Category.TECHNOLOGY,
        ),
    )

    val ALL: List<FeedSource> =
        QUERY_FEEDS + PUBLISHER_FEEDS + NSE_FEEDS + WORLD_FEEDS + WORLD_AGGREGATOR_FEEDS

    private val BY_ID: Map<String, FeedSource> by lazy { ALL.associateBy { it.id } }

    /**
     * The shipped definition of a built-in feed.
     *
     * Exists so an edit to a built-in is always undoable. Without it, mistyping a URL on
     * a seeded feed leaves the only recovery path as clearing app data.
     */
    fun byId(id: String): FeedSource? = BY_ID[id]
}
