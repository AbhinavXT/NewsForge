package com.abhinavxt.newsforge.core.search

/**
 * Builds an FTS `MATCH` expression from whatever was typed into the search box.
 *
 * The input cannot be passed through. FTS has its own query language — `-` excludes, a
 * quote opens a phrase, `OR` and `NEAR` are operators, `*` is a prefix — and a user
 * searching for `"M&M"` or `Bajaj-Auto` or `L&T` would otherwise get either a syntax
 * error or a query meaning something quite different from what they typed. So the input
 * is reduced to words and rebuilt as the one query shape that is always well-formed: a
 * list of prefix terms, all of which must match.
 *
 * All must match rather than any, because the second word of a search is almost always
 * a narrowing — "ireda order", "rbi rate" — and an any-word match would answer "rbi rate"
 * with every story that ever mentioned a rate.
 */
object SearchQuery {

    /**
     * Shortest term kept.
     *
     * A single-letter prefix like `a*` matches most of the index and turns a search into
     * a scan with extra steps. Two letters still admits "5g", "ai" and "it".
     */
    const val MIN_TERM_LENGTH = 2

    /** More words than this is a sentence, not a search, and each one costs a lookup. */
    const val MAX_TERMS = 8

    /** @return the match expression, or null when nothing searchable was typed. */
    fun toMatch(text: String): String? {
        // Grouped numbers first, and taken out of the text before the words are read, or
        // "1,200" would also be read as the words "1" and "200".
        val phrases = GROUPED_NUMBER.findAll(text).map { it.value }.toList()
        val words = terms(GROUPED_NUMBER.replace(text, " "))

        val parts = phrases.map { number ->
            // The index splits "1,200" at the comma into two tokens, so the token "1200"
            // does not exist and a search for it finds nothing. A phrase of the groups
            // matches the two tokens side by side, which is exactly what was written.
            // Digits and spaces only, so the quotes cannot be unbalanced.
            "\"" + number.split(',').joinToString(" ") + "\""
        } + words.map { word ->
            // Prefix on every word, so a search updates usefully while it is still being
            // typed: "irfc" finds IRFC before the reader has finished the word.
            "$word*"
        }
        return parts.take(MAX_TERMS).takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * The words in [text], folded the way the index folds them.
     *
     * Kept: letters in any script, combining marks, and digits. The marks matter more
     * than they look — Devanagari vowel signs are marks, not letters, and splitting on
     * them breaks "रिलायंस" into single characters that are then thrown away, which made
     * every Hindi headline unsearchable.
     *
     * Only A–Z is lower-cased, because that is all SQLite's tokenizer lower-cases. Folding
     * more than the index does — "É" to "é" — produces terms the index never contains.
     * The same folding is what neutralises the operators: FTS only treats `AND`, `OR`,
     * `NOT` and `NEAR` as operators in upper case, so "rate OR cut" becomes three plain
     * terms rather than a disjunction nobody asked for.
     */
    fun terms(text: String): List<String> =
        foldAscii(text)
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.length >= MIN_TERM_LENGTH }
            .distinct()
            .take(MAX_TERMS)

    private fun foldAscii(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in 'A'..'Z') c + ('a' - 'A') else c)
    }

    /** Indian and Western grouping alike: "1,00,000" and "1,000,000". */
    private val GROUPED_NUMBER = Regex("""\d{1,3}(?:,\d{2,3})+""")
}
