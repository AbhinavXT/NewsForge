package com.abhinavxt.newsforge.core.quote

/**
 * A second company's price, redrawn on the first one's chart.
 *
 * Rebased rather than drawn on a second axis. Two axes invite reading the lines' heights
 * against each other, which means nothing when one stock is ₹90 and the other ₹9,000;
 * starting both at the same point makes the only thing on screen the thing being asked
 * about — which one did better from here.
 */
object Comparison {

    /**
     * [other]'s closes, one per bar of [main], scaled so both start at [main]'s close.
     *
     * Null where [other] has no bar yet, and before its first one. Each [main] bar takes
     * the latest [other] bar that opened at or before it, so a missing session on one side
     * holds the last price rather than drawing a gap or a fall to zero.
     */
    fun rebased(main: List<Candle>, other: List<Candle>): List<Double?> {
        if (main.isEmpty() || other.isEmpty()) return main.map { null }
        val sorted = other.sortedBy { it.openTimeMillis }
        var cursor = -1
        val aligned = main.map { bar ->
            while (cursor + 1 < sorted.size && sorted[cursor + 1].openTimeMillis <= bar.openTimeMillis) {
                cursor++
            }
            if (cursor >= 0) sorted[cursor].close else null
        }
        val start = aligned.indexOfFirst { it != null && it > 0.0 }
        if (start < 0) return main.map { null }
        val scale = main[start].close / aligned[start]!!
        return aligned.mapIndexed { index, close -> if (index < start) null else close?.times(scale) }
    }

    /** First to last close over [candles], in per cent; null with fewer than two. */
    fun changePercent(candles: List<Candle>): Double? {
        if (candles.size < 2) return null
        val first = candles.first().close.takeIf { it > 0 } ?: return null
        return (candles.last().close - first) / first * 100.0
    }

    /** The same figure for a rebased line, which starts where its first value is. */
    fun lineChangePercent(rebased: List<Double?>): Double? {
        val values = rebased.filterNotNull()
        if (values.size < 2) return null
        val first = values.first().takeIf { it > 0 } ?: return null
        return (values.last() - first) / first * 100.0
    }
}
