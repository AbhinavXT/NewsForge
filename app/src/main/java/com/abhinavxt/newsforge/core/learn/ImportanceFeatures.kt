package com.abhinavxt.newsforge.core.learn

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.core.rank.MarketPhase
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** Everything the model is allowed to look at. Deliberately all cheap and already stored. */
data class FeatureInput(
    val category: Category,
    val tier: SourceTier,
    val isFiling: Boolean,
    val outletCount: Int,
    val spanMinutes: Long,
    val symbolCount: Int,
    val title: String,
    val summary: String? = null,
    val phase: MarketPhase,
)

/**
 * Turns a story into the numbers a linear model can learn from.
 *
 * No bag of words. The obvious move is to throw headline tokens at it, and on a few
 * hundred training examples — which is all two days of price samples can ever produce —
 * that would fit the vocabulary of last week's news and nothing else. Every feature here
 * is either something the pipeline already computes or a quantity the headline states
 * outright, which keeps the dimension count near thirty and the model honest about what
 * it knows.
 *
 * All features are scaled to roughly the unit interval. Not for numerical stability — a
 * logistic regression tolerates far worse — but so that the learned weights are directly
 * comparable to each other, which is what makes the model inspectable rather than a
 * vector of magic numbers.
 */
object ImportanceFeatures {

    /**
     * Names in vector order, for the explainer and for tests.
     *
     * Built once and asserted against the vector's length, because the failure mode of a
     * feature list and a builder drifting apart is silent: the model keeps training,
     * every weight lands on the wrong feature, and nothing crashes.
     */
    val NAMES: List<String> = buildList {
        add("bias")
        Category.MARKETS.forEach { add("cat:${it.name.lowercase()}") }
        SourceTier.entries.forEach { add("tier:${it.name.lowercase()}") }
        MarketPhase.entries.forEach { add("phase:${it.name.lowercase()}") }
        add("filing")
        add("coverage")
        add("pickup")
        // No "has a company" flag. Every training example has one — a price reaction
        // cannot be measured without a stock — so the flag would be 1 for the whole
        // training set: no information, and perfectly collinear with the bias. Its
        // weight would be whatever share of the bias the penalty happened to hand it,
        // and every story without a company would be scored by that accident.
        add("symbol_count")
        add("money")
        add("has_money")
        add("percent")
        add("headline_length")
    }

    val SIZE: Int = NAMES.size

    fun of(input: FeatureInput): DoubleArray {
        val out = DoubleArray(SIZE)
        var index = 0

        out[index++] = 1.0

        for (category in Category.MARKETS) {
            out[index++] = if (category == input.category) 1.0 else 0.0
        }
        for (tier in SourceTier.entries) {
            out[index++] = if (tier == input.tier) 1.0 else 0.0
        }
        for (phase in MarketPhase.entries) {
            out[index++] = if (phase == input.phase) 1.0 else 0.0
        }

        out[index++] = if (input.isFiling) 1.0 else 0.0

        // log2 of outlets over the same cap the ranker uses, so the two terms cannot
        // disagree about where extra coverage stops meaning anything.
        out[index++] = ln(min(max(input.outletCount, 1), COVERAGE_CAP).toDouble()) / ln(2.0) / 3.0

        out[index++] = pickupRate(input.outletCount, input.spanMinutes)

        out[index++] = min(1.0, ln(1.0 + input.symbolCount) / ln(6.0))

        val text = listOfNotNull(input.title, input.summary).joinToString(" ")
        val crore = HeadlineNumbers.croreIn(text)
        // Log-scaled: the step from 10 crore to 100 is the interesting one, and the step
        // from 5,000 to 5,100 is not. Ten thousand crore reads as 1.0.
        out[index++] = crore?.let { min(1.0, max(0.0, log10(it + 1.0) / 4.0)) } ?: 0.0
        // Separate from the magnitude, so "names an amount at all" can be learned apart
        // from "names a large one" — a headline with no figure is not a headline with a
        // figure of zero.
        out[index++] = if (crore != null) 1.0 else 0.0

        out[index++] = HeadlineNumbers.percentIn(text)
            ?.let { min(1.0, it / 50.0) } ?: 0.0

        out[index++] = min(1.0, input.title.split(' ').size / 25.0)

        require(index == SIZE) { "feature builder wrote $index of $SIZE" }
        return out
    }

    /** The same rate the ranker's velocity term uses, scaled to the unit interval. */
    private fun pickupRate(outletCount: Int, spanMinutes: Long): Double {
        if (outletCount < 2) return 0.0
        val span = max(spanMinutes.toDouble(), MIN_SPAN_MINUTES)
        val perHour = (outletCount - 1) * 60.0 / span
        return min(1.0, perHour / REFERENCE_PICKUP_RATE)
    }

    private const val COVERAGE_CAP = 8
    private const val MIN_SPAN_MINUTES = 5.0
    private const val REFERENCE_PICKUP_RATE = 3.0
}
