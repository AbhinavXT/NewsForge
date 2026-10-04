package com.abhinavxt.newsforge.core.quote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStudyTest {

    private val minute = 60_000L
    private val published = 1_000_000_000L

    /** A minute-by-minute series at a flat price, from [fromMinutes] before publication. */
    private fun flat(
        price: Double,
        fromMinutes: Long,
        toMinutes: Long,
    ): List<PricePoint> = (-fromMinutes..toMinutes).map {
        PricePoint(published + it * minute, price)
    }

    /** Flat until publication, then stepped to [after]. */
    private fun step(before: Double, after: Double, toMinutes: Long): List<PricePoint> =
        (-30..toMinutes).map { offset ->
            PricePoint(published + offset * minute, if (offset <= 0) before else after)
        }

    @Test
    fun measuresTheMoveFromTheLastPriceBeforePublication() {
        val result = EventStudy.of(
            symbol = "INFY",
            series = step(before = 100.0, after = 102.0, toMinutes = 120),
            publishedAt = published,
            nowMillis = published + 5 * 60 * minute,
        )
        val fifteen = result.outcomes.first { it.horizon == EventHorizon.FIFTEEN_MINUTES }
        assertEquals(2.0, fifteen.percent, 0.0001)
        assertEquals(100.0, fifteen.basePrice, 0.0001)
        assertEquals(published, fifteen.baseAtMillis)
        assertTrue(fifteen.complete)
    }

    @Test
    fun theBaseIsNeverTakenFromAfterPublication() {
        // A price observed thirty seconds after the story may already contain the
        // reaction being measured, which would quietly report zero.
        val series = step(before = 100.0, after = 105.0, toMinutes = 60) +
            PricePoint(published + minute / 2, 105.0)
        val result = EventStudy.of("INFY", series, published, nowMillis = published + 60 * minute)
        val fifteen = result.outcomes.first { it.horizon == EventHorizon.FIFTEEN_MINUTES }
        assertEquals(100.0, fifteen.basePrice, 0.0001)
        assertEquals(published, fifteen.baseAtMillis)
        assertEquals(5.0, fifteen.percent, 0.0001)
    }

    @Test
    fun aStaleBaseIsRefusedRatherThanUsed() {
        // Nothing sampled near publication: the app was asleep, or the story is
        // overnight. A study from an hour-old base is a fabricated claim about cause.
        val series = listOf(
            PricePoint(published - 90 * minute, 100.0),
            PricePoint(published + 30 * minute, 110.0),
        )
        val result = EventStudy.of("INFY", series, published, nowMillis = published + 60 * minute)
        assertTrue(result.isEmpty)
    }

    @Test
    fun anUncoveredWindowIsOmittedNotEstimated() {
        // Sampling stops twelve minutes in — the session ended, or polling failed.
        // Reporting that as the one-hour move would be the kind of wrong that looks right.
        val result = EventStudy.of(
            symbol = "INFY",
            series = step(before = 100.0, after = 103.0, toMinutes = 12),
            publishedAt = published,
            nowMillis = published + 24 * 60 * minute,
        )
        val horizons = result.outcomes.map { it.horizon }
        assertTrue(EventHorizon.FIFTEEN_MINUTES in horizons)
        assertFalse(EventHorizon.ONE_HOUR in horizons)
        assertFalse(EventHorizon.ONE_DAY in horizons)
    }

    @Test
    fun toleranceScalesWithTheWindow() {
        // Five minutes missing from fifteen is a third of it; five missing from a day is
        // nothing.
        assertEquals(5 * minute, EventStudy.toleranceFor(EventHorizon.FIFTEEN_MINUTES))
        assertEquals(6 * minute, EventStudy.toleranceFor(EventHorizon.ONE_HOUR))
        assertEquals(144 * minute, EventStudy.toleranceFor(EventHorizon.ONE_DAY))
    }

    @Test
    fun anOpenWindowIsReportedAsSoFar() {
        val result = EventStudy.of(
            symbol = "INFY",
            series = step(before = 100.0, after = 101.0, toMinutes = 20),
            publishedAt = published,
            // Twenty minutes in: the fifteen-minute window has closed, the hour has not.
            nowMillis = published + 20 * minute,
        )
        val fifteen = result.outcomes.first { it.horizon == EventHorizon.FIFTEEN_MINUTES }
        assertTrue(fifteen.complete)
        assertNull(result.outcomes.firstOrNull { it.horizon == EventHorizon.ONE_HOUR })
        assertEquals(EventHorizon.FIFTEEN_MINUTES, result.settled?.horizon)
    }

    @Test
    fun aMoveInLineWithTheMarketIsNotAReaction() {
        // The whole point. Up 2% on a market up 2% is a stock that did nothing.
        val market = (1..10).associate { index ->
            "PEER$index" to step(before = 50.0, after = 51.0, toMinutes = 120)
        }
        val result = EventStudy.of(
            symbol = "INFY",
            series = step(before = 100.0, after = 102.0, toMinutes = 120),
            publishedAt = published,
            benchmark = market,
            nowMillis = published + 5 * 60 * minute,
        )
        val fifteen = result.outcomes.first { it.horizon == EventHorizon.FIFTEEN_MINUTES }
        assertEquals(2.0, fifteen.percent, 0.0001)
        assertEquals(2.0, fifteen.benchmarkPercent!!, 0.0001)
        assertEquals(0.0, fifteen.abnormalPercent!!, 0.0001)
    }

    @Test
    fun aMoveAgainstTheMarketShowsUpAsAbnormal() {
        val market = (1..10).associate { index ->
            "PEER$index" to step(before = 50.0, after = 49.5, toMinutes = 120)
        }
        val result = EventStudy.of(
            symbol = "INFY",
            series = step(before = 100.0, after = 103.0, toMinutes = 120),
            publishedAt = published,
            benchmark = market,
            nowMillis = published + 5 * 60 * minute,
        )
        val fifteen = result.outcomes.first { it.horizon == EventHorizon.FIFTEEN_MINUTES }
        // Up 3% while the market was down 1% is four points of abnormal move.
        assertEquals(4.0, fifteen.abnormalPercent!!, 0.0001)
    }

    @Test
    fun tooFewPeersMeansNoBenchmarkRatherThanABadOne() {
        val thin = (1..3).associate { index ->
            "PEER$index" to step(before = 50.0, after = 51.0, toMinutes = 120)
        }
        val result = EventStudy.of(
            symbol = "INFY",
            series = step(before = 100.0, after = 102.0, toMinutes = 120),
            publishedAt = published,
            benchmark = thin,
            nowMillis = published + 5 * 60 * minute,
        )
        val fifteen = result.outcomes.first { it.horizon == EventHorizon.FIFTEEN_MINUTES }
        assertNull(fifteen.benchmarkPercent)
        assertNull(fifteen.abnormalPercent)
    }

    @Test
    fun peersMissingTheWindowAreDroppedFromTheBenchmark() {
        // Nine peers cover the window and two do not; the two must not drag the median
        // toward zero by being counted as flat.
        val covered = (1..9).associate { index ->
            "PEER$index" to step(before = 50.0, after = 52.0, toMinutes = 120)
        }
        val absent = mapOf(
            "GONE1" to listOf(PricePoint(published - 90 * minute, 10.0)),
            "GONE2" to emptyList<PricePoint>(),
        )
        val result = EventStudy.of(
            symbol = "INFY",
            series = step(before = 100.0, after = 104.0, toMinutes = 120),
            publishedAt = published,
            benchmark = covered + absent,
            nowMillis = published + 5 * 60 * minute,
        )
        val fifteen = result.outcomes.first { it.horizon == EventHorizon.FIFTEEN_MINUTES }
        assertEquals(4.0, fifteen.benchmarkPercent!!, 0.0001)
        assertEquals(0.0, fifteen.abnormalPercent!!, 0.0001)
    }

    @Test
    fun aStoryAfterTheLastSampleOfTheDayMeasuresNothing() {
        // Sampling stops at publication — the session closed. Every window would have to
        // be measured from the base to itself, and none of them is reported.
        val series = flat(100.0, fromMinutes = 30, toMinutes = 0)
        val result = EventStudy.of(
            "INFY", series, published, nowMillis = published + 24 * 60 * minute
        )
        assertTrue(result.isEmpty)
    }

    @Test
    fun nonPositivePricesAreIgnored() {
        val series = listOf(
            PricePoint(published - 3 * minute, 0.0),
            PricePoint(published - 2 * minute, 100.0),
            PricePoint(published + 14 * minute, 101.0),
        )
        val result = EventStudy.of(
            "INFY", series, published, nowMillis = published + 60 * minute
        )
        assertEquals(100.0, result.outcomes.first().basePrice, 0.0001)
    }

    @Test
    fun candlesAreStampedAtTheEndOfTheirInterval() {
        // A bar labelled 10:05 covers 10:05 to 10:10, and its close is what the stock was
        // worth at 10:10. Dating it 10:05 would shift every observation one bar early.
        val candle = Candle(
            openTimeMillis = published,
            open = 100.0,
            high = 101.0,
            low = 99.0,
            close = 100.5,
            volume = 1000.0,
        )
        val points = EventStudy.fromCandles(listOf(candle), CandleInterval.FIVE_MINUTE)
        assertEquals(published + 5 * minute, points.single().atMillis)
        assertEquals(100.5, points.single().price, 0.0001)
    }

    @Test
    fun samplesConvertStraightThrough() {
        val points = EventStudy.fromSamples(
            listOf(PriceSample("INFY", published, 100.0))
        )
        assertEquals(published, points.single().atMillis)
        assertEquals(100.0, points.single().price, 0.0001)
    }

    @Test
    fun anEmptySeriesIsEmptyNotZero() {
        val result = EventStudy.of(
            "INFY", emptyList(), published, nowMillis = published + 60 * minute
        )
        assertTrue(result.isEmpty)
        assertNull(result.settled)
    }
}
