package com.abhinavxt.newsforge.core.ta

import com.abhinavxt.newsforge.core.quote.Candle

/**
 * Technical indicators, computed on the phone from bars the desk sent.
 *
 * The split is deliberate. The desk could compute these — it has pandas and it already has
 * the candles — but the chart needs the bars regardless, and once they have crossed the
 * bridge an indicator is arithmetic. Sending both would mean a wire field per indicator
 * per period, and changing an RSI window from 14 to 21 would mean redeploying the desk to
 * answer a question the phone already has all the data for.
 *
 * Every function returns a series the same length as its input, with null where the
 * indicator is not yet defined. Nulls rather than a shorter list or a zero: a chart pane
 * has to line up with the candles above it index for index, a shorter list makes that the
 * caller's arithmetic to get wrong, and zero is a value RSI and MACD can legitimately
 * take.
 */
object Indicators {

    const val RSI_PERIOD = 14
    const val MACD_FAST = 12
    const val MACD_SLOW = 26
    const val MACD_SIGNAL = 9
    const val MFI_PERIOD = 14

    /**
     * Relative strength index, with Wilder's smoothing.
     *
     * Wilder's, not a simple moving average of gains — the two diverge by several points
     * on the same data, and every chart the reader will compare this against uses
     * Wilder's. The seed is the arithmetic mean of the first [period] changes, which is
     * how he defined it; everything after is the running smooth.
     */
    fun rsi(closes: List<Double>, period: Int = RSI_PERIOD): List<Double?> {
        val out = arrayOfNulls<Double>(closes.size)
        if (period < 1 || closes.size <= period) return out.toList()

        var gainSum = 0.0
        var lossSum = 0.0
        for (i in 1..period) {
            val change = closes[i] - closes[i - 1]
            if (change >= 0) gainSum += change else lossSum -= change
        }
        var avgGain = gainSum / period
        var avgLoss = lossSum / period
        out[period] = rsiFrom(avgGain, avgLoss)

        for (i in period + 1 until closes.size) {
            val change = closes[i] - closes[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) -change else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            out[i] = rsiFrom(avgGain, avgLoss)
        }
        return out.toList()
    }

