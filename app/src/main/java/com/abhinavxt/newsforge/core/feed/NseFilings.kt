package com.abhinavxt.newsforge.core.feed

import com.abhinavxt.newsforge.core.calendar.EventType
import com.abhinavxt.newsforge.core.calendar.UpcomingEvent
import com.abhinavxt.newsforge.core.model.ParsedItem
import java.security.MessageDigest
import java.util.Locale

/** What kind of document a feed serves. */
enum class FeedKind(val label: String) {
    RSS("RSS / Atom"),
    NSE_ANNOUNCEMENT("NSE announcements"),
    NSE_CORP_ACTION("NSE corporate actions"),
    NSE_BOARD_MEETING("NSE board meetings"),
    ;

    val isNse: Boolean get() = this != RSS
}

/**
 * Normalises NSE's JSON filing feeds.
 *
 * These are the primary record — the filing itself, not a report of it — and typically
 * land before the media that summarises them. That is the only latency edge available to
 * a retail reader, which is why they are worth a dedicated parser.
 *
 * The field mappings and the materiality rules below are ported from a working Python
 * poller rather than invented. That matters: NSE's schemas drift, the same logical field
 * arrives under two or three names depending on the day, and the fallback chains here
 * encode which ones have actually been seen in the wild.
 *
 * Records arrive as string maps, not JSON, so every rule in this file is testable without
 * an Android JSON parser on the classpath.
 */
object NseFilings {

    /**
     * Inclusion-based curation: a filing must match a category to survive.
     *
     * The exchange feed is mostly paperwork — ESOP allotments, trading-window notices,
     * newspaper publication copies, duplicate share certificates. Filtering by exclusion
     * would mean chasing every new species of noise forever; requiring a positive match
     * means new noise is silently ignored by default.
     */
    private val MATERIAL_KEYWORDS = listOf(
        "financial result", "unaudited", "audited result", "results for the quarter",
        "outcome of board meeting", "quarterly result", "standalone", "consolidated",
        "dividend",
        "conference call", "earnings call", "con. call", "concall", "analyst",
        "investor meet", "investors meet", "institutional investor",
        "bonus", "split", "sub-division", "subdivision", "buyback", "buy-back",
        "rights issue", "record date", "amalgamation", "demerger",
    )

    /** Checked first: these override any keyword match above. */
    private val EXCLUDE_KEYWORDS = listOf(
        "newspaper", "publication", "loss of share", "duplicate share",
        "trading window", "esop", "investor complaint", "reg. 74",
    )

    /**
     * Landing page used when a filing carries no attachment.
     *
     * The URL is the article's identity, so every filing needs a distinct one. The `nfid`
     * query parameter carries a hash of the filing's salient fields — the same shape of
     * identity the Python poller builds — and survives URL canonicalisation, which strips
     * only known tracking parameters.
     */
    private const val LANDING =
        "https://www.nseindia.com/companies-listing/corporate-filings-announcements"

    fun isMaterial(headline: String, detail: String): Boolean {
        val text = "$headline $detail".lowercase(Locale.US)
        if (EXCLUDE_KEYWORDS.any { it in text }) return false
        return MATERIAL_KEYWORDS.any { it in text }
    }

    /**
     * @param record one feed row, values already stringified.
     * @return null when the row is unusable or immaterial. Returning null rather than
     *   throwing is deliberate: one odd record must not abort a poll of two thousand.
     */
    fun normalize(kind: FeedKind, record: Map<String, String?>): ParsedItem? {
        val symbol: String
        val headline: String
        val detail: String
        val whenText: String
        val attachment: String

        when (kind) {
            FeedKind.NSE_ANNOUNCEMENT -> {
                symbol = first(record, "symbol", "sm_symbol")
                headline = first(record, "desc", "subject")
                detail = first(record, "attchmntText", "sm_desc").take(300)
                whenText = first(record, "an_dt", "sort_date", "exchdisstime")
                attachment = first(record, "attchmntFile")
            }

            FeedKind.NSE_CORP_ACTION -> {
                symbol = first(record, "symbol")
                headline = first(record, "subject", "purpose")
                val exDate = first(record, "exDate", "exdate")
                detail = if (exDate.isNotEmpty()) "Ex-date: $exDate" else ""
                whenText = exDate.ifEmpty { first(record, "recDate") }
                attachment = ""
            }

            FeedKind.NSE_BOARD_MEETING -> {
                symbol = first(record, "bm_symbol", "symbol")
                headline = first(record, "bm_purpose", "purpose")
                detail = first(record, "bm_desc", "desc").take(300)
                whenText = first(record, "bm_date", "meetingDate", "date")
                attachment = first(record, "attachment")
            }

            FeedKind.RSS -> return null
        }

        if (symbol.isEmpty() || headline.isEmpty()) return null
        if (!isMaterial(headline, detail)) return null

        val ticker = symbol.uppercase(Locale.US)
        return ParsedItem(
            // The symbol is prepended so the tagger sees it even when the filing's own
            // wording never names the company, which is the usual case: "Outcome of board
            // meeting" says nothing about who filed it.
            title = "$ticker — $headline",
            link = attachment.ifEmpty { landingUrl(kind, ticker, headline, whenText) },
            summary = detail.ifEmpty { null },
            publishedAtMillis = FeedDates.parse(whenText),
            guid = filingId(kind, ticker, headline, whenText),
            sourceName = "NSE",
        )
    }

