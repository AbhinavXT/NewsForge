package com.abhinavxt.newsforge.ui.feed

import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.core.quote.PriceReaction
import com.abhinavxt.newsforge.core.quote.PriceSample
import com.abhinavxt.newsforge.core.quote.Reaction
import com.abhinavxt.newsforge.data.model.ArticleSummary

/**
 * What every symbol is worth now, and what it was worth earlier.
 *
 * The two travel together because neither is useful alone for the thing the feed wants to
 * say. A current price answers "what is it worth"; the pair answers "did the market care
 * about this", which is the question an intraday reader is actually asking.
 */
data class PriceBook(
    val quotes: Map<String, DeskPayload> = emptyMap(),
    val samples: Map<String, List<PriceSample>> = emptyMap(),
    /** Relative volume the app worked out for itself, by symbol. */
    val relativeVolumes: Map<String, Double> = emptyMap(),
) {
    val isEmpty: Boolean get() = quotes.isEmpty()

    operator fun get(symbol: String): DeskPayload? = quotes[symbol]

    /**
     * How busy the story's most relevant name is, against its own normal.
     *
     * A desk figure wins where there is one: it is computed against twenty real sessions
     * of history rather than the fortnight the phone has accumulated, and it exists
     * precisely because somebody had better data to hand.
     *
     * Reported for the first symbol that has an answer rather than the busiest. The badge
     * sits beside the tickers, and pointing it at a different company from the one the
     * reader's eye lands on would be worse than showing nothing.
     */
    fun volumeFor(article: ArticleSummary): Double? = article.symbols.firstNotNullOfOrNull {
        quotes[it]?.relativeVolume ?: relativeVolumes[it]
    }

    /**
     * How the story's most-moved symbol has behaved since it was published.
     *
     * Most-moved rather than first-listed: a policy story touching four metals companies
     * is interesting because of whichever one the market actually repriced, and the order
     * of the tags says nothing about that.
     *
     * Null is the common case and the correct one — before the app has sampled a symbol,
     * for a story older than the samples, or for a move too small to mean anything.
     */
    fun reactionFor(article: ArticleSummary): Reaction? {
        if (quotes.isEmpty()) return null
        return PriceReaction.strongest(
            article.symbols.mapNotNull { symbol ->
                val quote = quotes[symbol] ?: return@mapNotNull null
                PriceReaction.of(
                    symbol = symbol,
                    samples = samples[symbol].orEmpty(),
                    publishedAt = article.publishedAt,
                    lastPrice = quote.ltp,
                    previousClose = quote.previousClose,
                )
            }
        )
    }
}