    /**
     * An unbroken run of gains has no downside to divide by.
     *
     * The limit is 100 and reporting it is correct, but it has to be said explicitly or
     * the division produces an infinity that renders as a blank pane.
     */
    private fun rsiFrom(avgGain: Double, avgLoss: Double): Double {
        if (avgLoss <= 0.0) return if (avgGain <= 0.0) 50.0 else 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    /** The three MACD series, aligned with the input. */
    data class Macd(
        val line: List<Double?>,
        val signal: List<Double?>,
        val histogram: List<Double?>,
    )

    /**
     * Moving-average convergence/divergence.
     *
     * The signal line is an EMA of the MACD line, seeded only once the MACD line itself
     * has [signal] values — so it starts at index `slow - 1 + signal - 1` rather than at
     * `signal - 1`. Getting that wrong is the usual MACD bug: the histogram appears
     * twenty-five bars early and is meaningless for all of them.
     */
    fun macd(
        closes: List<Double>,
        fast: Int = MACD_FAST,
        slow: Int = MACD_SLOW,
        signal: Int = MACD_SIGNAL,
    ): Macd {
        val empty = List<Double?>(closes.size) { null }
        if (fast < 1 || slow <= fast || signal < 1) return Macd(empty, empty, empty)

        val fastEma = ema(closes, fast)
        val slowEma = ema(closes, slow)
        val line = closes.indices.map { i ->
            val f = fastEma[i]
            val s = slowEma[i]
            if (f == null || s == null) null else f - s
        }

        // The signal EMA runs over the defined part of the line only, then is placed back
        // at the indices it came from.
        val firstDefined = line.indexOfFirst { it != null }
        if (firstDefined < 0) return Macd(line, empty, empty)
        val defined = line.drop(firstDefined).map { it ?: 0.0 }
        val signalDefined = ema(defined, signal)

        val signalOut = arrayOfNulls<Double>(closes.size)
        for (i in signalDefined.indices) signalOut[firstDefined + i] = signalDefined[i]

        val histogram = closes.indices.map { i ->
            val l = line[i]
            val s = signalOut[i]
            if (l == null || s == null) null else l - s
        }
        return Macd(line, signalOut.toList(), histogram)
    }

    /**
     * Money flow index — RSI weighted by traded value.
     *
     * Worth having next to RSI rather than instead of it: the two disagree exactly when
     * a move happened on unusual volume, which for a news reader is the interesting case.
     * A bar whose typical price is unchanged counts as neither inflow nor outflow, which
     * is the definition and not an oversight.
     */
    fun mfi(candles: List<Candle>, period: Int = MFI_PERIOD): List<Double?> {
        val out = arrayOfNulls<Double>(candles.size)
        if (period < 1 || candles.size <= period) return out.toList()

        val typical = candles.map { it.typicalPrice }
        val positive = DoubleArray(candles.size)
        val negative = DoubleArray(candles.size)
        for (i in 1 until candles.size) {
            val flow = typical[i] * candles[i].volume
            when {
                typical[i] > typical[i - 1] -> positive[i] = flow
                typical[i] < typical[i - 1] -> negative[i] = flow
            }
        }

        var positiveSum = 0.0
        var negativeSum = 0.0
        for (i in 1..period) {
            positiveSum += positive[i]
            negativeSum += negative[i]
        }
        out[period] = mfiFrom(positiveSum, negativeSum)

        for (i in period + 1 until candles.size) {
            positiveSum += positive[i] - positive[i - period]
            negativeSum += negative[i] - negative[i - period]
            out[i] = mfiFrom(positiveSum, negativeSum)
        }
        return out.toList()
    }

    private fun mfiFrom(positive: Double, negative: Double): Double {
        if (negative <= 0.0) return if (positive <= 0.0) 50.0 else 100.0
        return 100.0 - (100.0 / (1.0 + positive / negative))
    }

    /**
     * Exponential moving average, seeded with the simple mean of the first [period] values.
     *
     * Seeded that way rather than from the first value alone, which is the other common
     * convention: seeding from one price leaves the early output dominated by whatever
     * that price happened to be, and over the twenty-six bars MACD needs it has not
     * finished decaying.
     */
    internal fun ema(values: List<Double>, period: Int): List<Double?> {
        val out = arrayOfNulls<Double>(values.size)
        if (period < 1 || values.size < period) return out.toList()

        var sum = 0.0
        for (i in 0 until period) sum += values[i]
        var previous = sum / period
        out[period - 1] = previous

        val multiplier = 2.0 / (period + 1)
        for (i in period until values.size) {
            previous = (values[i] - previous) * multiplier + previous
            out[i] = previous
        }
        return out.toList()
    }
}

/** The extremes of a window, and where the last price sits between them. */
data class PriceRange(
    val high: Double,
    val low: Double,
    val last: Double,
) {
    /**
     * 0 at the low, 1 at the high.
     *
     * Null when the window has no width, for the same reason
     * [com.abhinavxt.newsforge.core.desk.DeskPayload.positionInDayRange] returns null
     * there: a bar pinned to one end reads as information when it is the absence of it.
     */
    val position: Double?
        get() {
            if (high - low < 1e-9) return null
            return ((last - low) / (high - low)).coerceIn(0.0, 1.0)
        }
}

/**
 * The 52-week range, from daily bars.
 *
 * Computed rather than fetched because no endpoint this app can reach returns it: Kite's
 * quote does not carry it, and NSE's index response carries `yearHigh`/`yearLow` only for
 * constituents. Once a year of daily candles is on the phone the number is a fold, and
 * the same fold serves any window the screen wants to offer.
 *
 * Bounded by elapsed time rather than by a count of bars. Two hundred and fifty-two is
 * the usual stand-in for a trading year and it is wrong after every stretch of holidays,
 * whereas a date cutoff means the same thing in every month.
 */
object Range {

    const val YEAR_MS = 365L * 24 * 60 * 60 * 1000

    fun over(candles: List<Candle>, nowMillis: Long, windowMillis: Long = YEAR_MS): PriceRange? {
        val since = nowMillis - windowMillis
        val window = candles.filter { it.openTimeMillis >= since }
        if (window.isEmpty()) return null
        return PriceRange(
            high = window.maxOf { it.high },
            low = window.minOf { it.low },
            // The close of the most recent bar, not of the last one in the list: a sender
            // that paged backwards would otherwise report the oldest price as current.
            last = window.maxBy { it.openTimeMillis }.close,
        )
    }
}
