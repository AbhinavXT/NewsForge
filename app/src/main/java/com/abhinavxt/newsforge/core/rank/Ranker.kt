package com.abhinavxt.newsforge.core.rank

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Everything the score depends on. Kept flat so the function stays pure and testable. */
data class RankInput(
    val category: Category,
    val tier: SourceTier,
    val publishedAtMillis: Long,
    val symbolCount: Int,
    val clusterSize: Int,
)

/**
 * Turns an article into a single comparable number.
 *
 * Multiplicative rather than a weighted sum, so that a fatal factor actually dominates:
 * a two-day-old headline should sink no matter how good its category is, which an
 * additive model will not do.
 *
 * The whole function is deliberately explainable — every term below is something you
 * could justify out loud after a bad call — and every weight lives in one of three
 * places, so tuning it does not mean touching the pipeline.
 */
object Ranker {

    /** Multiplier applied once when at least one symbol was recognised. */
    private const val SYMBOL_BOOST = 0.5

    /** Weight on the log of how many outlets are carrying the story. */
    private const val CLUSTER_BOOST = 0.3

    /** Beyond this, extra coverage says nothing new and just crowds the top. */
    private const val CLUSTER_CAP = 8

    fun score(input: RankInput, nowMillis: Long, phase: MarketPhase): Double {
        val base = input.category.weight * input.tier.weight
        return base * recency(input.publishedAtMillis, nowMillis, phase) *
            symbolFactor(input.symbolCount) *
            clusterFactor(input.clusterSize)
    }

    /**
     * Exponential decay on age, half-life set by the session phase.
     *
     * Age is clamped at zero because feeds routinely publish timestamps a few minutes in
     * the future; without the clamp those items would score *above* 1.0 on recency and
     * permanently pin themselves to the top of the list.
     */
    fun recency(publishedAtMillis: Long, nowMillis: Long, phase: MarketPhase): Double {
        val ageMinutes = max(0.0, (nowMillis - publishedAtMillis) / 60_000.0)
        val halfLife = MarketClock.halfLifeMinutes(phase)
        return 0.5.pow(ageMinutes / halfLife)
    }

    private fun symbolFactor(symbolCount: Int): Double =
        if (symbolCount > 0) 1.0 + SYMBOL_BOOST else 1.0

    private fun clusterFactor(clusterSize: Int): Double {
        val size = min(max(clusterSize, 1), CLUSTER_CAP)
        return 1.0 + CLUSTER_BOOST * (ln(size.toDouble()) / ln(2.0))
    }
}
