package com.abhinavxt.newsforge.ui.chart

import com.abhinavxt.newsforge.core.quote.CandleInterval

/**
 * How much history the chart is showing.
 *
 * Range and bar size are chosen together rather than separately, because only some pairs
 * make sense: a year of one-minute bars is ninety thousand rows nobody will scroll, and a
 * day of daily bars is one candle. Binding them into a single choice means the screen
 * offers five buttons instead of two controls that can be set to nonsense.
 */
enum class ChartRange(
    val label: String,
    val interval: CandleInterval,
    val windowMillis: Long,
) {
    /**
     * Two days rather than one, so a Monday morning still has Friday behind it to read
     * against. A chart that opens empty because the session has not started yet is worse
     * than one showing the last session that did.
     */
    TODAY("1D", CandleInterval.FIVE_MINUTE, 2 * DAY_MS),
    MONTH("1M", CandleInterval.DAY, 31 * DAY_MS),
    QUARTER("3M", CandleInterval.DAY, 92 * DAY_MS),
    HALF_YEAR("6M", CandleInterval.DAY, 183 * DAY_MS),
    YEAR("1Y", CandleInterval.DAY, 365 * DAY_MS);

    companion object {
        /**
         * Daily first, and the default.
         *
         * A month of daily bars is the range that answers the question a news reader
         * actually arrived with — "has this been moving?" — without needing a zoom.
         */
        val Default = MONTH
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000
