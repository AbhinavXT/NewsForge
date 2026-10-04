package com.abhinavxt.newsforge.core.desk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteBatchTest {

    /** As NtfyJson would hand it over: nested objects already flattened to dotted keys. */
    private fun batch(vararg pairs: Pair<String, String?>): Map<String, String?> =
        mapOf("nf" to "1", "kind" to "quotes", *pairs)

    @Test
    fun readsOneQuotePerSymbolKey() {
        val quotes = DeskPayloads.batchFromFlat(
            batch(
                "ts" to "1789649400",
                "q.BEL.ltp" to "412.35",
                "q.BEL.prev_close" to "405.10",
                "q.INFY.ltp" to "1502.0",
                "q.INFY.day.h" to "1510.0",
                "q.INFY.day.l" to "1486.0",
            )
        )
        assertEquals(listOf("BEL", "INFY"), quotes.map { it.symbol })
        assertEquals(412.35, quotes[0].ltp ?: 0.0, 1e-9)
        assertEquals(1510.0, quotes[1].dayHigh ?: 0.0, 1e-9)
        // The shared timestamp reaches every entry.
        assertEquals(1789649400L * 1000, quotes[1].timestampMillis)
    }

    @Test
    fun anEntryCanOverrideTheSharedTimestamp() {
        // Quotes in one batch are not always taken at the same instant — a thin name may
        // not have traded since the previous cycle, and saying so is the difference
        // between a stale price being filtered and being rendered as live.
        val quotes = DeskPayloads.batchFromFlat(
            batch(
                "ts" to "1789649400",
                "q.BEL.ltp" to "412.35",
                "q.BEL.ts" to "1789649000",
            )
        )
        assertEquals(1789649000L * 1000, quotes.single().timestampMillis)
    }

    @Test
    fun symbolsAreUppercasedAndKeptInWireOrder() {
        val quotes = DeskPayloads.batchFromFlat(
            batch("q.bel.ltp" to "412.35", "q.Infy.ltp" to "1502.0")
        )
        assertEquals(listOf("BEL", "INFY"), quotes.map { it.symbol })
    }

    @Test
    fun anEntryWithNoLastPriceIsDropped() {
        // A key that matched nothing — a typo in the sender, or a name it had no data
        // for. A panel of dashes looks like a failed fetch.
        val quotes = DeskPayloads.batchFromFlat(
            batch("q.BEL.ltp" to "412.35", "q.GARBAGE.vwap" to "10.0")
        )
        assertEquals(listOf("BEL"), quotes.map { it.symbol })
    }

    @Test
    fun batchEntriesAreMarkedAsQuotes() {
        // Everything downstream filters on kind; a batch entry is a quote like any other.
        val quotes = DeskPayloads.batchFromFlat(batch("q.BEL.ltp" to "412.35"))
        assertEquals(DeskPayload.KIND_QUOTE, quotes.single().kind)
    }

    @Test
    fun levelsAreReadPerSymbolRatherThanSharedAcrossTheBatch() {
        val quotes = DeskPayloads.batchFromFlat(
            batch(
                "q.BEL.ltp" to "412.35",
                "q.BEL.levels.pdh" to "418.0",
                "q.INFY.ltp" to "1502.0",
                "q.INFY.levels.pdh" to "1515.0",
            )
        )
        assertEquals(mapOf("pdh" to 418.0), quotes[0].levels)
        assertEquals(mapOf("pdh" to 1515.0), quotes[1].levels)
    }

    @Test
    fun aSingleQuoteIsNotABatchAndABatchIsNotASingleQuote() {
        // The two schemas have to stay distinguishable, because the reader tries one and
        // then the other rather than branching on kind itself.
        val single = mapOf("nf" to "1", "kind" to "quote", "symbol" to "BEL", "ltp" to "412.35")
        assertTrue(DeskPayloads.batchFromFlat(single).isEmpty())
        assertNull(DeskPayloads.fromFlat(batch("q.BEL.ltp" to "412.35")))
    }

    @Test
    fun positionsAreNotQuotes() {
        assertTrue(
            DeskPayloads.batchFromFlat(
                mapOf("nf" to "1", "kind" to "positions", "holding" to "BEL")
            ).isEmpty()
        )
    }

    @Test
    fun anUnknownSchemaVersionIsIgnoredRatherThanGuessedAt() {
        assertTrue(
            DeskPayloads.batchFromFlat(
                mapOf("nf" to "2", "kind" to "quotes", "q.BEL.ltp" to "412.35")
            ).isEmpty()
        )
    }
}
