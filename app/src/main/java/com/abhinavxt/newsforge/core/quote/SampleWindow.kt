package com.abhinavxt.newsforge.core.quote

/**
 * Cuts a run of samples back to the session it ends in.
 *
 * The store holds a couple of days of prices, collected only while the market is open. Any
 * chart drawn across the whole of that has an overnight gap in it, and a line has no way
 * to render a gap — it draws a long flat segment instead, which reads as seventeen hours
 * of a stock not moving. It also squashes the part anyone cares about into whatever width
 * is left over.
 *
 * So the window ends at the newest sample and walks backwards until the samples stop being
 * adjacent. No calendar is involved on purpose: a session boundary defined by a gap in the
 * data is right on a holiday, right on a half-day, right when the phone was asleep for the
 * morning, and right without anything having to know the exchange's hours.
 */
object SampleWindow {

    /**
     * How far apart two samples can be and still belong to the same run.
     *
     * Polling is per-minute while the market is open, so anything on this scale is the app
     * having been asleep rather than the session having ended — and a four-hour hole with
     * trading either side is still better drawn as one line than split into two charts of
     * an hour each. The overnight gap is more than three times this.
     */
    const val DEFAULT_MAX_GAP_MS = 4L * 60 * 60 * 1000

    fun latestSession(
        samples: List<PriceSample>,
        maxGapMillis: Long = DEFAULT_MAX_GAP_MS,
    ): List<PriceSample> {
        if (samples.size < 2) return samples
        // Sorted rather than assumed: these come out of a query that orders by time, but
        // the cost is nothing and a silently reversed series would draw the day backwards.
        val ordered = samples.sortedBy { it.atMillis }
        var start = ordered.lastIndex
        while (start > 0 && ordered[start].atMillis - ordered[start - 1].atMillis <= maxGapMillis) {
            start--
        }
        return ordered.subList(start, ordered.size)
    }
}
