package com.abhinavxt.newsforge.core.quote

import com.abhinavxt.newsforge.core.desk.DeskPayload
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Prices for the whole feed, from the exchange's own index endpoint.
 *
 * The desk bridge gives real, licensed, real-time quotes — but only while TickerForge is
 * running, which is not most evenings and not when you are away from the machine. This is
 * the baseline underneath it: NSE publishes every constituent of an index in one response,
 * with last price and previous close, which is exactly the two numbers a headline needs
 * beside it. One request covers hundreds of symbols.
 *
 * Deliberately not scraped from a research site. Those have no bulk endpoint, so a feed
 * tagging twenty companies would mean twenty HTML page loads a minute from a phone; the
 * markup changes without notice and turns every price into a dash when it does; and their
 * terms prohibit it. This is the exchange publishing its own data as JSON.
 *
 * What it is not is live. Exchange web data is delayed by regulation, which is why these
 * carry a wider staleness window than a desk quote and why the desk's numbers win
 * wherever both exist.
 */
object ExchangeQuotes {

    /**
     * The index whose constituents get prices.
     *
     * A breadth-for-cost trade with no right answer. NIFTY 500 is roughly ninety per cent
     * of free-float market cap in a single response, so nearly every name that reaches a
     * news feed is in it — but not all of them, and a small cap that only appears when
     * something happens to it is exactly the kind of name you would want a price for.
     * Editable here rather than in settings because changing it is rare and because the
     * valid values are NSE's, not ours.
     */
    const val DEFAULT_INDEX: String = "NIFTY 500"

    fun urlFor(index: String = DEFAULT_INDEX): String =
        "https://www.nseindia.com/api/equity-stockIndices?index=" +
            index.trim().replace(" ", "%20")

    /**
     * @param wanted symbols worth keeping. The response covers the whole index; only the
     *   names the feed has actually tagged are carried forward, so a week of quiet news
     *   holds twenty quotes rather than five hundred.
     * @param fetchedAtMillis used when a row carries no usable timestamp of its own.
     */
    fun fromRows(
        rows: List<Map<String, String?>>,
        wanted: Set<String>,
        fetchedAtMillis: Long,
        zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    ): List<DeskPayload> = rows.mapNotNull { row ->
        val symbol = row["symbol"]?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        if (wanted.isNotEmpty() && symbol !in wanted) return@mapNotNull null
        // The index row for the index itself — "NIFTY 500" appears among its own
        // constituents — has no last price and is not a company.
        val last = number(row["lastPrice"]) ?: return@mapNotNull null

        DeskPayload(
            symbol = symbol,
            kind = DeskPayload.KIND_EXCHANGE,
            timestampMillis = timestampOf(row["lastUpdateTime"], zone) ?: fetchedAtMillis,
            ltp = last,
            previousClose = number(row["previousClose"]),
            dayOpen = number(row["open"]),
            dayHigh = number(row["dayHigh"]),
            dayLow = number(row["dayLow"]),
            // Already in the response and previously thrown away. Free here, and the
            // only source of a 52-week range for a reader whose desk is not running —
            // Kite's quote does not carry one, so without this the research screen has
            // to wait on a year of candles arriving over the bridge before it can draw
            // a range at all.
            yearHigh = number(row["yearHigh"]),
            yearLow = number(row["yearLow"]),
            volume = number(row["totalTradedVolume"])?.toLong(),
        )
    }

    /**
     * NSE stamps rows like `17-Sep-2026 15:29:59`, in IST, with no offset.
     *
     * Parsed rather than replaced with the fetch time because the difference is the whole
     * point: a name that has not traded since 11:40 should read as an eleven-forty price,
     * not as a fresh one that happens to be flat. Unparseable falls back to the fetch
     * time, which is wrong in the safe direction — it can only make a quote look older
     * than it is once the row is genuinely current.
     */
    private fun timestampOf(raw: String?, zone: ZoneId): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            LocalDateTime.parse(text, STAMP).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.US)

    /** NSE sends numbers with thousands separators often enough to matter. */
    private fun number(raw: String?): Double? =
        raw?.trim()?.replace(",", "")?.toDoubleOrNull()
}
