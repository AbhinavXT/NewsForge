package com.abhinavxt.newsforge.core.reader

import com.abhinavxt.newsforge.core.tag.SymbolSpan

/**
 * Which company names in an article to make tappable.
 *
 * The first mention of each company only. A results story names its subject in nearly
 * every paragraph, and a link on each one turns the body into a field of underlines that
 * is harder to read than none — the first is where a reader meets the name and might
 * want to look it up.
 */
object Mentions {

    /**
     * @param tagged companies the story is already tagged with, from its headline. These
     *   are linked on any match; anything else only on a match that cannot be a word.
     * @param spansOf finds companies in one block's text; the lexicon in the app.
     * @return per block index, the spans to link in it, in text order.
     */
    fun firstMentions(
        blocks: List<ReaderBlock>,
        tagged: Set<String> = emptySet(),
        spansOf: (String) -> List<SymbolSpan>,
    ): Map<Int, List<SymbolSpan>> {
        val seen = HashSet<String>()
        val result = LinkedHashMap<Int, List<SymbolSpan>>()
        blocks.forEachIndexed { index, block ->
            if (block is ReaderBlock.Image || block is ReaderBlock.Heading) return@forEachIndexed
            val fresh = spansOf(block.text)
                .filter { it.symbol in tagged || unambiguous(block.text, it) }
                .filter { seen.add(it.symbol) }
            if (fresh.isNotEmpty()) result[index] = fresh
        }
        return result
    }

    /**
     * Whether a match is a company and not a word that happens to be one's name.
     *
     * Article bodies are full prose, which a headline is not: "the dollar", "global cues"
     * and "Titan" the moon all match company names. A name of several words, or a ticker
     * in capitals, is not something prose produces by accident; a single capitalised word
     * at the start of a sentence is.
     */
    internal fun unambiguous(text: String, span: SymbolSpan): Boolean {
        val matched = text.substring(span.start, span.end)
        if (matched.any { it.isWhitespace() }) return true
        return matched.length >= 2 && matched.all { it.isUpperCase() || it.isDigit() || it == '&' }
    }

    /** Every company the article names, in order of first mention. */
    fun symbols(mentions: Map<Int, List<SymbolSpan>>): List<String> =
        mentions.values.flatten().map { it.symbol }.distinct()
}
