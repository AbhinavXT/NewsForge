package com.abhinavxt.newsforge.core.quote

/** One remembered price, at one moment. */
data class PriceSample(val symbol: String, val atMillis: Long, val price: Double)

/**
 * How a stock moved after a story broke, and what that measurement is worth.
 *
 * [basis] exists because "up 2%" means three different things depending on what it is
 * measured from, and a number whose meaning is ambiguous is worse in a trading app than
 * no number at all.
 */
data class Reaction(
    val symbol: String,
    val percent: Double,
    val sinceMillis: Long,
    val basis: ReactionBasis,
)

enum class ReactionBasis {
    /** Measured from a price sampled at or just before the story was published. */
    PUBLICATION,

    /** Story predates anything sampled today, so measured from the previous close. */
    PREVIOUS_CLOSE,
}

/**
 * The difference between news and signal.
 *
 * The day's move tells you what the stock did. It cannot tell you whether it did it
 * because of the thing you are reading, and at 11:40 a name that is up four per cent on
 * the session may have done all of it before the headline you are looking at existed.
 * Measuring from the moment of publication separates the order win the market has already
 * priced from the one it has not noticed — which is the entire question an intraday
 * reader is asking.
 *
 * No broker app can show this, because the broker does not know when the story landed.
 * That is the only real advantage this app has over the terminal already open next to it.
 *
 * The samples come from the app's own polling. Nothing is requested from anywhere: quotes
 * arrive every minute during the session anyway, and remembering them costs a row.
 */
object PriceReaction {

    /**
     * How stale a sample may be and still be treated as "the price when this broke".
     *
     * Wider than the one-minute polling interval because polling is not guaranteed — the
     * app may have been backgrounded, the session may have been in its midday gap, the
     * request may simply have failed. Beyond this the gap stops being measurement error
     * and starts being a different time of day.
     */
    const val MAX_LOOKBACK_MS: Long = 15L * 60 * 1000

    /**
     * Below this a reaction is noise dressed as information.
     *
     * Every price moves. Printing "+0.1% since this broke" next to a headline implies a
     * connection that a tick does not support.
     */
    const val MIN_PERCENT: Double = 0.4

    /**
     * @param samples prices remembered for this symbol, in any order.
     * @param publishedAt when the story was published.
     * @param lastPrice the current price.
     * @param previousClose used when the story predates everything sampled.
     */
    fun of(
        symbol: String,
        samples: List<PriceSample>,
        publishedAt: Long,
        lastPrice: Double?,
        previousClose: Double? = null,
    ): Reaction? {
        val last = lastPrice ?: return null

        // The newest sample at or before publication: the closest thing to what the
        // stock was worth in the moment before anyone had read this.
        val before = samples
            .filter { it.atMillis <= publishedAt && it.price > 0.0 }
            .maxByOrNull { it.atMillis }

        if (before != null && publishedAt - before.atMillis <= MAX_LOOKBACK_MS) {
            return reaction(symbol, before.price, last, before.atMillis, ReactionBasis.PUBLICATION)
        }

        // Nothing sampled around then — an overnight story, or the app was not running.
        // The previous close is the honest fallback and is labelled as such rather than
        // being passed off as a reaction to this headline.
        val base = previousClose?.takeIf { it > 0.0 } ?: return null
        return reaction(symbol, base, last, publishedAt, ReactionBasis.PREVIOUS_CLOSE)
    }

    private fun reaction(
        symbol: String,
        from: Double,
        to: Double,
        sinceMillis: Long,
        basis: ReactionBasis,
    ): Reaction? {
        val percent = (to - from) / from * 100.0
        if (kotlin.math.abs(percent) < MIN_PERCENT) return null
        return Reaction(symbol, percent, sinceMillis, basis)
    }

    /**
     * Picks the symbol to report on when a story names several.
     *
     * The one that moved most, not the first one listed. A policy story touching four
     * metals companies is interesting because of whichever of them the market actually
     * repriced, and ordering in the tag list carries no information about that.
     */
    fun strongest(reactions: List<Reaction>): Reaction? =
        reactions.maxByOrNull { kotlin.math.abs(it.percent) }
}
