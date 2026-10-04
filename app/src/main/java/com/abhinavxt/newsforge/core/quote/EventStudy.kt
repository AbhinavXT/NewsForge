package com.abhinavxt.newsforge.core.quote

/** One observed price, from whatever source. Sorting is the caller's business. */
data class PricePoint(val atMillis: Long, val price: Double)

/**
 * How long after publication a measurement is taken.
 *
 * Fixed windows rather than "to the close", deliberately. A window that runs to the close
 * means something different for a 09:20 story than for a 15:20 one, so the numbers could
 * not be compared across stories — and comparing them is the only reason to compute them
 * at all.
 */
enum class EventHorizon(val label: String, val minutes: Long) {
    FIFTEEN_MINUTES("15m", 15),
    ONE_HOUR("1h", 60),
    ONE_DAY("1d", 24 * 60),
}

/**
 * What happened over one window.
 *
 * [abnormalPercent] is the number that means something. A stock up 1.8% after a filing on
 * a morning the whole market is up 1.6% has not reacted to the filing; [percent] alone
 * cannot say so, and printing it next to a headline implies a connection the data does
 * not support.
 */
data class EventOutcome(
    val horizon: EventHorizon,
    val percent: Double,
    /** The market over the same window, or null when too few names were observed. */
    val benchmarkPercent: Double?,
    val basePrice: Double,
    val baseAtMillis: Long,
    val endPrice: Double,
    val endAtMillis: Long,
    /** False while the window is still open — the figure is "so far", not the answer. */
    val complete: Boolean,
) {
    val abnormalPercent: Double?
        get() = benchmarkPercent?.let { percent - it }
}

/** A story's measured aftermath. Empty [outcomes] means nothing was measurable. */
data class EventStudyResult(
    val symbol: String,
    val publishedAt: Long,
    val outcomes: List<EventOutcome>,
) {
    val isEmpty: Boolean get() = outcomes.isEmpty()

    /** The longest window that has actually closed, which is the headline number. */
    val settled: EventOutcome? get() = outcomes.lastOrNull { it.complete }
}

/**
 * Measures what a stock did after a story, and what the market did at the same time.
 *
 * The app is the only thing on the phone that knows when a story landed. The broker does
 * not, the exchange does not, and a chart cannot: it shows a move without saying what the
 * move was about. Everything needed for this is already stored — sampled prices, desk
 * candles, and the publication time of every article — so this is arithmetic over data
 * that was collected for other reasons.
 *
 * Deliberately conservative about refusing to answer. An event study with a wrong base
 * price is not a slightly worse study, it is a fabricated claim about cause and effect,
 * and there is no way for the reader to tell the two apart on screen.
 */
object EventStudy {

    /**
     * How stale the base observation may be.
     *
     * The same fifteen minutes [PriceReaction] allows, and for the same reason: polling
     * is not guaranteed, and beyond this the gap stops being measurement error and starts
     * being a different time of day.
     */
    const val MAX_BASE_LOOKBACK_MS: Long = 15L * 60 * 1000

    /** Fewest symbols that can stand in for "the market" in a cross-section. */
    const val MIN_BENCHMARK_SYMBOLS = 8

    /**
     * How short of the horizon the closing observation may fall.
     *
     * Proportional, because five minutes missing from a fifteen-minute window is a third
     * of it and five minutes missing from a day is nothing. Under-coverage is the real
     * failure here — trading halted, the app asleep, the session over — and measuring a
     * "one hour" move over the twelve minutes that happened to be sampled would be the
     * kind of wrong that looks right.
     *
     * Kept strictly below the horizon, which is what guarantees a window is never
     * measured between an observation and itself: the base sits at or before publication
     * and the target a whole horizon after it, so any observation close enough to the
     * target to be accepted is necessarily later than the base.
     */
    fun toleranceFor(horizon: EventHorizon): Long =
        maxOf(5L * 60 * 1000, horizon.minutes * 60 * 1000 / 10)

