package com.abhinavxt.newsforge.core.quote

import com.abhinavxt.newsforge.core.tag.Sector
import java.util.Locale

/**
 * What one sector's classified constituents did today.
 *
 * Deliberately not called a sector index. NSE publishes real ones — NIFTY BANK, NIFTY IT
 * — and this is not them: it is the median of the constituents of the broad index that
 * this app can classify, which is a smaller and differently-weighted set. Fetching the
 * real sector indices would be sixteen more requests per poll against an endpoint that
 * already blocks phones for less.
 *
 * [constituents] is therefore part of the reading rather than a diagnostic. "Pharma +1.8%
 * from 5 names" is a claim a reader can weigh; "Pharma +1.8%" alone invites them to trust
 * it as though it were the index.
 */
data class SectorBreadth(
    val sector: Sector,
    val medianChangePercent: Double,
    val advances: Int,
    val declines: Int,
    val unchanged: Int,
    /** How many classified names the median was taken over. */
    val constituents: Int,
    val atMillis: Long,
) {
    /** `+0.3%`, sign always shown — a bare `0.3%` reads as a magnitude. */
    fun formattedMedian(): String =
        String.format(Locale.US, "%+.2f%%", medianChangePercent)

    /**
     * How the sector sits against the market, in percentage points.
     *
     * The number that matters. Pharma up 1.2% on a day the market is up 1.1% is pharma
     * doing nothing, and the sector figure on its own cannot say so.
     */
    fun relativeTo(market: MarketBreadth?): Double? =
        market?.let { medianChangePercent - it.medianChangePercent }
}

object SectorBreadths {

    /**
     * Below this the median is describing a handful of companies, not a sector.
     *
     * Far lower than [MarketBreadths.MIN_CONSTITUENTS], and for a reason that is not
     * laxity: the broad index is five hundred names of which this app can classify a
     * couple of hundred, so a sector holding twenty classified constituents is one of the
     * largest. A floor set at the market's would report nothing for any sector at all,
     * which is worse than reporting four names and saying that it is four.
     */
    const val MIN_CONSTITUENTS = 4

    /**
     * @param rows the whole index response, before it is narrowed to tagged symbols.
     * @param sectorOf classifier; returning null drops the row, which is the common case.
     * @param index the index being read, so its own row is skipped — NSE lists the index
     *   among its constituents.
     */
    fun fromRows(
        rows: List<Map<String, String?>>,
        sectorOf: (String) -> Sector?,
        index: String,
        nowMillis: Long,
    ): Map<Sector, SectorBreadth> {
        val indexName = index.trim().uppercase(Locale.US)
        val changes = HashMap<Sector, MutableList<Double>>()

        for (row in rows) {
            val symbol = row["symbol"]?.trim()?.uppercase(Locale.US) ?: continue
            if (symbol == indexName) continue
            val sector = sectorOf(symbol) ?: continue
            val last = number(row["lastPrice"]) ?: continue
            val previous = number(row["previousClose"]) ?: continue
            if (previous <= 0.0) continue
            changes.getOrPut(sector) { ArrayList() } += (last - previous) / previous * 100.0
        }

        val out = LinkedHashMap<Sector, SectorBreadth>()
        // Iterated in enum order rather than in map order, so the result is stable across
        // polls; a sector list that reshuffles itself every thirty seconds is unreadable
        // even when every number in it is right.
        for (sector in Sector.entries) {
            val values = changes[sector] ?: continue
            if (values.size < MIN_CONSTITUENTS) continue
            values.sort()
            out[sector] = SectorBreadth(
                sector = sector,
                medianChangePercent = MarketBreadths.median(values),
                advances = values.count { it > 0.0 },
                declines = values.count { it < 0.0 },
                unchanged = values.count { it == 0.0 },
                constituents = values.size,
                atMillis = nowMillis,
            )
        }
        return out
    }

    /** NSE sends numbers with thousands separators often enough to matter. */
    private fun number(raw: String?): Double? =
        raw?.trim()?.replace(",", "")?.toDoubleOrNull()
}
