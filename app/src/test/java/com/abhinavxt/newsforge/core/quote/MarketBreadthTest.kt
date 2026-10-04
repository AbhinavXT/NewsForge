package com.abhinavxt.newsforge.core.quote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketBreadthTest {

    private val now = 1_758_844_800_000L

    private fun constituents(vararg changes: Double): List<Map<String, String?>> =
        changes.mapIndexed { i, change ->
            val previous = 100.0
            mapOf(
                "symbol" to "SYM$i",
                "previousClose" to previous.toString(),
                "lastPrice" to (previous * (1 + change / 100)).toString(),
            )
        }

    @Test
    fun theMedianIsTheMiddleConstituent() {
        // Twenty-one names: ten flat-ish either side of a clear middle.
        val changes = (0 until 21).map { it.toDouble() - 10 }
        val breadth = MarketBreadths.fromRows(constituents(*changes.toDoubleArray()), "NIFTY 500", now)!!
        assertEquals(0.0, breadth.medianChangePercent, 0.01)
    }

    /**
     * The whole reason for a median rather than a mean.
     *
     * One name up three hundred per cent drags a mean of twenty-one stocks by fourteen
     * points and would report a flat market as a strong one.
     */
    @Test
    fun aRunawayConstituentDoesNotMoveTheMedian() {
        val ordinary = (0 until 20).map { 0.5 }
        val breadth = MarketBreadths.fromRows(
            constituents(*(ordinary + 300.0).toDoubleArray()),
            "NIFTY 500",
            now,
        )!!
        assertEquals(0.5, breadth.medianChangePercent, 0.01)
    }

    @Test
    fun advancesAndDeclinesAreCounted() {
        val breadth = MarketBreadths.fromRows(
            constituents(*(List(12) { 1.0 } + List(8) { -1.0 } + List(3) { 0.0 }).toDoubleArray()),
            "NIFTY 500",
            now,
        )!!
        assertEquals(12, breadth.advances)
        assertEquals(8, breadth.declines)
        assertEquals(3, breadth.unchanged)
        assertEquals(23, breadth.total)
    }

    /** NSE lists the index among its own constituents; it must not vote on the median. */
    @Test
    fun theIndexOwnRowIsSkipped() {
        val rows = constituents(*DoubleArray(20) { 1.0 }) + mapOf(
            "symbol" to "NIFTY 500",
            "previousClose" to "100",
            "lastPrice" to "150",
        )
        val breadth = MarketBreadths.fromRows(rows, "nifty 500", now)!!
        assertEquals(20, breadth.total)
        assertEquals(1.0, breadth.medianChangePercent, 0.01)
    }

    /**
     * A short response is no context, not flat context.
     *
     * The endpoint answers an unrecognised index with a handful of rows rather than an
     * error, and a "market" computed from six stocks would render exactly as confidently
     * as one computed from five hundred.
     */
    @Test
    fun tooFewConstituentsProduceNothing() {
        assertNull(MarketBreadths.fromRows(constituents(1.0, 2.0, 3.0), "NIFTY 500", now))
        assertNull(MarketBreadths.fromRows(emptyList(), "NIFTY 500", now))
    }

    @Test
    fun rowsWithoutUsablePricesAreIgnored() {
        val rows = constituents(*DoubleArray(20) { 1.0 }) + listOf(
            mapOf("symbol" to "NOPRICE", "previousClose" to "100"),
            mapOf("symbol" to "ZEROBASE", "previousClose" to "0", "lastPrice" to "10"),
        )
        assertEquals(20, MarketBreadths.fromRows(rows, "NIFTY 500", now)!!.total)
    }

    @Test
    fun relativeStrengthIsInPercentagePoints() {
        val breadth = MarketBreadths.fromRows(constituents(*DoubleArray(21) { 3.0 }), "NIFTY 500", now)!!
        assertEquals(1.0, breadth.relativeTo(4.0)!!, 0.01)
        assertEquals(-4.0, breadth.relativeTo(-1.0)!!, 0.01)
        assertNull(breadth.relativeTo(null))
    }

    /** The sign is always shown — a bare "0.30%" reads as a magnitude, not a direction. */
    @Test
    fun theMedianIsFormattedWithItsSign() {
        val up = MarketBreadths.fromRows(constituents(*DoubleArray(21) { 0.3 }), "NIFTY 500", now)!!
        assertEquals("+0.30%", up.formattedMedian())
        val down = MarketBreadths.fromRows(constituents(*DoubleArray(21) { -0.3 }), "NIFTY 500", now)!!
        assertEquals("-0.30%", down.formattedMedian())
    }
}