    /**
     * @param series the symbol's observed prices, any order.
     * @param benchmark every symbol's observed prices, including this one; used for the
     *   cross-sectional median. Pass an empty map to skip the market comparison.
     * @param nowMillis used only to decide whether a window has closed.
     */
    fun of(
        symbol: String,
        series: List<PricePoint>,
        publishedAt: Long,
        benchmark: Map<String, List<PricePoint>> = emptyMap(),
        nowMillis: Long,
    ): EventStudyResult {
        val ordered = series.filter { it.price > 0.0 }.sortedBy { it.atMillis }
        val base = baseFor(ordered, publishedAt)
            ?: return EventStudyResult(symbol, publishedAt, emptyList())

        val outcomes = EventHorizon.entries.mapNotNull { horizon ->
            val end = endFor(ordered, publishedAt, horizon) ?: return@mapNotNull null
            EventOutcome(
                horizon = horizon,
                percent = change(base.price, end.price),
                benchmarkPercent = benchmarkChange(
                    benchmark, base.atMillis, end.atMillis, publishedAt, horizon,
                ),
                basePrice = base.price,
                baseAtMillis = base.atMillis,
                endPrice = end.price,
                endAtMillis = end.atMillis,
                complete = nowMillis >= publishedAt + horizon.minutes * 60_000,
            )
        }
        return EventStudyResult(symbol, publishedAt, outcomes)
    }

    /**
     * The last price observed at or before publication.
     *
     * At or before, never after: the point of the exercise is the price in the moment
     * before anyone had read this, and an observation from thirty seconds afterwards may
     * already contain the reaction being measured.
     */
    internal fun baseFor(ordered: List<PricePoint>, publishedAt: Long): PricePoint? {
        val candidate = ordered.lastOrNull { it.atMillis <= publishedAt } ?: return null
        if (publishedAt - candidate.atMillis > MAX_BASE_LOOKBACK_MS) return null
        return candidate
    }

    /** The last price observed at or before the horizon, if the window is covered. */
    internal fun endFor(
        ordered: List<PricePoint>,
        publishedAt: Long,
        horizon: EventHorizon,
    ): PricePoint? {
        val target = publishedAt + horizon.minutes * 60_000
        val candidate = ordered.lastOrNull { it.atMillis <= target } ?: return null
        if (target - candidate.atMillis > toleranceFor(horizon)) return null
        return candidate
    }

    /**
     * The market over the same window, as the median of every symbol that was observed at
     * both ends of it.
     *
     * Median and unweighted, for the reasons [MarketBreadth] already gives: a mean is
     * decided by whichever three names are up twenty per cent, and a cap weighting turns
     * "the market" into the largest eight companies.
     *
     * Each symbol is measured over *its own* nearest observations rather than forced onto
     * this symbol's two timestamps, because the samples are not synchronised — a name
     * polled forty seconds later is the ordinary case, not a reason to drop it.
     */
    private fun benchmarkChange(
        benchmark: Map<String, List<PricePoint>>,
        baseAt: Long,
        endAt: Long,
        publishedAt: Long,
        horizon: EventHorizon,
    ): Double? {
        if (benchmark.isEmpty()) return null
        val changes = ArrayList<Double>(benchmark.size)
        for ((_, points) in benchmark) {
            val ordered = points.filter { it.price > 0.0 }.sortedBy { it.atMillis }
            val base = baseFor(ordered, publishedAt) ?: continue
            val end = endFor(ordered, publishedAt, horizon) ?: continue
            changes += change(base.price, end.price)
        }
        if (changes.size < MIN_BENCHMARK_SYMBOLS) return null
        changes.sort()
        return MarketBreadths.median(changes)
    }

    private fun change(from: Double, to: Double): Double = (to - from) / from * 100.0

    /** Adapters, so a study can be run off either thing the app already stores. */
    fun fromSamples(samples: List<PriceSample>): List<PricePoint> =
        samples.map { PricePoint(it.atMillis, it.price) }

    /**
     * Candles read at their close price, stamped at the end of the interval.
     *
     * Stamped at the end because a bar labelled 10:05 covers 10:05 to 10:10, and its
     * close is what the stock was worth at 10:10. Using the open timestamp would date
     * every observation one interval early, which on minute bars is survivable and on
     * daily bars would measure the wrong day entirely.
     */
    fun fromCandles(candles: List<Candle>, interval: CandleInterval): List<PricePoint> {
        val span = when (interval) {
            CandleInterval.MINUTE -> 60_000L
            CandleInterval.FIVE_MINUTE -> 5 * 60_000L
            CandleInterval.DAY -> 24 * 60 * 60_000L
        }
        return candles.map { PricePoint(it.openTimeMillis + span, it.close) }
    }
}
