package com.abhinavxt.newsforge.core.rollup

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.quote.MarketBreadth
import com.abhinavxt.newsforge.core.quote.SectorBreadth
import com.abhinavxt.newsforge.core.tag.Sector

/**
 * One story, reduced to what a rollup needs.
 *
 * A separate input type rather than the feed's own model, so this file stays a pure
 * function of its arguments and can be tested without building an article, a cluster and
 * a price book to do it. The caller does the mapping, which is three lines and obvious.
 */
data class RollupStory(
    val clusterId: String,
    val title: String,
    val sectors: List<Sector>,
    val symbols: List<String>,
    val category: Category,
    val score: Double,
    /** True when the story touches a name the reader follows. */
    val watchlisted: Boolean = false,
)

/**
 * What a sector's day looks like: what was written about it, and what it did.
 *
 * The two are reported side by side and never combined into a single number. It is
 * tempting to fold heat and price move into one "attention score" and sort by that, and
 * it would destroy the only thing this screen is for — a sector loud in the news and flat
 * on the tape is a different situation from a quiet sector that moved three per cent, and
 * a combined score renders them identical.
 */
data class SectorRollup(
    val sector: Sector,
    /** Distinct stories, not articles: twelve rewrites of one wire item is one story. */
    val stories: Int,
    val heat: Double,
    /** Highest-scoring story in the sector, which is what the row should be labelled by. */
    val lead: RollupStory?,
    /** Companies named, most-mentioned first. */
    val symbols: List<String>,
    val watchlisted: Boolean,
    /** Null when too few classified constituents were quoted to say anything. */
    val breadth: SectorBreadth?,
) {
    /** Percentage points against the market, or null when either side is missing. */
    fun relativeMove(market: MarketBreadth?): Double? = breadth?.relativeTo(market)
}

object SectorRollups {

    /**
     * How many stories contribute to heat.
     *
     * Summing every story would rank sectors by how much gets written about them, which
     * is a fact about the press rather than about the market — banking and IT would win
     * every morning forever. Taking the strongest three means one filing that matters
     * outranks a dozen recycled previews, which is the question the reader is asking.
     */
    const val HEAT_STORIES = 3

    /**
     * @param stories scored stories from the window being summarised, in any order.
     * @param breadths per-sector price context, keyed by sector; may be empty.
     * @return rollups ordered by heat, hottest first. Sectors with no stories are absent
     *   — a rollup is a summary of what happened, and eighteen rows of "nothing" is not.
     */
    fun of(
        stories: List<RollupStory>,
        breadths: Map<Sector, SectorBreadth> = emptyMap(),
    ): List<SectorRollup> {
        val bySector = LinkedHashMap<Sector, MutableList<RollupStory>>()
        for (story in stories) {
            for (sector in story.sectors.distinct()) {
                bySector.getOrPut(sector) { ArrayList() } += story
            }
        }

        return bySector.map { (sector, members) ->
            // One entry per cluster, keeping its strongest member. Without this a story
            // carried by eight outlets counts eight times, and heat measures syndication.
            val leaders = members
                .groupBy { it.clusterId }
                .values
                .map { cluster -> cluster.maxBy { it.score } }

            val ranked = leaders.sortedByDescending { it.score }
            SectorRollup(
                sector = sector,
                stories = ranked.size,
                heat = ranked.take(HEAT_STORIES).sumOf { it.score },
                lead = ranked.firstOrNull(),
                symbols = rankedSymbols(ranked),
                watchlisted = ranked.any { it.watchlisted },
                breadth = breadths[sector],
            )
        }.sortedWith(
            // Heat decides; the sector's own order breaks ties, so a morning where
            // nothing happened still produces a stable list rather than one that
            // reshuffles on every recomposition.
            compareByDescending<SectorRollup> { it.heat }.thenBy { it.sector.ordinal }
        )
    }

    /**
     * Companies named in the sector's stories, most-mentioned first.
     *
     * Frequency rather than score: this answers "which names is this about", and one
     * high-scoring filing should not put its company ahead of the four that everybody is
     * writing about.
     */
    private fun rankedSymbols(stories: List<RollupStory>): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (story in stories) {
            for (symbol in story.symbols.distinct()) {
                counts[symbol] = (counts[symbol] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }
}
