package com.abhinavxt.newsforge.core.dedupe

/** The minimum an article needs to expose to be clustered. */
data class ClusterInput(
    val id: String,
    val canonicalUrl: String,
    val tokens: Set<String>,
    val symbols: Set<String>,
    val publishedAtMillis: Long,
)

/** An already-clustered article, as held in the database. */
data class ClusterMember(
    val clusterId: String,
    val canonicalUrl: String,
    val tokens: Set<String>,
    val symbols: Set<String>,
    val publishedAtMillis: Long,
)

/** A group of articles judged to be the same story. */
data class Cluster(
    val id: String,
    val memberIds: List<String>,
)

/**
 * Groups near-identical headlines so one story occupies one row.
 *
 * Three conditions, and all of them must hold:
 *
 * 1. the two items are within [DEFAULT_WINDOW_MS] of each other;
 * 2. their tagged symbols do not *contradict* — this is what keeps "Tata Steel bags Rs
 *    2,000 crore order" apart from "JSW Steel bags Rs 2,000 crore order", which no text
 *    similarity measure can do reliably, since those headlines differ by one word;
 * 3. their token sets overlap enough on both measures.
 *
 * [assign] is the incremental entry point the repository calls per fetched item.
 * [cluster] is the batch form built on the same rule.
 */
object Clusterer {

    const val DEFAULT_WINDOW_MS: Long = 6 * 60 * 60 * 1000L

    /** Overlap over the shorter headline. Tolerates one outlet writing more detail. */
    const val MIN_CONTAINMENT: Double = 0.65

    /** Overall agreement. Stops a short headline being swallowed by a long one. */
    const val MIN_JACCARD: Double = 0.45

    /**
     * Below this many content words, only an identical token set counts.
     *
     * "Sensex falls sharply" and "Nifty falls sharply" are two thirds identical and are
     * not the same story. Short headlines are exactly where fuzzy matching goes wrong.
     */
    const val MIN_TOKENS_FOR_FUZZY: Int = 5

    /**
     * @return the cluster the item should join, or null if it starts its own.
     */
    fun assign(
        item: ClusterInput,
        existing: List<ClusterMember>,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): String? {
        var bestCluster: String? = null
        var bestScore = 0.0

        for (candidate in existing) {
            if (kotlin.math.abs(candidate.publishedAtMillis - item.publishedAtMillis) > windowMs) {
                continue
            }
            // Same canonical URL is the same article, full stop — a publisher rewriting
            // its own headline must not fork the cluster.
            if (candidate.canonicalUrl == item.canonicalUrl) return candidate.clusterId

            if (symbolsConflict(item.symbols, candidate.symbols)) continue

            val score = overlapScore(item.tokens, candidate.tokens)
            if (score > bestScore) {
                bestScore = score
                bestCluster = candidate.clusterId
            }
        }
        return bestCluster
    }

    fun cluster(
        items: List<ClusterInput>,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): List<Cluster> {
        val ordered = items.sortedBy { it.publishedAtMillis }
        val seen = ArrayList<ClusterMember>(ordered.size)
        val members = LinkedHashMap<String, MutableList<String>>()

        for (item in ordered) {
            val clusterId = assign(item, seen, windowMs) ?: item.id
            members.getOrPut(clusterId) { ArrayList() }.add(item.id)
            seen.add(
                ClusterMember(
                    clusterId = clusterId,
                    canonicalUrl = item.canonicalUrl,
                    tokens = item.tokens,
                    symbols = item.symbols,
                    publishedAtMillis = item.publishedAtMillis,
                )
            )
        }
        return members.map { (id, ids) -> Cluster(id, ids) }
    }

    /**
     * Two items conflict when both name companies and they name different ones.
     *
     * An untagged item never conflicts: absence of a symbol means the lexicon did not
     * recognise the company, not that the story is about nothing.
     */
    internal fun symbolsConflict(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a.none { it in b }
    }

    /** @return 0.0 when the pair fails either threshold, otherwise the containment. */
    private fun overlapScore(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (minOf(a.size, b.size) < MIN_TOKENS_FOR_FUZZY) {
            return if (a == b) 1.0 else 0.0
        }
        val containment = Similarity.containment(a, b)
        if (containment < MIN_CONTAINMENT) return 0.0
        if (Similarity.jaccard(a, b) < MIN_JACCARD) return 0.0
        return containment
    }
}
