package com.abhinavxt.newsforge.core.model

/**
 * How much a source is trusted to be (a) fast and (b) material.
 *
 * Official filings and regulator releases are the primary record — everything else is
 * downstream reporting of them — so they outrank even the best wire.
 */
enum class SourceTier(val label: String, val weight: Double) {
    /** Exchanges and regulators: NSE, BSE, SEBI, RBI, PIB. The primary record. */
    OFFICIAL("Official", 1.8),

    /** Wires and the major business desks. Fast, usually accurate. */
    WIRE("Wire", 1.2),

    /** Everything else, including Google News aggregation. Broad but noisy. */
    AGGREGATOR("Aggregator", 0.9),
}

/**
 * A single pollable feed.
 *
 * @param id stable key; also the foreign key used by stored articles, so never reuse an id
 *   for a different feed.
 * @param categoryHint applied when the text rules find nothing. A PIB feed is policy news
 *   even when the headline is worded blandly.
 */
data class FeedSource(
    val id: String,
    val name: String,
    val url: String,
    val tier: SourceTier,
    val categoryHint: Category? = null,
    val enabled: Boolean = true,
    /**
     * Which parser handles the response.
     *
     * Last and defaulted so that adding it did not disturb the positional call sites
     * of every feed that existed before JSON feeds did.
     */
    val kind: com.abhinavxt.newsforge.core.feed.FeedKind =
        com.abhinavxt.newsforge.core.feed.FeedKind.RSS,
)
