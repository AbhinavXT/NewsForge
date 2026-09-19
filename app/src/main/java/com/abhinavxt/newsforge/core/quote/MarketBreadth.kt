package com.abhinavxt.newsforge.core.quote

import java.util.Locale

/**
 * What the market did today, from the response the app already fetches.
 *
 * [ExchangeQuotes] pulls every constituent of an index in one request and then throws all
 * but the tagged names away. The discarded rows are the only context that makes a single
 * stock's move mean anything: a defence contractor up four per cent on a day the whole
 * market is up three is a stock that did nothing in particular, and the feed has been
 * unable to say so while holding the numbers that prove it.
 *
 * This is the half of the question a news reader is actually asking. The other half — did
 * it move *after* the story — is [PriceReaction]. Together they separate a genuine
 * reaction from a rising tide.
 */
data class MarketBreadth(
    val index: String,
    /**
     * The median constituent's move, in per cent.
     *
     * Median rather than mean, and unweighted rather than by market cap. A mean is decided
     * by whichever three names are up twenty per cent, and a cap weighting turns "the
     * market" into "the largest eight companies" — which is what the headline index number
     * already is, and is not what a mid-cap should be judged against.
     */
    val medianChangePercent: Double,
    val advances: Int,
    val declines: Int,
    val unchanged: Int,
    val atMillis: Long,
) {
    val total: Int get() = advances + declines + unchanged

    /**
     * How far a stock's move sits above or below the market's, in percentage points.
     *
     * Points, not per cent, and named so. The difference between a four per cent move and
     * a three per cent one is one percentage point, and calling it "up thirty-three per
     * cent relative" would be arithmetically defensible and read as nonsense.
     */
    fun relativeTo(changePercent: Double?): Double? =
        changePercent?.let { it - medianChangePercent }

    /** `+0.3%`, with the sign always shown — a bare `0.3%` reads as a magnitude. */
    fun formattedMedian(): String =
        String.format(Locale.US, "%+.2f%%", medianChangePercent)
}

object MarketBreadths {

    /**
     * Fewer constituents than this and the median is not describing a market.
     *
     * A half-written response, or an index the endpoint does not recognise, returns a
     * handful of rows rather than an error — and a "market" computed from six stocks
     * would be rendered with the same confidence as one computed from five hundred.
     */
    const val MIN_CONSTITUENTS = 20

    /**
     * @param rows the whole index response, before it is narrowed to tagged symbols.
     * @param index the index being read, used to skip its own row — NSE lists the index
     *   among its constituents, and including it would let the thing being measured vote
     *   on the measurement.
     */
    fun fromRows(
        rows: List<Map<String, String?>>,
        index: String,
        nowMillis: Long,
    ): MarketBreadth? {
        val indexName = index.trim().uppercase(Locale.US)
        val changes = ArrayList<Double>(rows.size)
        var advances = 0
        var declines = 0
        var unchanged = 0

        for (row in rows) {
            val symbol = row["symbol"]?.trim()?.uppercase(Locale.US) ?: continue
            if (symbol == indexName) continue
            val last = number(row["lastPrice"]) ?: continue
            val previous = number(row["previousClose"]) ?: continue
            if (previous <= 0.0) continue

            val change = (last - previous) / previous * 100.0
            changes += change
            when {
                change > 0.0 -> advances++
                change < 0.0 -> declines++
                else -> unchanged++
            }
        }

        if (changes.size < MIN_CONSTITUENTS) return null
        changes.sort()
        return MarketBreadth(
            index = index.trim(),
            medianChangePercent = median(changes),
            advances = advances,
            declines = declines,
            unchanged = unchanged,
            atMillis = nowMillis,
        )
    }

    /** Assumes [sorted] is sorted; averages the middle pair on an even count. */
    private fun median(sorted: List<Double>): Double {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /** NSE sends numbers with thousands separators often enough to matter. */
    private fun number(raw: String?): Double? =
        raw?.trim()?.replace(",", "")?.toDoubleOrNull()
}
