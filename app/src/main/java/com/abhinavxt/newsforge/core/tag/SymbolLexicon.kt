package com.abhinavxt.newsforge.core.tag

import java.util.Locale

/**
 * One tradable name and every way a newsroom might refer to it.
 *
 * [aliases] should carry the short forms ("RIL"), the legal name without the suffix
 * ("Reliance Industries"), and the colloquial one ("Reliance"), because headlines use all
 * three interchangeably.
 */
data class SymbolEntry(
    val symbol: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    /** [com.abhinavxt.newsforge.core.tag.Sector] name, or null when unclassified. */
    val sector: String? = null,
    /**
     * True for entries imported in bulk rather than written by hand.
     *
     * The distinction earns its keep at exactly one place in the phrase table: a
     * hand-written entry may claim a single common word, a bulk-imported one may not.
     * Two hundred curated names can be checked; two thousand imported ones cannot, and
     * among them sit Trent, Titan, Orient and Zen — every one of which is an ordinary
     * English word that appears in market copy meaning nothing of the sort.
     */
    val generated: Boolean = false,
)

/** One company mention: [start] inclusive, [end] exclusive, as character offsets. */
data class SymbolSpan(val start: Int, val end: Int, val symbol: String)

/**
 * Maps free text to NSE symbols by longest phrase match.
 *
 * A trie or Aho-Corasick would be asymptotically nicer, but the phrase table is a few
 * thousand entries and headlines are twenty tokens, so a windowed hash lookup is both
 * fast enough and far easier to reason about when a match goes wrong.
 */
class SymbolLexicon(entries: List<SymbolEntry>) {

    private val phrases: Map<String, String>
    private val maxPhraseTokens: Int

    init {
        val table = HashMap<String, String>()
        var longest = 1
        for (entry in entries) {
            val candidates = buildList {
                add(entry.symbol)
                add(entry.name)
                addAll(entry.aliases)
            }
            for (candidate in candidates) {
                val tokens = tokenize(candidate)
                if (tokens.isEmpty()) continue
                val key = tokens.joinToString(" ")
                // Single short tokens ("IT", "GO") collide with ordinary English far too
                // often to be worth the coverage they add.
                if (tokens.size == 1 && key.length < 3) continue
                if (tokens.size == 1 && key in AMBIGUOUS) continue
                // A bulk-imported entry has to earn a single-token claim by being an
                // improbable word. "symbiotec" is nobody's prose; "trent" is a river, a
                // surname and a retailer, and only one of those is on the exchange.
                // Length is a crude proxy for improbability and is the only one available
                // without shipping an English dictionary — so it is set where the common
                // false positives fall, and multi-token names are unaffected.
                if (tokens.size == 1 && entry.generated && key.length < MIN_GENERATED_LENGTH) {
                    continue
                }
                // First writer wins, so an earlier (larger, more likely) company keeps a
                // shared alias instead of a later one silently stealing it.
                table.putIfAbsent(key, entry.symbol)
                if (tokens.size > longest) longest = tokens.size
            }
        }
        phrases = table
        maxPhraseTokens = longest
    }

    /**
     * @return matched symbols in order of first appearance, without duplicates.
     */
    fun match(text: String): List<String> = spans(text).map { it.symbol }.distinct()

    /**
     * Every mention, with where it sits in [text].
     *
     * The same longest-phrase walk as [match] — which is built on this — so a company the
     * reader can tap in an article body is exactly one the app would have tagged.
     */
    fun spans(text: String): List<SymbolSpan> {
        val tokens = tokenizeWithRanges(text)
        if (tokens.isEmpty()) return emptyList()
        val found = ArrayList<SymbolSpan>()
        var i = 0
        while (i < tokens.size) {
            var matchedLength = 0
            var matchedSymbol: String? = null
            val maxWindow = minOf(maxPhraseTokens, tokens.size - i)
            // Longest match first, so "bajaj finance" never resolves as "bajaj".
            for (length in maxWindow downTo 1) {
                val key = tokens.subList(i, i + length).joinToString(" ") { it.word }
                val symbol = phrases[key]
                if (symbol != null) {
                    matchedLength = length
                    matchedSymbol = symbol
                    break
                }
            }
            if (matchedSymbol != null) {
                found += SymbolSpan(
                    start = tokens[i].start,
                    end = tokens[i + matchedLength - 1].end,
                    symbol = matchedSymbol,
                )
                i += matchedLength
            } else {
                i++
            }
        }
        return found
    }

