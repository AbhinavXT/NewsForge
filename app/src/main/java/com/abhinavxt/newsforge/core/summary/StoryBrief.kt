package com.abhinavxt.newsforge.core.summary

import com.abhinavxt.newsforge.core.dedupe.Headline
import com.abhinavxt.newsforge.core.dedupe.Similarity

/** One outlet's version of a story, as stored. */
data class OutletText(
    val sourceName: String,
    val title: String,
    val summary: String?,
)

/**
 * What a story's outlets agree on, in a few sentences.
 *
 * @param points sentences taken verbatim from the outlets' own summaries.
 * @param outlets distinct outlets the points were drawn from.
 */
data class StoryBrief(val points: List<String>, val outlets: Int)

/**
 * Summarises a story across every outlet that carried it.
 *
 * Extractive, not generated. Every point is a sentence one of the outlets actually wrote,
 * chosen because the other outlets' versions say the same thing — so the summary can be
 * wrong only in what it leaves out, never in what it claims. A generated summary would
 * read more smoothly and would need a model, a key and a network round trip per story,
 * and its errors would be invented facts under a news app's name.
 *
 * The scoring is centrality: a sentence earns a point for each *other* outlet whose text
 * shares its words. The detail every outlet reported rises; the one outlet's colour or
 * boilerplate sinks. Picks are then thinned so no two points repeat each other.
 */
object StoryBriefs {

    const val MAX_POINTS = 3

    /** Below this a sentence is a fragment or a dateline, not a point. */
    private const val MIN_CHARS = 40

    /** Above this it is a paragraph the feed failed to split, and unreadable as a bullet. */
    private const val MAX_CHARS = 280

    /**
     * Whether two points say the same thing.
     *
     * Two measures, because outlets paraphrase: "ISRO launched the mission on Tuesday" and
     * "India's space agency launched a mission on Tuesday" share half of the shorter one's
     * words but only a quarter of both together, so Jaccard alone lets the pair through.
     * Containment catches the paraphrase; the Jaccard floor stops a short point that
     * happens to share three common words with a long one being swallowed by it.
     */
    private fun repeats(a: Set<String>, b: Set<String>): Boolean =
        Similarity.jaccard(a, b) > 0.45 ||
            (Similarity.containment(a, b) >= 0.5 && Similarity.jaccard(a, b) >= 0.25)

    /**
     * @param anchorTitle the headline the reader already sees, which no point may repeat.
     * @return null when the outlets give too little to say anything the headline does not.
     */
    fun summarize(anchorTitle: String, coverage: List<OutletText>): StoryBrief? {
        // One text per outlet. A publisher arriving under two feeds is one voice, and
        // counting it twice would let it vote for its own sentences.
        val outlets = coverage
            .groupBy { it.sourceName.trim().lowercase() }
            .map { (_, versions) -> versions.first() }
        val titleTokens = Headline.tokenSet(anchorTitle)

        val candidates = outlets.flatMap { outlet ->
            sentences(outlet.summary).map { Candidate(it, outlet.sourceName, Headline.tokenSet(it)) }
        }.filter { candidate ->
            candidate.tokens.size >= 4 &&
                // A summary that only restates a headline adds nothing under it.
                Similarity.jaccard(candidate.tokens, titleTokens) < 0.7 &&
                outlets.none { Similarity.jaccard(candidate.tokens, Headline.tokenSet(it.title)) >= 0.7 }
        }
        if (candidates.isEmpty()) return null

        val outletTokens = outlets.map { outlet ->
            outlet.sourceName to Headline.tokenSet(outlet.title + " " + outlet.summary.orEmpty())
        }
        val scored = candidates.map { candidate ->
            val support = outletTokens
                .filter { (name, _) -> name != candidate.source }
                .sumOf { (_, tokens) -> supported(candidate.tokens, tokens) }
            // A little weight on the headline's words keeps the points on the story the
            // headline names, when a summary wanders into background.
            val onTopic = supported(candidate.tokens, titleTokens) * 0.5
            candidate to support + onTopic
        }.sortedByDescending { it.second }

        val picked = ArrayList<Candidate>(MAX_POINTS)
        for ((candidate, _) in scored) {
            if (picked.size == MAX_POINTS) break
            if (picked.any { repeats(it.tokens, candidate.tokens) }) continue
            picked += candidate
        }
        return StoryBrief(
            points = picked.map { it.text },
            outlets = picked.mapTo(HashSet()) { it.source }.size,
        )
    }

    /** Splits a summary into sentences, dropping the debris feeds put in them. */
    internal fun sentences(summary: String?): List<String> {
        if (summary.isNullOrBlank()) return emptyList()
        return SENTENCE_BREAK.split(summary.replace(WHITESPACE, " ").trim())
            .map { it.trim().trimEnd('…').trim() }
            .filter { it.length in MIN_CHARS..MAX_CHARS }
            .filterNot { sentence -> BOILERPLATE.any { sentence.contains(it, ignoreCase = true) } }
            // A sentence cut off by the feed's own truncation ends mid-word, not in a stop.
            .filter { it.last() in ".!?\"'”’)" }
    }

    /**
     * Share of [sentence]'s words that [other] also uses.
     *
     * One-sided on purpose: an outlet that wrote four paragraphs supports a sentence as
     * fully as one that wrote one line, and Jaccard would punish it for the length.
     */
    private fun supported(sentence: Set<String>, other: Set<String>): Double {
        if (sentence.isEmpty()) return 0.0
        return sentence.count { it in other }.toDouble() / sentence.size
    }

    private data class Candidate(val text: String, val source: String, val tokens: Set<String>)

    private val WHITESPACE = Regex("\\s+")

    /** After terminal punctuation, before something that starts a sentence. */
    private val SENTENCE_BREAK = Regex("(?<=[.!?][\"'”’)]?)\\s+(?=[\"'“‘(]?[A-Z0-9])")

    private val BOILERPLATE = listOf(
        "read more", "click here", "also read", "subscribe", "sign up", "follow us",
        "the post ", "appeared first on", "continue reading", "all rights reserved",
    )
}
