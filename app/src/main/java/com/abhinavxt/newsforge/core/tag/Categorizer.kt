package com.abhinavxt.newsforge.core.tag

import com.abhinavxt.newsforge.core.model.Category
import java.util.Locale

/**
 * Assigns a [Category] from the headline text using an ordered rule list.
 *
 * Rules rather than a model: this runs on every item at fetch time, has to work offline
 * and instantly, and — more importantly — has to be *correctable*. When something lands
 * in the wrong bucket the fix is one line here, visible and reviewable, rather than a
 * retrained classifier nobody can audit.
 *
 * First match wins, so [RULES] is ordered by specificity. The main consequence is that a
 * headline mentioning two events gets the more decisive one, which is the behaviour you
 * want: "SEBI probes company that won order" is a regulatory story, not an order win.
 */
object Categorizer {

    /** A named rule, exposed so tests and a future settings screen can enumerate them. */
    data class Rule(val category: Category, val pattern: Regex)

    private fun rule(category: Category, pattern: String) =
        Rule(category, Regex(pattern, RegexOption.IGNORE_CASE))

    val RULES: List<Rule> = listOf(
        rule(
            Category.REGULATORY,
            "\\bsebi\\b|enforcement directorate|\\bcbi\\b|income tax (raid|search|survey)|" +
                "\\braid(s|ed)?\\b|\\bprobe(s|d)?\\b|investigat|show cause|forensic audit|" +
                "auditor (resign|quit)|\\bfraud\\b|penalt|debarr|\\bbarred\\b|money laundering|" +
                "\\bpmla\\b|\\bnclt\\b|insolvenc|lookout (notice|circular)|\\bsummon"
        ),
        rule(
            Category.MERGER,
            "acquisition|acquires?|acquir(ing|ed)|\\bmerger\\b|merge[sd]? with|takeover|" +
                "open offer|stake sale|divest|demerger|demerge|hive[- ]off|slump sale|" +
                "controlling stake|majority stake"
        ),
        rule(
            Category.RESULTS,
            "\\bq[1-4]\\b|quarterly result|quarter result|net profit|\\bpat\\b|\\bebitda\\b|" +
                "\\brevenue\\b|earnings|topline|bottom ?line|results (beat|miss)|" +
                "profit (rose|fell|jump|surge|slump|decline)|guidance|" +
                // The exchange's own wording, which none of the media phrasings above
                // cover: a filing reads "Financial Results for the quarter ended", never
                // "quarterly results".
                "financial result|audited result|results for the quarter|" +
                "outcome of board meeting"
        ),
        rule(
            Category.DIVIDEND,
            "\\bdividend\\b|record date|\\bex-date\\b|ex date"
        ),
        rule(
            Category.ORDER_WIN,
            // The verb and the noun are almost never adjacent — "bags Rs 500 crore order"
            // — so the gap has to be allowed for rather than matched literally.
            "(bags?|wins?|secures?|receives?|awarded|clinch(es|ed)?)\\b.{0,40}?" +
                "\\b(order|contract|tender|\\bbid\\b)|" +
                "order worth|contract worth|letter of intent|\\bloi\\b|order book|" +
                "emerges? l1|\\bl1 bidder\\b|work order"
        ),
        rule(
            Category.BLOCK_DEAL,
            "block deal|bulk deal|promoter (pledge|stake|sells|buys)|offloads?|" +
                "picks up (a )?stake|open market (sale|purchase)|\\bofs\\b"
        ),
        rule(
            Category.FUNDRAISE,
            "\\bqip\\b|rights issue|preferential (allotment|issue)|fund ?rais|buyback|" +
                "bonus issue|stock split|\\bipo\\b|listing (gain|debut)|anchor book|" +
                "\\bncd\\b|raises? (rs|\\$|₹)"
        ),
        rule(
            Category.RATING,
            "\\bcrisil\\b|\\bicra\\b|care ratings|india ratings|moody|s&p global|\\bfitch\\b|" +
                "rating (upgrade|downgrade|action)|(upgrade|downgrade)[sd]? .{0,20}rating|" +
                "outlook to (stable|negative|positive)"
        ),
        rule(
            Category.MANAGEMENT,
            "appoint(s|ed|ment)|steps down|resign(s|ed|ation)|new (ceo|cfo|md|chairman)|" +
                "(ceo|cfo|md|chairman) (exit|quits|to retire)|elevated to|" +
                "board (approves|clears) appointment"
        ),
        rule(
            Category.MACRO,
            "\\brbi\\b|repo rate|monetary policy|\\bmpc\\b|inflation|\\bcpi\\b|\\bwpi\\b|" +
                "\\bgdp\\b|\\biip\\b|\\bfii\\b|\\bdii\\b|\\brupee\\b|bond yield|" +
                "forex reserves|liquidity|fiscal deficit"
        ),
        rule(
            Category.POLICY,
            "cabinet (approves|clears)|union budget|gst council|\\bgst\\b|\\bpli\\b|" +
                "\\bministry\\b|government (approves|clears|plans|announces)|" +
                "import duty|export (ban|duty|curb)|subsid|niti aayog|parliament|" +
                "notification|\\bpolicy\\b|scheme worth|\\bcabinet\\b"
        ),
        rule(
            Category.GEOPOLITICS,
            "\\bwar\\b|missile|air ?strike|sanction|ceasefire|\\bconflict\\b|houthi|" +
                "red sea|invasion|\\btroops\\b|\\bnato\\b|border (clash|tension)"
        ),
        rule(
            Category.COMMODITY,
            "crude|\\bbrent\\b|\\bwti\\b|\\bopec\\b|gold price|silver price|copper|" +
                "aluminium|steel price|natural gas price|commodity price"
        ),
        rule(
            Category.GLOBAL,
            "\\bfed\\b|\\bfomc\\b|federal reserve|wall street|nasdaq|dow jones|\\becb\\b|" +
                "bank of japan|treasury yield|asian markets|us markets|gift nifty|" +
                "\\bsgx\\b|\\bchina\\b"
        ),
    )

    /**
     * @param hint the feed's own category, applied only when no rule matches. A PIB feed
     *   is policy news even when the headline itself reads blandly.
     */
    fun categorize(title: String, summary: String? = null, hint: Category? = null): Category {
        val text = buildText(title, summary)
        for (rule in RULES) {
            if (rule.pattern.containsMatchIn(text)) return rule.category
        }
        return hint ?: Category.OTHER
    }

    /**
     * The summary is truncated because a full article body drags in boilerplate — "also
     * read", disclaimers, unrelated trending headlines — that fires rules the actual
     * story never justified.
     */
    private fun buildText(title: String, summary: String?): String {
        val head = summary?.take(SUMMARY_CHARS).orEmpty()
        return (title + " " + head).lowercase(Locale.US)
    }

    private const val SUMMARY_CHARS = 240
}
