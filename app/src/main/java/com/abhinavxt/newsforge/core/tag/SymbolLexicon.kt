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
)

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
    fun match(text: String): List<String> {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        var i = 0
        while (i < tokens.size) {
            var matchedLength = 0
            var matchedSymbol: String? = null
            val maxWindow = minOf(maxPhraseTokens, tokens.size - i)
            // Longest match first, so "bajaj finance" never resolves as "bajaj".
            for (length in maxWindow downTo 1) {
                val key = tokens.subList(i, i + length).joinToString(" ")
                val symbol = phrases[key]
                if (symbol != null) {
                    matchedLength = length
                    matchedSymbol = symbol
                    break
                }
            }
            if (matchedSymbol != null) {
                found.add(matchedSymbol)
                i += matchedLength
            } else {
                i++
            }
        }
        return found.toList()
    }

    companion object {
        /**
         * Words that are real tickers somewhere but are far more often ordinary English
         * or market jargon in a headline.
         */
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
        )

        private val TOKEN = Regex("[a-z0-9&]+")

        internal fun tokenize(text: String): List<String> =
            TOKEN.findAll(text.lowercase(Locale.US))
                .map { it.value }
                .filter { it !in NOISE }
                .toList()

        /**
         * Corporate-form suffixes, stripped so "Infosys Ltd" and "Infosys" are one phrase.
         */
        private val NOISE = setOf(
            "ltd", "limited", "inc", "corp", "corporation", "co", "company", "the",
            "&", "and", "of", "plc", "pvt", "private",
        )
    }
}
