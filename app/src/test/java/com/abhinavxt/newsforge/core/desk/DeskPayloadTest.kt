package com.abhinavxt.newsforge.core.desk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskPayloadTest {

    private val now = 1_757_400_000_000L

    private fun parse(vararg pairs: Pair<String, String?>) =
        DeskPayloads.fromFlat(pairs.toMap())

    private fun quote(vararg extra: Pair<String, String?>): DeskPayload {
        val base = mapOf<String, String?>(
            "nf" to "1",
            "symbol" to "bel",
            "ts" to "1757400000",
        )
        return DeskPayloads.fromFlat(base + extra.toMap())!!
    }

    @Test
    fun parsesAFullQuote() {
        val payload = quote(
            "kind" to "quote",
            "ltp" to "312.4",
            "prev_close" to "306.8",
            "vwap" to "309.8",
            "day.o" to "307.0",
            "day.h" to "314.9",
            "day.l" to "306.1",
            "volume" to "1234567",
            "rel_volume" to "3.2",
            "levels.pdh" to "310.2",
            "levels.pdl" to "301.5",
            "confluence.score" to "72",
            "confluence.verdict" to "bullish",
        )
        assertEquals("BEL", payload.symbol)
        assertEquals(312.4, payload.ltp!!, 1e-9)
        assertEquals(1_757_400_000_000L, payload.timestampMillis)
        assertEquals(mapOf("pdh" to 310.2, "pdl" to 301.5), payload.levels)
        assertEquals(72, payload.confluenceScore)
        assertEquals(1234567L, payload.volume)
    }

    @Test
    fun onlyTheSymbolIsRequired() {
        val payload = parse("nf" to "1", "symbol" to "INFY")!!
        assertEquals("quote", payload.kind)
        assertNull(payload.ltp)
        assertNull(payload.timestampMillis)
    }

    @Test
    fun aMissingOrWrongVersionIsIgnored() {
        // Version-gated rather than shape-sniffed, so a future schema is ignored by an
        // older app instead of being rendered wrongly.
        assertNull(parse("symbol" to "BEL", "ltp" to "312"))
        assertNull(parse("nf" to "2", "symbol" to "BEL"))
        assertNull(parse("nf" to "junk", "symbol" to "BEL"))
    }

    @Test
    fun aMissingSymbolIsIgnored() {
        assertNull(parse("nf" to "1", "ltp" to "312"))
        assertNull(parse("nf" to "1", "symbol" to "  "))
    }

    @Test
    fun unparseableNumbersAreDroppedNotZeroed() {
        // Rendering a bad field as 0.00 would look like a real price.
        val payload = quote("ltp" to "n/a", "vwap" to "309.8")
        assertNull(payload.ltp)
        assertEquals(309.8, payload.vwap!!, 1e-9)
    }

    @Test
    fun timestampsAreConvertedFromSeconds() {
        assertEquals(1_757_400_000_000L, quote().timestampMillis)
    }

    @Test
    fun changePercentNeedsBothSides() {
        assertEquals(1.825, quote("ltp" to "312.4", "prev_close" to "306.8").changePercent!!, 0.01)
        assertNull(quote("ltp" to "312.4").changePercent)
        assertNull(quote("ltp" to "312.4", "prev_close" to "0").changePercent)
    }

    @Test
    fun vwapBiasIsNullWithoutBothPrices() {
        assertTrue(quote("ltp" to "312.4", "vwap" to "309.8").aboveVwap!!)
        assertFalse(quote("ltp" to "305.0", "vwap" to "309.8").aboveVwap!!)
        assertNull(quote("ltp" to "312.4").aboveVwap)
    }

    @Test
    fun dayRangePositionIsNullWhenTheRangeHasNoWidth() {
        // A stock at circuit would otherwise divide by zero and render pinned at one end,
        // which reads as information when it is the absence of it.
        assertNull(quote("ltp" to "300", "day.h" to "300", "day.l" to "300").positionInDayRange)
        assertEquals(
            0.5,
            quote("ltp" to "305", "day.h" to "310", "day.l" to "300").positionInDayRange!!,
            1e-9,
        )
    }

    @Test
    fun dayRangePositionIsClampedWhenThePriceLeadsTheRange() {
        // Quote and range can be a tick out of step between two snapshots.
        assertEquals(
            1.0,
            quote("ltp" to "315", "day.h" to "310", "day.l" to "300").positionInDayRange!!,
            1e-9,
        )
    }

    @Test
    fun aQuoteWithNoTimestampIsTreatedAsStale() {
        // Unknown age is not fresh; showing it as live is the mistake this prevents.
        assertTrue(parse("nf" to "1", "symbol" to "BEL", "ltp" to "1")!!.isStale(now))
    }

    @Test
    fun stalenessFollowsTheClock() {
        val payload = quote("ltp" to "312")
        assertFalse(payload.isStale(now + 60_000))
        assertTrue(payload.isStale(now + 20 * 60_000))
    }

    @Test
    fun proseIsNotMistakenForAPayload() {
        assertFalse(DeskPayloads.looksLikePayload("BEL crossed PDH at 310.20"))
        assertFalse(DeskPayloads.looksLikePayload("{\"some\":\"other json\"}"))
        assertTrue(DeskPayloads.looksLikePayload("  {\"nf\":1,\"symbol\":\"BEL\"}"))
    }
}
