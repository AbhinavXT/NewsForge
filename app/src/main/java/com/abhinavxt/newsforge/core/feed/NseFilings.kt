package com.abhinavxt.newsforge.core.feed

import com.abhinavxt.newsforge.core.calendar.EventType
import com.abhinavxt.newsforge.core.calendar.UpcomingEvent
import com.abhinavxt.newsforge.core.model.Category
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
     * Routine paperwork, matched against the filing's subject only.
     *
     * Deliberately not matched against the attachment text: that is free prose and
     * mentions all sorts of things in passing, so a results filing whose covering note
     * says the numbers will also appear in the newspapers would be thrown away by an
     * exclusion list that read it.
     */
    private val EXCLUDED_SUBJECTS = listOf(
        "newspaper", "publication", "loss of share", "duplicate share",
        "trading window", "esop", "employee stock option", "investor complaint",
        "reg. 74", "regulation 74", "regulation 40", "shareholding pattern",
        "certificate under", "compliance certificate", "share transfer",
    )

    /**
     * NSE's own subject vocabulary, mapped to this app's taxonomy.
     *
     * Announcements do not arrive as free text. The `desc` field is drawn from a
     * controlled list the filer picks from — "Award of Order(s) / Receipt of Order(s)",
     * "Acquisition", "Credit Rating", "Fund Raising" — which makes it a far better
     * classifier than guessing from prose, and makes classification and curation the same
     * decision rather than two.
     *
     * Order is significance, not preference: the first rule that matches wins, so the
     * regulatory phrasings sit above the order-win ones. Both contain the word "order"
     * and they mean opposite things — a company receiving an order from a customer is
     * good news, and receiving one from a regulator is the other kind.
     *
     * A null category means "material, but let the text rules decide". Operational
     * filings are the reason it exists: a plant fire and a capacity commissioning both
     * move a stock and neither has a bucket in [Category] that is not a lie.
     */
    private val SUBJECT_RULES: List<Pair<List<String>, Category?>> = listOf(
        listOf(
            "orders passed", "order passed", "action(s) taken", "action taken",
            "show cause", "penalty", "penalties", "adjudicat", "search and seizure",
            "summons", "prosecution", "insolvency", "nclt", "resolution professional",
            "sebi order", "attachment of",
        ) to Category.REGULATORY,

        listOf(
            "financial result", "quarterly result", "audited result",
            "unaudited financial", "results for the quarter", "earnings release",
        ) to Category.RESULTS,

        listOf("dividend") to Category.DIVIDEND,

        listOf(
            "fund rais", "fundrais", "qip", "qualified institution", "rights issue",
            "preferential", "buyback", "buy-back", "bonus issue", "bonus share",
            "stock split", "sub-division", "subdivision", "debenture", "ncd ",
            "convertible warrant", "commercial paper",
        ) to Category.FUNDRAISE,

        listOf(
            "acquisition", "acquire", "merger", "amalgamation", "demerger",
            "scheme of arrangement", "open offer", "stake sale", "slump sale",
            "divestment", "divestiture", "joint venture",
        ) to Category.MERGER,

        listOf(
            "award of order", "receipt of order", "order(s)", "work order",
            "purchase order", "letter of intent", "letter of award", "contract",
            "bags order", "wins order", "new order",
        ) to Category.ORDER_WIN,

        // Named agencies rather than the bare word: "rating" is a substring of
        // "operating", and "Update on operating performance" is not a rating action.
        listOf(
            "credit rating", "rating action", "rating upgrade", "rating downgrade",
            "revision in rating", "crisil", "icra", "care ratings", "india ratings",
        ) to Category.RATING,

        listOf(
            "encumbrance", "pledge", "regulation 29", "sast", "promoter group",
        ) to Category.BLOCK_DEAL,

        listOf(
            "change in director", "change in kmp", "resignation", "cessation",
            "appointment of", "auditor", "managing director", "chief executive",
            "chief financial officer",
        ) to Category.MANAGEMENT,

        // Material, uncategorised. Scheduled contact with the market, and anything that
        // happens to the business itself rather than to its paperwork.
        listOf(
            "conference call", "earnings call", "con. call", "concall", "analyst",
            "investor meet", "investors meet", "institutional investor",
            "investor presentation",
            "disruption", "fire", "shutdown", "lock-out", "lockout", "strike",
            "force majeure", "capacity expansion", "commercial production",
            "commissioning", "clarification",
            // A date fixed for a benefit, without the filing saying which benefit. The
            // rules above catch it when it does say, so reaching here means it did not.
            "record date", "ex-date",
            // Last, and only once everything specific has had its turn: on its own this
            // says a board met, not what it decided.
            "outcome of board meeting",
        ) to null,
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

    /**
     * @return true when the filing is worth a reader's attention at all.
     *
     * Inclusion-based, still: a filing must match a subject to survive. Filtering by
     * exclusion would mean chasing every new species of paperwork forever, whereas
     * requiring a positive match means new noise is ignored by default and the cost of
     * missing a new subject is one entry in the table above.
     */
    fun isMaterial(headline: String, detail: String): Boolean =
        classify(headline, detail) != null

    /**
     * The category NSE's own subject implies, or null when it implies none.
     *
     * Null is not a rejection — [isMaterial] is the question of whether to keep the
     * filing — it means the text rules should have the final say.
     */
    fun categoryOf(headline: String, detail: String): Category? =
        classify(headline, detail)?.category

    /** Null when the filing is excluded or matched nothing. */
    private fun classify(headline: String, detail: String): Match? {
        val subject = headline.lowercase(Locale.US)
        if (EXCLUDED_SUBJECTS.any { it in subject }) return null
        val text = "$headline $detail".lowercase(Locale.US)
        for ((phrases, category) in SUBJECT_RULES) {
            if (phrases.any { it in text }) return Match(category)
        }
        return null
    }

    /**
     * Wraps a nullable category so "no rule matched" and "matched a rule that assigns no
     * category" stay distinguishable. Collapsing them would make every operational
     * filing immaterial.
     */
    private data class Match(val category: Category?)

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
        val match = classify(headline, detail) ?: return null

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
            // The exchange's own subject beats anything the text rules could infer from
            // "TATASTEEL — Acquisition", which is a headline with one usable word in it.
            categoryHint = match.category,
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
