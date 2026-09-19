package com.abhinavxt.newsforge.core.quote

import com.abhinavxt.newsforge.core.desk.DeskPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceSummaryTest {

    private val day = 24L * 60 * 60 * 1000

    private fun bars(vararg closes: Double): List<Candle> =
        closes.mapIndexed { i, close ->
            Candle(
                openTimeMillis = (i + 1) * day,
                open = close - 2,
                high = close + 3,
                low = close - 4,
                close = close,
                volume = 1_000_000.0 + i,
            )
        }

    private fun quote(
        ltp: Double? = null,
        previousClose: Double? = null,
        dayOpen: Double? = null,
    ) = DeskPayload(
        symbol = "BEL",
        kind = "quote",
        timestampMillis = 5 * day,
        ltp = ltp,
        previousClose = previousClose,
        dayOpen = dayOpen,
    )

    /** The gap this exists to fill: history but no quote, which used to render nothing. */
    @Test
    fun theNewestCloseIsAPriceWhenThereIsNoQuote() {
        val price = PriceSummaries.of(quote = null, dailyCandles = bars(100.0, 102.0, 105.0))!!
        assertEquals(105.0, price.last, 1e-9)
        assertEquals(PriceSummary.Source.LAST_CLOSE, price.source)
        assertEquals(3 * day, price.asOfMillis)
    }

    /** Measured against the bar before, not against the newest bar's own open. */
    @Test
    fun changeIsAgainstThePreviousClose() {
        val price = PriceSummaries.of(null, bars(100.0, 102.0, 105.0))!!
        assertEquals(102.0, price.previousClose!!, 1e-9)
        assertEquals(3.0, price.change!!, 1e-9)
        assertEquals(2.94, price.changePercent!!, 0.01)
    }

    @Test
    fun aLiveQuoteWinsOverTheNewestBar() {
        val price = PriceSummaries.of(quote(ltp = 110.0), bars(100.0, 102.0, 105.0))!!
        assertEquals(110.0, price.last, 1e-9)
        assertEquals(PriceSummary.Source.LIVE, price.source)
    }

    /**
     * A quote's gaps are filled from the bar for the same session.
     *
     * The exchange's index response often carries a last price and a previous close and
     * nothing else, while the bar sitting next to it has the open, high, low and volume.
     * Leaving them empty would mean a live price above four blank fields.
     */
    @Test
    fun aQuoteWithoutDayFiguresBorrowsThemFromTheBar() {
        val price = PriceSummaries.of(quote(ltp = 110.0), bars(100.0, 105.0))!!
        assertEquals(103.0, price.open!!, 1e-9)
        assertEquals(108.0, price.high!!, 1e-9)
        assertEquals(101.0, price.low!!, 1e-9)
    }

    @Test
    fun aQuoteThatHasItsOwnFiguresKeepsThem() {
        val price = PriceSummaries.of(
            quote(ltp = 110.0, previousClose = 99.0, dayOpen = 108.0),
            bars(100.0, 105.0),
        )!!
        assertEquals(99.0, price.previousClose!!, 1e-9)
        assertEquals(108.0, price.open!!, 1e-9)
    }

    @Test
    fun aSingleBarHasNoPreviousClose() {
        val price = PriceSummaries.of(null, bars(100.0))!!
        assertEquals(100.0, price.last, 1e-9)
        assertNull(price.previousClose)
        assertNull(price.change)
        assertNull(price.changePercent)
    }

    /** A quote with no last price is not a price; the bars still are. */
    @Test
    fun aPricelessQuoteFallsThroughToTheBars() {
        val price = PriceSummaries.of(quote(ltp = null), bars(100.0, 105.0))!!
        assertEquals(105.0, price.last, 1e-9)
        assertEquals(PriceSummary.Source.LAST_CLOSE, price.source)
    }

    @Test
    fun nothingAtAllProducesNothing() {
        assertNull(PriceSummaries.of(null, emptyList()))
        assertNull(PriceSummaries.of(quote(ltp = null), emptyList()))
    }

    @Test
    fun theNewestBarIsTakenByTimeNotByPosition() {
        val reversed = bars(100.0, 102.0, 105.0).reversed()
        assertEquals(105.0, PriceSummaries.of(null, reversed)!!.last, 1e-9)
    }

    @Test
    fun positionInTheDayRangeIsClamped() {
        val price = PriceSummaries.of(null, bars(100.0))!!
        // close 100, high 103, low 96 → (100-96)/(103-96)
        assertEquals(0.571, price.positionInDayRange!!, 0.01)
    }

    @Test
    fun aFlatDayHasNoPosition() {
        val flat = listOf(Candle(day, 50.0, 50.0, 50.0, 50.0, 10.0))
        assertNull(PriceSummaries.of(null, flat)!!.positionInDayRange)
    }
}