    /**
     * Extracts a dated future obligation from a filing, if it carries one.
     *
     * Only the two structured feeds are read for this. Announcements do sometimes mention
     * a date in prose, but pulling one out of free text would produce a calendar that is
     * wrong often enough to be worse than empty — and being wrong about a results date is
     * precisely the failure this feature exists to prevent.
     *
     * The materiality filter is deliberately *not* applied here. A board meeting called
     * to consider a fundraise is not news yet, but it is absolutely a date you want to
     * know about before holding over it.
     */
    fun calendarEvent(kind: FeedKind, record: Map<String, String?>, feedId: String): UpcomingEvent? {
        val symbol: String
        val purpose: String
        val dateText: String

        when (kind) {
            FeedKind.NSE_BOARD_MEETING -> {
                symbol = first(record, "bm_symbol", "symbol")
                purpose = first(record, "bm_purpose", "purpose")
                dateText = first(record, "bm_date", "meetingDate", "date")
            }

            FeedKind.NSE_CORP_ACTION -> {
                symbol = first(record, "symbol")
                purpose = first(record, "subject", "purpose")
                // Ex-date is what actually matters for holding decisions; the record date
                // is the fallback because it is always within a day or two of it.
                dateText = first(record, "exDate", "exdate", "recDate")
            }

            else -> return null
        }

        if (symbol.isEmpty() || purpose.isEmpty()) return null
        val dateMillis = FeedDates.parse(dateText) ?: return null
        val ticker = symbol.uppercase(Locale.US)

        return UpcomingEvent(
            id = filingId(kind, ticker, purpose, dateText),
            symbol = ticker,
            type = eventTypeOf(kind, purpose),
            title = purpose,
            dateMillis = dateMillis,
            sourceFeedId = feedId,
        )
    }

    internal fun eventTypeOf(kind: FeedKind, purpose: String): EventType {
        val text = purpose.lowercase(Locale.US)
        return when (kind) {
            FeedKind.NSE_BOARD_MEETING ->
                if (RESULTS_HINTS.any { it in text }) EventType.RESULTS else EventType.BOARD_MEETING

            else ->
                if ("dividend" in text) EventType.DIVIDEND else EventType.CORPORATE_ACTION
        }
    }

    private val RESULTS_HINTS = listOf(
        "result", "financial statement", "quarterly", "audited", "unaudited",
    )

    /** Parses a whole feed, skipping unusable rows. */
    fun parse(kind: FeedKind, records: List<Map<String, String?>>): List<ParsedItem> =
        records.mapNotNull { normalize(kind, it) }

    fun calendarEvents(
        kind: FeedKind,
        records: List<Map<String, String?>>,
        feedId: String,
    ): List<UpcomingEvent> = records.mapNotNull { calendarEvent(kind, it, feedId) }

    internal fun landingUrl(
        kind: FeedKind,
        symbol: String,
        headline: String,
        whenText: String,
    ): String = "$LANDING?nfid=" + filingId(kind, symbol, headline, whenText)

    /** Stable identity from the filing's salient fields. */
    internal fun filingId(
        kind: FeedKind,
        symbol: String,
        headline: String,
        whenText: String,
    ): String {
        val raw = listOf(kind.name, symbol, headline, whenText).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return buildString(20) {
            for (i in 0 until 10) append(String.format(Locale.US, "%02x", digest[i]))
        }
    }

    /**
     * First non-blank value across a chain of candidate keys.
     *
     * NSE also uses a literal "-" for "no value", which has to be treated as absent or it
     * ends up rendered as a headline.
     */
    internal fun first(record: Map<String, String?>, vararg keys: String): String {
        for (key in keys) {
            val value = record[key]?.trim()
            if (!value.isNullOrEmpty() && value != "-") return value
        }
        return ""
    }
}
