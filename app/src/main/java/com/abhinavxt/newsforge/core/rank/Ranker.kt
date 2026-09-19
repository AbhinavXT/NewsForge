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
 * One term of the score, and what it did to it.
 *
 * [multiplier] is a factor rather than a contribution: below one it pushed the story down,
 * above one it pulled it up, and one means it had no opinion. That reads directly — "age
 * halved it" — in a way an additive share cannot, since the same added amount matters
 * differently at different totals.
 */
data class RankFactor(
    val name: String,
    val detail: String,
    val multiplier: Double,
) {
    val isNeutral: Boolean get() = multiplier in 0.999..1.001
}

/** A score and the factors that produced it, in the order they were applied. */
data class RankExplanation(val factors: List<RankFactor>) {
    val score: Double get() = factors.fold(1.0) { acc, factor -> acc * factor.multiplier }

    /** The factor that moved the score furthest from where it would otherwise be. */
    val dominant: RankFactor?
        get() = factors.filterNot { it.isNeutral }
            .maxByOrNull { kotlin.math.abs(kotlin.math.ln(it.multiplier)) }
}

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

    fun score(input: RankInput, nowMillis: Long, phase: MarketPhase): Double =
        explain(input, nowMillis, phase).score

    /**
     * The same score, with its working shown.
     *
     * Ordering is the one thing this app asserts and never justifies, and the number of
     * invisible inputs has only grown: a tier that now comes from open positions, a
     * weight that shifts it, a half-life that changes with the session. When the top of
     * the feed looks wrong there is currently no way to tell a bug from a disagreement,
     * and no way to know which weight to reach for.
     *
     * Multiplicative, so each factor is a multiple of what came before and reads honestly
     * as "doubled it" or "cut it in half" — which an additive model's contributions do
     * not, since there the same term means different things at different totals.
     */
    fun explain(input: RankInput, nowMillis: Long, phase: MarketPhase): RankExplanation {
        val factors = listOf(
            RankFactor("Category", input.category.label, input.category.weight),
            RankFactor("Source", input.tier.name.lowercase(), input.tier.weight),
            RankFactor(
                name = "Age",
                detail = MarketClock.halfLifeMinutes(phase).let { "${it.toInt()}m half-life" },
                multiplier = recency(input.publishedAtMillis, nowMillis, phase),
            ),
            RankFactor(
                name = "Companies",
                detail = if (input.symbolCount > 0) "${input.symbolCount} tagged" else "none",
                multiplier = symbolFactor(input.symbolCount),
            ),
            RankFactor(
                name = "Coverage",
                detail = if (input.clusterSize > 1) "${input.clusterSize} outlets" else "1 outlet",
                multiplier = clusterFactor(input.clusterSize),
            ),
        )
        return RankExplanation(factors)
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
