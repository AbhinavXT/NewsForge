package com.abhinavxt.newsforge.core.quote

import java.util.Locale

/**
 * A price target somebody put in a headline.
 *
 * Read out of the text rather than fetched, because nothing the app can reach publishes
 * these as data. That makes it the least certain thing on the research screen, and the
 * design follows from that: a single parsed number is never shown on its own, only a
 * range across several notes, so one misread figure moves a band instead of asserting a
 * price.
 */
data class BrokerTarget(
    val price: Double,
    val publishedAt: Long,
    val source: String,
)

/** What several brokers think, taken together. */
data class TargetConsensus(
    val low: Double,
    val high: Double,
    val median: Double,
    val count: Int,
    val newestAt: Long,
)

object TargetPrices {

    /**
     * Notes needed before a consensus is shown.
     *
     * Two, not one. A single headline gives a number with no way to tell a real target
     * from a misparse, and the whole value of this is the spread — one broker at 4,200
     * and another at 2,800 is the useful fact, and neither alone is.
     */
    const val MIN_NOTES = 2

    /** How long a target counts for. Older than this and it is describing a different company. */
    const val MAX_AGE_MS = 120L * 24 * 60 * 60 * 1000

    /**
     * A figure a target could plausibly be, in rupees.
     *
     * Below the floor is a percentage or a quantity that happened to sit next to the
     * word; above the ceiling is a crore figure — "target price" and "Rs 4,200 crore"
     * both appear in market copy and only one of them is a share price.
     */
    private val PLAUSIBLE = 1.0..500_000.0

    /**
     * The number attached to a target phrase.
     *
     * Anchored on the phrase rather than scanning for any rupee figure in the headline:
     * "Motilal raises target price to Rs 4,200 after Rs 8,000 crore order" has two
     * numbers and only one of them is the target. The phrase has to come first and the
     * number within a short reach of it.
     */
    private val TARGET = Regex(
        "(?:target price|price target|target)\\s*" +
            // A short gap for the company name: "price target on Zomato to Rs 240" is
            // how these are usually written. Non-greedy and narrow, so it reaches the
            // next figure rather than the last one in the sentence.
            "(?:[a-z0-9&.' ]{0,22}?)\\s*" +
            "(?:of|to|at|:)?\\s*" +
            "(?:rs\\.?|inr|₹)\\s*" +
            "([0-9][0-9,]*(?:\\.[0-9]+)?)",
        RegexOption.IGNORE_CASE,
    )

    /** The same, written the other way round: "Rs 4,200 target". */
    private val TRAILING = Regex(
        "(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:target|price target)",
        RegexOption.IGNORE_CASE,
    )

    /** Units that turn a share price into something else entirely. */
    private val SCALE = Regex("^\\s*(crore|cr\\b|lakh|billion|bn\\b|million|mn\\b)", RegexOption.IGNORE_CASE)

    fun parse(text: String): Double? {
        for (regex in listOf(TARGET, TRAILING)) {
            val match = regex.find(text) ?: continue
            // A unit immediately after the figure means it was never a share price.
            val after = text.substring(match.range.last + 1)
            if (SCALE.containsMatchIn(after)) continue
            val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            if (value in PLAUSIBLE) return value
        }
        return null
    }

    /**
     * @param notes targets already filtered to one company.
     * @return null when too few recent notes to say anything.
     *
     *   The median rather than the mean, for the reason it is used everywhere else here:
     *   one house with an outlying number should move the band it sits in, not the middle
     *   of it.
     */
    fun consensus(notes: List<BrokerTarget>, nowMillis: Long): TargetConsensus? {
        val fresh = notes.filter { nowMillis - it.publishedAt <= MAX_AGE_MS }
        if (fresh.size < MIN_NOTES) return null
        val prices = fresh.map { it.price }.sorted()
        val middle = prices.size / 2
        return TargetConsensus(
            low = prices.first(),
            high = prices.last(),
            median = if (prices.size % 2 == 1) {
                prices[middle]
            } else {
                (prices[middle - 1] + prices[middle]) / 2.0
            },
            count = fresh.size,
            newestAt = fresh.maxOf { it.publishedAt },
        )
    }

    /** Trims a source name to something that fits beside a number. */
    fun shortSource(name: String): String =
        name.substringBefore('-').substringBefore('|').trim().take(24)
            .ifEmpty { name.take(24) }
            .lowercase(Locale.US)
            .replaceFirstChar { it.titlecase(Locale.US) }
}
