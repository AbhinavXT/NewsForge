package com.abhinavxt.newsforge.core.desk

import com.abhinavxt.newsforge.core.quote.CandleInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge format, which is the part of this feature two codebases have to agree on.
 *
 * Whatever TickerForge writes, this has to read — and a decoder that quietly drops a row
 * or slides the timestamps by one bar produces a chart that looks entirely plausible and
 * is wrong. So the tests are mostly about what happens to malformed input.
 */
class CandlesTest {

    private fun payload(
        version: String = "1",
        kind: String = "candles",
        symbol: String? = "GRSE",
        interval: String? = "day",
        t0: String? = "1758844800",
        bars: String? = "0,412.35,418,410.1,416.75,1234567",
    ): Map<String, String?> = buildMap {
        put(DeskPayloads.VERSION_KEY, version)
        put("kind", kind)
        symbol?.let { put("symbol", it) }
        interval?.let { put("interval", it) }
        t0?.let { put("t0", it) }
        bars?.let { put("bars", it) }
    }

    @Test
    fun readsASingleBar() {
        val batch = Candles.fromFlat(payload())!!
        assertEquals("GRSE", batch.symbol)
        assertEquals(CandleInterval.DAY, batch.interval)
        assertEquals(1, batch.candles.size)

        val candle = batch.candles.single()
        assertEquals(1_758_844_800_000L, candle.openTimeMillis)
        assertEquals(412.35, candle.open, 1e-9)
        assertEquals(418.0, candle.high, 1e-9)
        assertEquals(410.1, candle.low, 1e-9)
        assertEquals(416.75, candle.close, 1e-9)
        assertEquals(1_234_567.0, candle.volume, 1e-9)
    }

    /**
     * The offsets are cumulative, so a weekend is one larger number rather than a gap.
     *
     * Reading them as absolute offsets from `t0` instead would put every bar after the
     * first in the wrong place — and on a daily chart the error is a day, which is
     * invisible.
     */
    @Test
    fun offsetsAccumulate() {
        val batch = Candles.fromFlat(
            payload(
                bars = "0,10,11,9,10.5,100;" +
                    "86400,10.5,12,10,11.5,200;" +
                    "259200,11.5,13,11,12.5,300",
            )
        )!!
        assertEquals(
            listOf(1_758_844_800_000L, 1_758_931_200_000L, 1_759_190_400_000L),
            batch.candles.map { it.openTimeMillis },
        )
    }

    /**
     * A rejected row must not advance the clock, or every bar after it shifts.
     *
     * This is the failure worth guarding: the chart still draws, the prices are all real,
     * and the dates are silently wrong from the bad row onward.
     */
    @Test
    fun aMalformedRowDoesNotShiftTheBarsAfterIt() {
        val batch = Candles.fromFlat(
            payload(
                bars = "0,10,11,9,10.5,100;" +
                    "nonsense;" +
                    "86400,10.5,12,10,11.5,200",
            )
        )!!
        assertEquals(2, batch.candles.size)
        assertEquals(
            listOf(1_758_844_800_000L, 1_758_931_200_000L),
            batch.candles.map { it.openTimeMillis },
        )
    }

    @Test
    fun aRowWithTooFewFieldsIsSkipped() {
        val batch = Candles.fromFlat(payload(bars = "0,10,11,9;86400,10.5,12,10,11.5,200"))!!
        assertEquals(1, batch.candles.size)
        assertEquals(11.5, batch.candles.single().close, 1e-9)
    }

    /** A high below its low is not a bar, and would poison the 52-week extremes. */
    @Test
    fun anInvertedBarIsRejected() {
        val batch = Candles.fromFlat(payload(bars = "0,10,5,9,10.5,100;86400,10.5,12,10,11.5,200"))!!
        assertEquals(1, batch.candles.size)
        assertEquals(11.5, batch.candles.single().close, 1e-9)
    }

    @Test
    fun barsAreSortedEvenWhenTheSenderPagedBackwards() {
        // Negative deltas: a sender walking backwards from a starting point.
        val batch = Candles.fromFlat(payload(bars = "0,10,11,9,10.5,100;-86400,9,10,8,9.5,50"))!!
        assertTrue(batch.candles[0].openTimeMillis < batch.candles[1].openTimeMillis)
        assertEquals(9.5, batch.candles[0].close, 1e-9)
    }

    // -------------------------------------------------------------- not mine

    @Test
    fun otherPayloadKindsAreNotCandles() {
        assertNull(Candles.fromFlat(payload(kind = "quote")))
    }

    @Test
    fun anUnsupportedVersionIsRejected() {
        assertNull(Candles.fromFlat(payload(version = "99")))
    }

    @Test
    fun anUnknownIntervalIsRejectedRatherThanGuessed() {
        assertNull(Candles.fromFlat(payload(interval = "fortnight")))
    }

    @Test
    fun missingFieldsProduceNothing() {
        assertNull(Candles.fromFlat(payload(symbol = null)))
        assertNull(Candles.fromFlat(payload(t0 = null)))
        assertNull(Candles.fromFlat(payload(bars = null)))
        assertNull(Candles.fromFlat(payload(bars = "")))
    }

    /** Null, not an empty batch: "not for me" and "no history" are different answers. */
    @Test
    fun aPayloadWithNoUsableRowsIsNull() {
        assertNull(Candles.fromFlat(payload(bars = "garbage;more garbage")))
    }

    // ------------------------------------------------------------- the ask

    @Test
    fun theRequestLineIsShapedForTheDesk() {
        assertEquals("/candles GRSE day 300", Candles.request("grse", CandleInterval.DAY, 300))
        assertEquals(
            "/candles MAZDOCK 5minute 400",
            Candles.request(" mazdock ", CandleInterval.FIVE_MINUTE, 400),
        )
    }

    /**
     * The quote decoder must not also claim these.
     *
     * Both schemas carry `nf` and a top-level `symbol`, and the quote decoder treats an
     * unknown kind as a quote — so before it learned about this one, every batch of bars
     * landed in the desk list as a price-less phantom quote alongside the real ones.
     */
    @Test
    fun aCandlePayloadIsNotReadAsAQuote() {
        assertNull(DeskPayloads.fromFlat(payload()))
        assertNull(DeskPayloads.batchFromFlat(payload()).takeIf { it.isNotEmpty() })
    }

    // -------------------------------------------------------------- budget

    /**
     * The format has to fit ntfy's message limit, which is the constraint that chose it.
     *
     * How many bars fit depends on how wide the prices are: a three-digit price gets
     * about ninety-five into four kilobytes, a six-digit one about seventy-four. Seventy
     * is the figure that holds for anything on the exchange, MRF included, so it is the
     * batch size the desk should send — roughly fourteen weeks, enough to be a useful
     * first screen while the rest arrives behind it.
     *
     * If this fails, the encoding got wider and the desk will start silently failing to
     * send rather than erroring.
     */
    @Test
    fun seventyDailyBarsFitInFourKilobytesAtAnyPrice() {
        // Six digits and eight-figure volume: wider than anything that actually trades.
        val rows = (0 until 70).joinToString(";") { "86400,142512.35,142618.00,142410.10,142516.75,12345678" }
        val body = """{"nf":1,"kind":"candles","symbol":"GRSE","interval":"day",""" +
            """"t0":1758844800,"bars":"$rows"}"""
        assertTrue("payload was ${body.toByteArray().size} bytes", body.toByteArray().size < 4096)

        val parsed = Candles.fromFlat(payload(bars = rows))!!
        assertEquals(70, parsed.candles.size)
    }
}
