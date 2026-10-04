package com.abhinavxt.newsforge.core.tag

import java.util.Locale

/**
 * Finds a company from what someone typed into the search box.
 *
 * Deliberately not [SymbolLexicon]. That one reads a headline and asks which companies a
 * newsroom mentioned, so it is built to be cautious — it refuses single short tokens and
 * makes bulk-imported names earn a claim, because tagging a story about a river as a story
 * about Trent is worse than missing it. This asks the opposite question. Somebody has
 * typed "tren" on purpose and wants the list of things it could be, so a near miss is a
 * useful suggestion rather than a false positive.
 *
 * Ranked rather than filtered, because the first row is the one that gets tapped. An exact
 * ticker beats everything: a person typing TCS means TCS and should not have to scroll
 * past three companies whose names happen to contain those letters.
 */
object SymbolSearch {

    /**
     * Below this a query matches most of the exchange.
     *
     * Two characters would put four hundred companies behind a suggestion list nobody can
     * read, and the answer would still be wrong — at that length the ranking is noise.
     */
    const val MIN_QUERY = 2

    private enum class Rank { EXACT_SYMBOL, SYMBOL_PREFIX, NAME_PREFIX, NAME_CONTAINS }

    fun search(
        entries: List<SymbolEntry>,
        query: String,
        limit: Int = 6,
    ): List<SymbolEntry> {
        val text = query.trim().lowercase(Locale.US)
        if (text.length < MIN_QUERY) return emptyList()

        val scored = ArrayList<Pair<Rank, SymbolEntry>>()
        val seen = HashSet<String>()
        for (entry in entries) {
            val symbol = entry.symbol.lowercase(Locale.US)
            val name = entry.name.lowercase(Locale.US)
            val rank = when {
                symbol == text -> Rank.EXACT_SYMBOL
                symbol.startsWith(text) -> Rank.SYMBOL_PREFIX
                name.startsWith(text) -> Rank.NAME_PREFIX
                // Aliases are searched as contains rather than as their own rank: they
                // exist so "RIL" and "Reliance" both find the same company, and a reader
                // does not care which of the three spellings matched.
                name.contains(text) || entry.aliases.any { it.lowercase(Locale.US).contains(text) } ->
                    Rank.NAME_CONTAINS
                else -> continue
            }
            // Seeds are listed before bulk rows, so the first entry for a symbol is the
            // hand-written one where both exist. Keeping it means the better name wins.
            if (!seen.add(symbol)) continue
            scored += rank to entry
        }

        return scored
            .sortedWith(
                compareBy<Pair<Rank, SymbolEntry>> { it.first.ordinal }
                    // Then shortest name: a query matching both "TATA MOTORS" and "TATA
                    // MOTORS DVR" almost always wants the plain one.
                    .thenBy { it.second.name.length }
                    .thenBy { it.second.symbol },
            )
            .take(limit)
            .map { it.second }
    }
}
