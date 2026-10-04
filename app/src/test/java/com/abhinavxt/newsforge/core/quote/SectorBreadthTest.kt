package com.abhinavxt.newsforge.core.quote

import com.abhinavxt.newsforge.core.tag.Sector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectorBreadthTest {

    private fun row(symbol: String, last: String, previous: String) =
        mapOf<String, String?>(
            "symbol" to symbol,
            "lastPrice" to last,
            "previousClose" to previous,
        )

    /** Four names a sector, which is exactly the floor. */
    private val sectors = mapOf(
        "BANK1" to Sector.BANKING,
        "BANK2" to Sector.BANKING,
        "BANK3" to Sector.BANKING,
        "BANK4" to Sector.BANKING,
        "PHARMA1" to Sector.PHARMA,
        "PHARMA2" to Sector.PHARMA,
    )

    private fun classify(symbol: String): Sector? = sectors[symbol]

    @Test
    fun computesAMedianPerSector() {
        val rows = listOf(
            row("BANK1", "102", "100"),
            row("BANK2", "104", "100"),
            row("BANK3", "98", "100"),
            row("BANK4", "100", "100"),
            row("UNCLASSIFIED", "150", "100"),
        )
        val result = SectorBreadths.fromRows(rows, ::classify, "NIFTY 500", 1_000L)
        val banking = result.getValue(Sector.BANKING)
        // +2, +4, -2, 0 sorted is -2, 0, +2, +4 — the middle pair averages to +1.
        assertEquals(1.0, banking.medianChangePercent, 0.0001)
        assertEquals(2, banking.advances)
        assertEquals(1, banking.declines)
        assertEquals(1, banking.unchanged)
        assertEquals(4, banking.constituents)
    }

    @Test
    fun aSectorWithTooFewNamesIsAbsentRatherThanWrong() {
        val rows = listOf(
            row("BANK1", "102", "100"),
            row("BANK2", "104", "100"),
            row("BANK3", "98", "100"),
            row("BANK4", "100", "100"),
            row("PHARMA1", "110", "100"),
            row("PHARMA2", "112", "100"),
        )
        val result = SectorBreadths.fromRows(rows, ::classify, "NIFTY 500", 1_000L)
        assertTrue(Sector.BANKING in result)
        // Two names is not a sector, and reporting +11% from them would be read as one.
        assertNull(result[Sector.PHARMA])
    }

    @Test
    fun theIndexDoesNotVoteOnItself() {
        val rows = listOf(
            row("NIFTY 500", "100", "50"),
            row("BANK1", "101", "100"),
            row("BANK2", "101", "100"),
            row("BANK3", "101", "100"),
            row("BANK4", "101", "100"),
        )
        val result = SectorBreadths.fromRows(
            rows,
            { symbol -> if (symbol == "NIFTY 500") Sector.BANKING else classify(symbol) },
            "NIFTY 500",
            1_000L,
        )
        assertEquals(4, result.getValue(Sector.BANKING).constituents)
        assertEquals(1.0, result.getValue(Sector.BANKING).medianChangePercent, 0.0001)
    }

    @Test
    fun unusableRowsAreSkippedRatherThanCountedAsFlat() {
        val rows = listOf(
            row("BANK1", "102", "100"),
            row("BANK2", "104", "100"),
            row("BANK3", "98", "100"),
            row("BANK4", "100", "100"),
            // A zero previous close would divide by zero; a missing price says nothing.
            row("BANK1", "100", "0"),
            mapOf<String, String?>("symbol" to "BANK2", "lastPrice" to null),
        )
        val result = SectorBreadths.fromRows(rows, ::classify, "NIFTY 500", 1_000L)
        assertEquals(4, result.getValue(Sector.BANKING).constituents)
    }

    @Test
    fun thousandsSeparatorsSurvive() {
        val rows = (1..4).map { row("BANK$it", "1,100", "1,000") }
        val result = SectorBreadths.fromRows(rows, ::classify, "NIFTY 500", 1_000L)
        assertEquals(10.0, result.getValue(Sector.BANKING).medianChangePercent, 0.0001)
    }

    @Test
    fun theSectorIsReadAgainstTheMarketNotInIsolation() {
        val market = MarketBreadth(
            index = "NIFTY 500",
            medianChangePercent = 1.1,
            advances = 300,
            declines = 190,
            unchanged = 10,
            atMillis = 1_000L,
        )
        val rows = (1..4).map { row("BANK$it", "101.2", "100") }
        val banking = SectorBreadths
            .fromRows(rows, ::classify, "NIFTY 500", 1_000L)
            .getValue(Sector.BANKING)
        // Up 1.2% on a market up 1.1% is a sector that did essentially nothing.
        assertEquals(0.1, banking.relativeTo(market)!!, 0.0001)
        assertNull(banking.relativeTo(null))
    }

    @Test
    fun orderIsStableAcrossPolls() {
        val rows = (1..4).map { row("BANK$it", "101", "100") } +
            (1..4).map { row("PHARMA$it", "101", "100") }
        val classifier = { symbol: String ->
            when {
                symbol.startsWith("BANK") -> Sector.BANKING
                symbol.startsWith("PHARMA") -> Sector.PHARMA
                else -> null
            }
        }
        val first = SectorBreadths.fromRows(rows, classifier, "NIFTY 500", 1L).keys.toList()
        val second =
            SectorBreadths.fromRows(rows.reversed(), classifier, "NIFTY 500", 2L).keys.toList()
        // Enum order, not insertion order: a list that reshuffles every poll is
        // unreadable even when every number in it is correct.
        assertEquals(first, second)
    }
}
