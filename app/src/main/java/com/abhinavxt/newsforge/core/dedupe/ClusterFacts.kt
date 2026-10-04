package com.abhinavxt.newsforge.core.dedupe

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier

/**
 * What a cluster is, read across all of its members rather than off the one shown.
 *
 * The feed displays one article per story — the anchor, which is simply whichever member
 * arrived first — and until now it also *scored* that one. That is wrong in a way that
 * costs exactly the stories worth having: when a wire rewrite lands two minutes before
 * the filing it was written from, the anchor is the rewrite, and the story is ranked as
 * aggregator-tier general news for the rest of its life while the exchange filing sits
 * inside the same cluster saying otherwise.
 *
 * So the properties that decide rank are taken from the strongest member. The weights
 * stay in [Category] and [SourceTier] where they already live, which is why this reads a
 * list of names rather than a SQL CASE expression — a second copy of the weights in the
 * query would be a second place to forget.
 */
object ClusterFacts {

    /**
     * @param joined enum names as SQLite's `GROUP_CONCAT` emits them: comma-separated,
     *   no spaces guaranteed, and null when the cluster somehow has no rows.
     * @param fallback the anchor's own value, used when nothing parses. A cluster always
     *   contains its anchor, so this is a guard against a downgraded enum name rather
     *   than an expected path.
     */
    fun strongestTier(joined: String?, fallback: SourceTier): SourceTier =
        names(joined)
            .mapNotNull { name -> SourceTier.entries.firstOrNull { it.name == name } }
            .maxByOrNull { it.weight }
            ?: fallback

    /**
     * Highest-weight category in the cluster.
     *
     * By weight rather than by a hand-written precedence list, so this cannot disagree
     * with the ranker about which of two categories matters more — there is one answer
     * to that question and it is the number in the enum.
     */
    fun strongestCategory(joined: String?, fallback: Category): Category =
        names(joined)
            .mapNotNull { name -> Category.entries.firstOrNull { it.name == name } }
            .maxByOrNull { it.weight }
            ?: fallback

    private fun names(joined: String?): List<String> =
        joined.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
