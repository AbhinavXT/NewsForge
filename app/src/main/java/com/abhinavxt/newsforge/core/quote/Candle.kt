package com.abhinavxt.newsforge.core.quote

/**
 * One bar of price history.
 *
 * Deliberately a plain value with no source attached, unlike [com.abhinavxt.newsforge.core.desk.DeskPayload].
 * A quote is a claim about *now* and its age decides whether it is worth rendering; a
 * closed bar is a fact about a past interval and does not go stale. The only bar that can
 * change is the one still forming, and that is handled by writing over it rather than by
 * labelling every bar with when it was fetched.
 */
data class Candle(
    /** Start of the interval, epoch millis. */
    val openTimeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
) {
    /** (H + L + C) / 3 — the input money-flow and several other indicators are built on. */
    val typicalPrice: Double get() = (high + low + close) / 3.0
}

/**
 * Bar sizes the research screen asks for.
 *
 * Named with Kite's own strings rather than ours. The desk passes this value straight
 * through to `historical_data`, so inventing a vocabulary here would mean a translation
 * table on both sides that exists only to be kept in sync.
 */
enum class CandleInterval(val wireName: String) {
    DAY("day"),
    FIVE_MINUTE("5minute"),
    MINUTE("minute");

    /** Bars this size are intraday, so they are kept for days rather than for years. */
    val isIntraday: Boolean get() = this != DAY

    companion object {
        fun parse(raw: String?): CandleInterval? {
            val text = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == text }
        }
    }
}

/** A run of bars for one symbol at one size, as it arrived. */
data class CandleBatch(
    val symbol: String,
    val interval: CandleInterval,
    val candles: List<Candle>,
)
