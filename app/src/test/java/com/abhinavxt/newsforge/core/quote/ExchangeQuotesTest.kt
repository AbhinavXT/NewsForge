package com.abhinavxt.newsforge.core.quote

import com.abhinavxt.newsforge.core.desk.DeskPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ExchangeQuotesTest {

    private val ist = ZoneId.of("Asia/Kolkata")

    private fun at(text: String): Long =
        ZonedDateTime.parse(text + "+05:30[Asia/Kolkata]").toInstant().toEpochMilli()

    private fun row(vararg pairs: Pair<String, String?>) = mapOf(*pairs)

    private val fetchedAt = at("2026-09-17T15:40:00")

    /**
     * The 52-week figures, which the response has always carried and this used to drop.
     *
     * Worth a test because they are the only source of a 52-week range for a reader whose
     * desk is not running — Kite's quote does not return one — so a key name changing on
     * NSE's side would silently blank a panel rather than fail anything.
     */
    @Test
    fun readsTheFiftyTwoWeekRange() {
        val quote = ExchangeQuotes.fromRows(
            rows = listOf(
                row(
                    "symbol" to "BEL", "lastPrice" to "412.35", "previousClose" to "405.10",
                    "yearHigh" to "436.00", "yearLow" to "240.15",
                ),
            ),
            wanted = setOf("BEL"),
            fetchedAtMillis = fetchedAt,
            zone = ist,
        ).single()
        assertEquals(436.00, quote.yearHigh ?: 0.0, 1e-9)
        assertEquals(240.15, quote.yearLow ?: 0.0, 1e-9)
    }

    /** A row without them is still a usable quote; every field here is optional. */
    @Test
    fun aRowWithoutAYearRangeIsStillAQuote() {
        val quote = ExchangeQuotes.fromRows(
            rows = listOf(row("symbol" to "BEL", "lastPrice" to "412.35")),
            wanted = setOf("BEL"),
            fetchedAtMillis = fetchedAt,
            zone = ist,
        ).single()
        assertEquals(412.35, quote.ltp ?: 0.0, 1e-9)
        assertEquals(null, quote.yearHigh)
        assertEquals(null, quote.yearLow)
    }

    @Test
    fun readsLastPriceAndPreviousCloseForWantedSymbols() {
        val quotes = ExchangeQuotes.fromRows(
            rows = listOf(
                row(
                    "symbol" to "BEL", "lastPrice" to "412.35", "previousClose" to "405.10",
                    "open" to "406.00", "dayHigh" to "415.80", "dayLow" to "404.50",
                    "totalTradedVolume" to "8421000",
                    "lastUpdateTime" to "17-Sep-2026 15:29:59",
                ),
                row("symbol" to "INFY", "lastPrice" to "1502.00", "previousClose" to "1488.50"),
            ),
            wanted = setOf("BEL", "INFY"),
            fetchedAtMillis = fetchedAt,
            zone = ist,
        )
        assertEquals(listOf("BEL", "INFY"), quotes.map { it.symbol })
        val bel = quotes.first()
        assertEquals(412.35, bel.ltp ?: 0.0, 1e-9)
        assertEquals(8421000L, bel.volume)
        // The move the card shows comes out of the two prices, not out of NSE's pChange.
        assertEquals(1.79, bel.changePercent ?: 0.0, 0.01)
    }

    @Test
    fun onlyWantedSymbolsSurvive() {
        // The response is a whole index; the feed has tagged a handful of names. Holding
        // five hundred quotes to show two would be the wrong end of the trade.
        val quotes = ExchangeQuotes.fromRows(
            rows = listOf(
                row("symbol" to "BEL", "lastPrice" to "412.35"),
                row("symbol" to "RELIANCE", "lastPrice" to "1401.0"),
            ),
            wanted = setOf("BEL"),
            fetchedAtMillis = fetchedAt,
            zone = ist,
        )
        assertEquals(listOf("BEL"), quotes.map { it.symbol })
    }

    @Test
    fun theIndexRowItselfIsNotACompany() {
        // "NIFTY 500" appears among its own constituents, carrying no last price.
        val quotes = ExchangeQuotes.fromRows(
            rows = listOf(
                row("symbol" to "NIFTY 500", "lastPrice" to null),
                row("symbol" to "BEL", "lastPrice" to "412.35"),
            ),
            wanted = emptySet(),
            fetchedAtMillis = fetchedAt,
            zone = ist,
        )
        assertEquals(listOf("BEL"), quotes.map { it.symbol })
    }

    @Test
    fun anEmptyWantedSetKeepsEverything() {
        // The filter is a narrowing, not a gate — callers that want the lot pass nothing.
        val quotes = ExchangeQuotes.fromRows(
            rows = listOf(row("symbol" to "BEL", "lastPrice" to "412.35")),
            wanted = emptySet(),
            fetchedAtMillis = fetchedAt,
            zone = ist,
        )
        assertEquals(1, quotes.size)
    }

    @Test
    fun theRowsOwnTimestampIsPreferredToTheFetchTime() {
        // A name that has not traded since 11:40 must read as an eleven-forty price, not
        // as a fresh one that happens to be flat.
        val quotes = ExchangeQuotes.fromRows(
            rows = listOf(
                row(
                    "symbol" to "BEL", "lastPrice" to "412.35",
                    "lastUpdateTime" to "17-Sep-2026 11:40:00",
                )
            ),
            wanted = emptySet(),
            fetchedAtMillis = fetchedAt,
            zone = ist,
        )
        assertEquals(at("2026-09-17T11:40:00"), quotes.single().timestampMillis)
    }

    @Test
    fun anUnreadableTimestampFallsBackToTheFetchTime() {
        val quotes = ExchangeQuotes.fromRows(
            rows = listOf(
                row("symbol" to "BEL", "lastPrice" to "412.35", "lastUpdateTime" to "31-DEC-2999")
            ),
            wanted = emptySet(),
            fetchedAtMillis = fetchedAt,
            zone = ist,
        )
        assertEquals(fetchedAt, quotes.single().timestampMillis)
    }

    @Test
    fun thousandsSeparatorsAreTolerated() {
        val quotes = ExchangeQuotes.fromRows(
            rows = listOf(row("symbol" to "MRF", "lastPrice" to "1,42,350.75")),
            wanted = emptySet(),
            fetchedAtMillis = fetchedAt,
            zone = ist,
        )
        assertEquals(142350.75, quotes.single().ltp ?: 0.0, 1e-9)
    }

    @Test
    fun aRowWithNoSymbolIsSkippedRatherThanCrashing() {
        val quotes = ExchangeQuotes.fromRows(
            rows = listOf(row("lastPrice" to "412.35"), row("symbol" to "  ")),
            wanted = emptySet(),
            fetchedAtMillis = fetchedAt,
            zone = ist,
        )
        assertTrue(quotes.isEmpty())
    }

    @Test
    fun exchangeQuotesAreMarkedAsDelayedAndGetAWiderStaleWindow() {
        // Five minutes would mark every one of them stale on arrival, because this data
        // is published late by regulation — the feed would then show no prices at all.
        val quote = ExchangeQuotes.fromRows(
            rows = listOf(row("symbol" to "BEL", "lastPrice" to "412.35")),
            wanted = emptySet(),
            fetchedAtMillis = fetchedAt,
            zone = ist,
        ).single()
        assertEquals(DeskPayload.KIND_EXCHANGE, quote.kind)
        assertTrue(quote.isDelayed)
        assertTrue(quote.defaultStaleMillis > DeskPayload.DEFAULT_STALE_MS)
        assertTrue(!quote.isStale(fetchedAt + 10L * 60 * 1000))
        assertTrue(quote.isStale(fetchedAt + 30L * 60 * 1000))
    }

    @Test
    fun theUrlEncodesTheIndexName() {
        // A raw space here is a 400 from NSE, which reads as a block rather than a typo.
        assertTrue(ExchangeQuotes.urlFor("NIFTY 500").endsWith("index=NIFTY%20500"))
    }
}
