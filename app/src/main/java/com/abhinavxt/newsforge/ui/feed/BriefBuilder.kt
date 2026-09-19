package com.abhinavxt.newsforge.ui.feed

import com.abhinavxt.newsforge.core.model.CategoryGroup
import com.abhinavxt.newsforge.data.model.ScoredArticle

/** One block of the brief. [totalCount] is the size before [BriefBuilder] truncated it. */
data class BriefSection(
    val key: String,
    val title: String,
    val stories: List<ScoredArticle>,
    val totalCount: Int,
) {
    val hiddenCount: Int get() = (totalCount - stories.size).coerceAtLeast(0)
}

data class Brief(
    val sinceMillis: Long,
    val totalStories: Int,
    val watchlistCount: Int,
    val sections: List<BriefSection>,
)

/**
 * Turns the ranked overnight list into something readable in one sitting.
 *
 * A flat ranked list is the right shape during the session, when you are scanning for what
 * just happened. It is the wrong shape at 08:30, when the question is "what do I do
 * today" and forty items with no structure is just a wall. Sections answer that by kind,
 * and each is capped so the whole brief fits a couple of screens.
 */
object BriefBuilder {

    const val SECTION_LIMIT: Int = 5

    const val KEY_WATCHLIST: String = "watchlist"

    /**
     * Order is editorial, not alphabetical.
     *
     * Your own holdings first, then the categories that reprice something, then context.
     * Anything uncategorised sits at the bottom — it is the section most likely to be
     * skipped, so it should cost the least to skip.
     */
    private val GROUP_ORDER = listOf(
        CategoryGroup.MOVERS,
        CategoryGroup.RESULTS,
        // After the things that happened, before the context. A broker's view is worth
        // reading once the facts are in and is not worth leading with.
        CategoryGroup.VIEWS,
        CategoryGroup.POLICY,
        CategoryGroup.GLOBAL,
        CategoryGroup.OTHER,
    )

    /**
     * @param expanded section keys the reader has opened; those skip the cap.
     */
    fun build(
        stories: List<ScoredArticle>,
        watchlist: Set<String>,
        sinceMillis: Long,
        limit: Int = SECTION_LIMIT,
        expanded: Set<String> = emptySet(),
    ): Brief {
        // A story belongs to exactly one section. Duplicating a watchlist story into its
        // category too would inflate every count and make the brief feel longer than it
        // is — the opposite of the point.
        val watchlistStories = stories.filter { scored ->
            watchlist.isNotEmpty() && scored.article.symbols.any { it in watchlist }
        }

        return Brief(
            sinceMillis = sinceMillis,
            totalStories = stories.size,
            watchlistCount = watchlistStories.size,
            sections = sections(stories, watchlist, limit, expanded),
        )
    }

    /**
     * The grouping on its own, without the brief's header counts.
     *
     * Split out for [FeedLayout], which needs the same sections over the live list. The
     * ordering and the one-story-one-section rule are the interesting parts and should
     * not be reimplemented per screen.
     */
    fun sections(
        stories: List<ScoredArticle>,
        watchlist: Set<String>,
        limit: Int = SECTION_LIMIT,
        expanded: Set<String> = emptySet(),
    ): List<BriefSection> {
        val watchlistStories = stories.filter { scored ->
            watchlist.isNotEmpty() && scored.article.symbols.any { it in watchlist }
        }
        val watchlistIds = watchlistStories.mapTo(HashSet()) { it.article.id }
        val rest = stories.filterNot { it.article.id in watchlistIds }

        val sections = ArrayList<BriefSection>(GROUP_ORDER.size + 1)
        if (watchlistStories.isNotEmpty()) {
            sections += section(KEY_WATCHLIST, "Your watchlist", watchlistStories, limit, expanded)
        }
        for (group in GROUP_ORDER) {
            val inGroup = rest.filter { it.article.category.group == group }
            if (inGroup.isEmpty()) continue
            sections += section(group.name, group.label, inGroup, limit, expanded)
        }
        return sections
    }

    private fun section(
        key: String,
        title: String,
        stories: List<ScoredArticle>,
        limit: Int,
        expanded: Set<String>,
    ): BriefSection {
        val shown = if (key in expanded || limit <= 0) stories else stories.take(limit)
        return BriefSection(key, title, shown, stories.size)
    }
}
