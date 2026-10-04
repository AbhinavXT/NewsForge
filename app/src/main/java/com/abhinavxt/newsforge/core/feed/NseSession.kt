package com.abhinavxt.newsforge.core.feed

/**
 * What NSE demands of a caller, as rules rather than as scattered constants.
 *
 * The exchange's JSON endpoints are not a documented API and are not meant to be polled
 * by anything but the site's own pages. They are gated three ways: cookies picked up from
 * a real page load, a `Referer` that names a page which actually embeds the widget, and a
 * browser-shaped `User-Agent`. Miss any of them and the response is not a clean error —
 * it is an HTML block page served with a 200, which every JSON parser in the world
 * reports as a parse failure and every reader of that report misdiagnoses as a broken
 * URL.
 *
 * All of it lives here, pure, because the interesting part is the classification — *is
 * this a block?* — and that is exactly the part that would otherwise only be exercisable
 * against the live site.
 */
object NseSession {

    const val HOME = "https://www.nseindia.com/"

    const val ANNOUNCEMENTS_PAGE =
        "https://www.nseindia.com/companies-listing/corporate-filings-announcements"

    const val CORP_ACTIONS_PAGE =
        "https://www.nseindia.com/companies-listing/corporate-filings-actions"

    const val BOARD_MEETINGS_PAGE =
        "https://www.nseindia.com/companies-listing/corporate-filings-board-meetings"

    /**
     * Pages fetched, in order, to establish a session.
     *
     * The home page alone is not reliably enough. NSE sets part of the session on the
     * landing page and the rest on the section page that hosts the widget, and a request
     * carrying only the first half is refused the same way as one carrying none. Two
     * round trips once per refresh is a cheap price for the feeds working at all.
     */
    val WARMUP_PAGES: List<String> = listOf(HOME, ANNOUNCEMENTS_PAGE)

    /**
     * The page each endpoint is called from.
     *
     * Sending the home page as the referer for every call is what the app did before, and
     * it works until it does not: the checks tighten from time to time, and naming the
     * page that genuinely embeds the widget costs nothing and is simply true.
     */
    fun refererFor(kind: FeedKind): String = when (kind) {
        FeedKind.NSE_ANNOUNCEMENT -> ANNOUNCEMENTS_PAGE
        FeedKind.NSE_CORP_ACTION -> CORP_ACTIONS_PAGE
        FeedKind.NSE_BOARD_MEETING -> BOARD_MEETINGS_PAGE
        FeedKind.RSS -> HOME
    }

    /**
     * Statuses that mean "your session, not your URL".
     *
     * 429 is included deliberately. It is rate limiting rather than rejection, but the
     * remedy from here is identical — back off, re-establish, try once — and treating it
     * as a hard failure would park a working feed in the health screen's error list.
     */
    fun isRejectionStatus(status: Int): Boolean =
        status == 401 || status == 403 || status == 429

    /**
     * Detects the block page.
     *
     * The endpoints answer with a JSON object or array and nothing else, so anything
     * whose first meaningful byte is neither `{` nor `[` is the gate rather than the
     * data. Checking the shape rather than matching on block-page wording is what makes
     * this survive NSE changing its block page, which it does.
     *
     * An empty body counts: a zero-length 200 is what a rejected request looks like on
     * some of their edges.
     */
    fun isBlocked(bytes: ByteArray): Boolean {
        val head = bytes.take(PROBE_BYTES)
            .toByteArray()
            .toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val first = head.firstOrNull() ?: return true
        return first != '{' && first != '['
    }

    /** What the health screen says when the gate, rather than the URL, is the problem. */
    const val REJECTED_MESSAGE =
        "NSE refused the session — retried once after re-priming"

    /** Status recorded for a rejection, so it is distinguishable from an HTTP code. */
    const val REJECTED_STATUS = -3

    /** Enough to see past a byte-order mark and any leading whitespace. */
    private const val PROBE_BYTES = 64
}
