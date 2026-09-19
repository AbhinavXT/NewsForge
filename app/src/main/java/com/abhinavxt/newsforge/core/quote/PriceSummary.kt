package com.abhinavxt.newsforge.core.quote

import com.abhinavxt.newsforge.core.desk.DeskPayload

/**
 * What a company's price is, from whatever the app actually has.
 *
 * Exists because the research screen could draw six months of history and still show no
 * price at all. A quote arrives only for symbols the watchlist asked the exchange about
 * or the desk chose to push; open anything outside that set and every price field was
 * empty while the chart underneath plotted the number perfectly well.
 *
 * So a closed bar counts as a price. It is not a live one and is never presented as one —
 * [source] exists so the screen can say which it has — but "last close, Thursday" is an
 * answer, and a blank space is not.
 */
data class PriceSummary(
    val last: Double,
    val previousClose: Double?,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val volume: Double?,
    val vwap: Double?,
    /** When this price was true: the quote's own stamp, or the bar's open. */
    val asOfMillis: Long?,
    val source: Source,
) {
    enum class Source {
        /** A quote, from the desk or the exchange. Moves while you watch it. */
        LIVE,

        /** The close of the newest bar held. Correct, and as old as the last session. */
        LAST_CLOSE,
    }

    val change: Double? get() = previousClose?.let { last - it }

    val changePercent: Double?
        get() = previousClose?.takeIf { it > 0.0 }?.let { (last - it) / it * 100.0 }

    /** Where the last price sits between the day's extremes; null on a flat or unknown day. */
    val positionInDayRange: Double?
        get() {
            val h = high ?: return null
            val l = low ?: return null
            if (h - l < 1e-9) return null
            return ((last - l) / (h - l)).coerceIn(0.0, 1.0)
        }
}

object PriceSummaries {

    /**
     * @param dailyCandles daily bars, oldest first — the fallback when no quote exists.
     *
     * A live quote wins where there is one, but its gaps are filled from the newest bar
     * rather than left empty: the exchange's index response carries a last price and a
     * previous close and often nothing else, while the bar for the same session has the
     * open, the high, the low and the volume sitting right there. Mixing them is safe
     * precisely because they describe the same day — and the alternative is a screen
     * showing a live price above four empty fields.
     */
    fun of(quote: DeskPayload?, dailyCandles: List<Candle>): PriceSummary? {
        val newest = dailyCandles.maxByOrNull { it.openTimeMillis }
        val ltp = quote?.ltp

        if (ltp != null) {
            return PriceSummary(
                last = ltp,
                previousClose = quote.previousClose ?: priorClose(dailyCandles),
                open = quote.dayOpen ?: newest?.open,
                high = quote.dayHigh ?: newest?.high,
                low = quote.dayLow ?: newest?.low,
                volume = quote.volume?.toDouble() ?: newest?.volume,
                vwap = quote.vwap,
                asOfMillis = quote.timestampMillis,
                source = PriceSummary.Source.LIVE,
            )
        }

        if (newest == null) return null
        return PriceSummary(
            last = newest.close,
            // The bar before the newest, not the newest itself: a bar's own open is where
            // the session started, and the change everyone means is against the last close.
            previousClose = priorClose(dailyCandles),
            open = newest.open,
            high = newest.high,
            low = newest.low,
            volume = newest.volume,
            vwap = null,
            asOfMillis = newest.openTimeMillis,
            source = PriceSummary.Source.LAST_CLOSE,
        )
    }

    /** The close before the newest bar, which is what a day's change is measured from. */
    private fun priorClose(candles: List<Candle>): Double? {
        if (candles.size < 2) return null
        val ordered = candles.sortedBy { it.openTimeMillis }
        return ordered[ordered.lastIndex - 1].close
    }
}
