package com.abhinavxt.newsforge.core.dedupe

import java.util.Locale

/**
 * Reduces a headline to a comparable set of content words.
 *
 * An earlier version of this used SimHash. It does not work here, and the reason is worth
 * recording: SimHash needs document-length text for its per-bit sign decisions to be
 * stable. On an eight-token headline a single word swap flips a large share of the bits,
 * and measurement bore that out — "Tata Steel *bags* … order" against "Tata Steel *wins*
 * … order" scored a Hamming distance of 9, while "*JSW* Steel bags … order" scored 11. A
 * same-story pair and a different-company pair, three bits apart. No threshold separates
 * those, so the whole approach was discarded rather than tuned.
 *
 * Direct set comparison is both more accurate and affordable at this scale: the candidate
 * window is a few hundred rows, each a handful of tokens.
 */
object Headline {

    /**
     * Function words plus the filler that appears in a large share of Indian market
     * headlines. "Rs" in particular is in nearly every value-bearing headline and carries
     * no information about *which* story this is.
     */
    private val STOPWORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "by",
        "for", "with", "from", "as", "is", "are", "was", "were", "be", "been", "being",
        "it", "its", "this", "that", "these", "those", "will", "would", "can", "could",
        "may", "might", "has", "have", "had", "not", "no", "up", "down", "out", "over",
        "after", "before", "into", "than", "then", "amid", "say", "said", "new", "latest",
        "news", "update", "live", "today", "check", "here", "what", "why", "how", "who",
        "all", "you", "your", "more", "about", "per", "cent", "rs", "inr", "vs", "via",
    )

    private val TOKEN = Regex("[a-z0-9]+")

    /**
     * Lowercases, removes digit separators so "5,000" and "5000" agree, strips
     * possessives, drops stopwords, and folds simple plurals.
     *
     * Plural folding matters more than it looks: outlets write "Railways" and "Railway",
     * "results" and "result", and without folding those count as disagreements on exactly
     * the words two reports of the same event are most likely to share.
     */
    fun tokenize(text: String): List<String> {
        val flattened = text.lowercase(Locale.US)
            .replace(Regex("(?<=\\d)[,.](?=\\d)"), "")
            .replace('\u2019', '\'')
            .replace("'s", "")
        return TOKEN.findAll(flattened)
            .map { fold(it.value) }
            .filter { it.length >= 2 && it !in STOPWORDS }
            .toList()
    }

    fun tokenSet(text: String): Set<String> = tokenize(text).toSet()

    private fun fold(token: String): String {
        if (token.length < 4 || !token.endsWith("s")) return token
        if (token.endsWith("ss") || token.endsWith("us") || token.endsWith("is")) return token
        return token.dropLast(1)
    }
}

/** Set-overlap measures used to decide whether two headlines describe one event. */
object Similarity {

    /** |A ∩ B| / |A ∪ B|. Symmetric, and punishes one side carrying extra detail. */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = intersectionSize(a, b)
        return shared.toDouble() / (a.size + b.size - shared)
    }

    /**
     * |A ∩ B| / min(|A|, |B|).
     *
     * Forgiving of the common case where one outlet writes a fuller headline than
     * another. Used alongside [jaccard] rather than instead of it, because on its own it
     * will happily swallow a short headline into an unrelated long one.
     */
    fun containment(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return intersectionSize(a, b).toDouble() / minOf(a.size, b.size)
    }

    private fun intersectionSize(a: Set<String>, b: Set<String>): Int {
        val (small, large) = if (a.size <= b.size) a to b else b to a
        var count = 0
        for (token in small) if (token in large) count++
        return count
    }
}
