package com.abhinavxt.newsforge.core.world

import java.util.Locale

/**
 * Followed words and phrases on the World tab — "ISRO", "Kohli", "Bengaluru metro".
 *
 * Matched as whole words, case-insensitively, against the headline and summary. Whole
 * words because a substring match makes short keywords useless: following "AI" by
 * substring matches "said", "again" and "Mumbai", which is every other story.
 */
object Keywords {

    /** Longest keyword accepted; past this it is a sentence, not something to follow. */
    const val MAX_LENGTH = 40

    /** Most keywords one install follows, to keep matching cheap and the section short. */
    const val MAX_COUNT = 30

    /**
     * Trims and collapses whitespace, or returns null for something not worth storing.
     *
     * Case is kept for display — "ISRO" should read as typed — and ignored when matching.
     */
    fun normalize(raw: String): String? {
        val text = raw.trim().replace(WHITESPACE, " ")
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        // Needs at least one letter or digit: "!!" would compile to a pattern that
        // matches nothing useful and would sit in the list confusing everyone.
        if (text.none { it.isLetterOrDigit() }) return null
        return text
    }

    /** The first of [keywords] that [title] or [summary] mentions, or null. */
    fun firstMatch(keywords: List<String>, title: String, summary: String?): String? {
        if (keywords.isEmpty()) return null
        val text = " " + listOfNotNull(title, summary).joinToString(" ").lowercase(Locale.ROOT) + " "
        return keywords.firstOrNull { keyword -> patternFor(keyword).containsMatchIn(text) }
    }

    /**
     * Whole-word pattern for one keyword.
     *
     * Boundaries are "not a letter or digit" rather than `\b`, so a keyword that starts or
     * ends with punctuation — "C++", "U.S." — still has a boundary to sit against.
     */
    private fun patternFor(keyword: String): Regex =
        cache.getOrPut(keyword) {
            val body = keyword.lowercase(Locale.ROOT)
                .split(' ')
                .joinToString("\\s+") { Regex.escape(it) }
            Regex("(?<![\\p{L}\\p{N}])$body(?![\\p{L}\\p{N}])")
        }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private val WHITESPACE = Regex("\\s+")
}
