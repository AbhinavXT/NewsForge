package com.abhinavxt.newsforge.core.ta

import com.abhinavxt.newsforge.core.quote.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Indicators are the one part of this feature with a right answer.
 *
 * A chart that is drawn slightly wrong is visibly wrong; an RSI that is drawn slightly
 * wrong looks exactly like an RSI. So the tests pin the two things that go silently
 * wrong in every implementation of these: the index at which a series becomes defined,
 * and the behaviour at the ends of the range.
 */
class IndicatorsTest {

    private fun bars(closes: List<Double>, volume: Double = 1_000.0): List<Candle> =
        closes.mapIndexed { i, close ->
            Candle(
                openTimeMillis = i * 86_400_000L,
                open = close,
                high = close + 1,
                low = close - 1,
                close = close,
                volume = volume,
            )
        }

    // ------------------------------------------------------------------ RSI

    /**
     * Wilder's own worked series.
     *
     * The published tables give 70.53 for the first value; this returns 70.46, and the
     * gap is the table, not the arithmetic — those figures are computed from closes with
     * more precision than the two decimal places they are printed to. The tolerance is
     * set to admit that and nothing larger.
     */
    @Test
    fun rsiMatchesWildersWorkedExample() {
        val closes = listOf(
            44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08,
            45.89, 46.03, 45.61, 46.28, 46.28, 46.00, 46.03, 46.41, 46.22, 45.64,
            46.21, 46.25, 45.71, 46.45, 45.78, 45.35, 44.03, 44.18, 44.22, 44.57,
        )
        val values = Indicators.rsi(closes)
        assertEquals(70.46, values[14]!!, 0.1)
        assertEquals(66.25, values[15]!!, 0.1)
        assertEquals(57.92, values[19]!!, 0.1)
    }

    @Test
    fun rsiIsUndefinedUntilItHasAFullPeriod() {
        val values = Indicators.rsi(bars((0 until 30).map { 100.0 + it }).map { it.close })
        for (i in 0 until Indicators.RSI_PERIOD) assertNull("index $i", values[i])
        assertNotNull(values[Indicators.RSI_PERIOD])
    }

    @Test
    fun rsiSaturatesRatherThanDividingByZero() {
        // An unbroken run of gains has no average loss to divide by. 100 is the right
        // answer; an infinity renders as an empty pane.
        assertEquals(100.0, Indicators.rsi((0 until 30).map { 100.0 + it }).last()!!, 0.001)
        assertEquals(0.0, Indicators.rsi((0 until 30).map { 100.0 - it }).last()!!, 0.001)
        // Neither gains nor losses: no strength either way, which is the midpoint.
        assertEquals(50.0, Indicators.rsi(List(30) { 100.0 }).last()!!, 0.001)
    }

    @Test
    fun rsiStaysInRange() {
        val closes = (0 until 120).map { 100.0 + 10 * kotlin.math.sin(it / 7.0) + it * 0.3 }
        for (value in Indicators.rsi(closes).filterNotNull()) {
            assertTrue("$value out of range", value in 0.0..100.0)
        }
    }

    // ----------------------------------------------------------------- MACD

    /**
     * The alignment, which is where MACD is usually got wrong.
     *
     * The line needs `slow` closes, so it starts at index 25. The signal is an EMA of the
     * *line*, so it needs nine defined line values and starts at 33 — not at index 8. A
     * signal that appears twenty-five bars early is meaningless for all of them and looks
     * entirely plausible on a chart.
     */
    @Test
    fun macdSeriesBecomeDefinedAtTheRightIndices() {
        val closes = (0 until 80).map { 100.0 + 10 * kotlin.math.sin(it / 7.0) + it * 0.3 }
        val macd = Indicators.macd(closes)

        assertEquals(25, macd.line.indexOfFirst { it != null })
        assertEquals(33, macd.signal.indexOfFirst { it != null })
        assertEquals(33, macd.histogram.indexOfFirst { it != null })
    }

    @Test
    fun macdSeriesAreTheLengthOfTheInput() {
        val closes = (0 until 40).map { 100.0 + it }
        val macd = Indicators.macd(closes)
        assertEquals(closes.size, macd.line.size)
        assertEquals(closes.size, macd.signal.size)
        assertEquals(closes.size, macd.histogram.size)
    }