    companion object {
        /**
         * Words that are real tickers somewhere but are far more often ordinary English
         * or market jargon in a headline.
         */
        /** Shortest single token a bulk-imported entry may claim on its own. */
        const val MIN_GENERATED_LENGTH = 6

        private val AMBIGUOUS = setOf(
            "india", "bank", "power", "steel", "gas", "oil", "auto", "motor", "motors",
            "cement", "port", "ports", "tata", "birla", "adani", "reliance", "group",
            "share", "shares", "stock", "stocks", "index", "nifty", "sensex", "market",
            "gold", "coal", "metal", "metals", "one", "life", "force", "century",
            // Exchange and regulator abbreviations appear in a large share of market
            // headlines with nothing to do with the listed entity of the same name.
            "bse", "nse", "sebi", "rbi",
            // Tickers that are also ordinary English.
            "sail", "bob", "idea", "just", "best", "care",
            // Bulk-imported names long enough to pass the length rule that are still
            // everyday market prose: "global markets", "global cues".
            "global", "dollar",
        )

        private val TOKEN = Regex("[A-Za-z0-9&]+")

        /**
         * Lower-cased words, with stopwords dropped and corporate forms collapsed.
         *
         * Collapsed to [CORPORATE_FORM], not dropped. They used to be dropped along with
         * the stopwords, so "Infosys Ltd" and "Infosys" were one phrase — which also made
         * "Trent Limited" the single word "trent", exactly the claim a bulk-imported
         * entry is forbidden to make. A short-named imported company could then not be
         * tagged by any form of its name, including the full legal one that no river,
         * surname or adjective ever appears as. Kept as a marker, the suffix is what it
         * is in a headline: evidence that the word before it is a company.
         *
         * Nothing is lost for everything else. A phrase matches at the start of its
         * window, so "Infosys" still matches in "Infosys Ltd shares" and the marker after
         * it matches nothing and is stepped over.
         */
        internal fun tokenize(text: String): List<String> =
            tokenizeWithRanges(text).map { it.word }

        /**
         * [tokenize], keeping where each word came from.
         *
         * Lower-casing can change a string's length for a handful of characters outside
         * this alphabet, so the offsets are taken from the original text, and only the
         * matched word is lower-cased.
         */
        private fun tokenizeWithRanges(text: String): List<Token> =
            TOKEN.findAll(text)
                .map { match -> Token(match.value.lowercase(Locale.US), match.range) }
                .filter { it.word !in STOPWORDS }
                .map { if (it.word in CORPORATE_FORMS) it.copy(word = CORPORATE_FORM) else it }
                .toList()

        private data class Token(val word: String, val range: IntRange) {
            val start: Int get() = range.first
            val end: Int get() = range.last + 1
        }

        /** Dropped outright: glue words that distinguish nothing. */
        private val STOPWORDS = setOf("the", "&", "and", "of")

        /** Legal suffixes, which do distinguish something — see [tokenize]. */
        private val CORPORATE_FORMS = setOf(
            "ltd", "limited", "inc", "corp", "corporation", "co", "company", "plc",
            "pvt", "private",
        )

        /**
         * What every corporate form becomes.
         *
         * Outside [TOKEN]'s alphabet on purpose, so no word in a headline can ever
         * tokenize to it by accident — it only exists where a suffix stood.
         */
        private const val CORPORATE_FORM = "~corp"
    }
}