    @Test
    fun histogramIsTheGapBetweenLineAndSignal() {
        val closes = (0 until 80).map { 100.0 + 10 * kotlin.math.sin(it / 5.0) }
        val macd = Indicators.macd(closes)
        for (i in closes.indices) {
            val line = macd.line[i]
            val signal = macd.signal[i]
            val histogram = macd.histogram[i]
            if (line == null || signal == null) {
                assertNull("index $i", histogram)
            } else {
                assertEquals(line - signal, histogram!!, 1e-9)
            }
        }
    }

    @Test
    fun tooFewClosesProduceNoMacdRatherThanAnException() {
        val macd = Indicators.macd(listOf(100.0, 101.0, 102.0))
        assertEquals(3, macd.line.size)
        assertTrue(macd.line.all { it == null })
        assertTrue(macd.signal.all { it == null })
    }

    // ------------------------------------------------------------------ MFI

    @Test
    fun mfiSaturatesAtBothEnds() {
        assertEquals(100.0, Indicators.mfi(bars((0 until 30).map { 100.0 + it })).last()!!, 0.001)
        assertEquals(0.0, Indicators.mfi(bars((0 until 30).map { 100.0 - it })).last()!!, 0.001)
    }

    /** A bar whose typical price is unchanged is neither inflow nor outflow, by definition. */
    @Test
    fun mfiTreatsAnUnchangedBarAsNeitherSide() {
        assertEquals(50.0, Indicators.mfi(bars(List(30) { 100.0 })).last()!!, 0.001)
    }

    @Test
    fun mfiIsUndefinedUntilItHasAFullPeriod() {
        val values = Indicators.mfi(bars((0 until 30).map { 100.0 + it }))
        for (i in 0 until Indicators.MFI_PERIOD) assertNull("index $i", values[i])
        assertNotNull(values[Indicators.MFI_PERIOD])
    }

    /**
     * Volume is what separates MFI from RSI, so it has to actually change the answer.
     *
     * Same prices, but the down bars carry ten times the value of the up bars: money is
     * leaving on the falls even though the closes drift up, and MFI should say so while
     * RSI cannot.
     */
    @Test
    fun mfiRespondsToVolumeWhereRsiCannot() {
        val closes = (0 until 30).map { 100.0 + if (it % 2 == 0) it * 0.5 else it * 0.5 - 2 }
        val even = bars(closes)
        val weighted = even.mapIndexed { i, bar ->
            if (i % 2 == 0) bar else bar.copy(volume = bar.volume * 10)
        }
        val plain = Indicators.mfi(even).last()!!
        val skewed = Indicators.mfi(weighted).last()!!
        assertTrue("volume did not move MFI: $plain vs $skewed", kotlin.math.abs(plain - skewed) > 1.0)
    }

    // ---------------------------------------------------------------- range

    @Test
    fun rangeIsTakenOverTheWindowOnly() {
        val now = 400L * 24 * 60 * 60 * 1000
        val old = Candle(0L, 10.0, 999.0, 5.0, 10.0, 1.0)
        val recent = (0 until 10).map {
            val at = now - (9L - it) * 24 * 60 * 60 * 1000
            Candle(at, 100.0, 110.0, 90.0, 100.0 + it, 1.0)
        }
        val range = Range.over(listOf(old) + recent, now)!!
        // The 999 high sits outside the year and must not become the 52-week high.
        assertEquals(110.0, range.high, 1e-9)
        assertEquals(90.0, range.low, 1e-9)
        assertEquals(109.0, range.last, 1e-9)
    }

    /** The newest bar's close, not the last element — a sender may page backwards. */
    @Test
    fun rangeTakesTheLastPriceFromTheNewestBar() {
        val now = 10L * 24 * 60 * 60 * 1000
        val newest = Candle(now, 100.0, 105.0, 95.0, 104.0, 1.0)
        val older = Candle(now - 86_400_000L, 100.0, 102.0, 98.0, 99.0, 1.0)
        assertEquals(104.0, Range.over(listOf(newest, older), now)!!.last, 1e-9)
    }

    @Test
    fun aFlatRangeHasNoPosition() {
        assertNull(PriceRange(high = 50.0, low = 50.0, last = 50.0).position)
    }

    @Test
    fun positionIsClampedToTheRange() {
        assertEquals(0.5, PriceRange(high = 110.0, low = 90.0, last = 100.0).position!!, 1e-9)
        assertEquals(1.0, PriceRange(high = 110.0, low = 90.0, last = 120.0).position!!, 1e-9)
        assertEquals(0.0, PriceRange(high = 110.0, low = 90.0, last = 80.0).position!!, 1e-9)
    }

    @Test
    fun emptyInputProducesNoRange() {
        assertNull(Range.over(emptyList(), nowMillis = 1_000_000L))
    }
}
