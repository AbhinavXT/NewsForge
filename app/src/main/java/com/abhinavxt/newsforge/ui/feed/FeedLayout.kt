package com.abhinavxt.newsforge.ui.feed

import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.data.model.ScoredArticle

/**
 * The live feed, as a lead block over capped sections.
 *
 * A flat ranked list cannot stop one kind of story owning the screen, because a score is
 * computed per article and knows nothing about its neighbours. Fourteen routine exchange
 * intimations arriving together will therefore take fourteen slots — each individually
 * well ranked, collectively a wall.
 *
 * Sections fix that structurally: whatever arrives in bulk takes one heading and a count
 * instead of the whole viewport. The lead block exists so that capping does not bury the
 * two or three stories that actually matter this morning underneath a heading — they sit
 * above the grouping, ungrouped, ranked purely on score.
 *
 * This is the same shape [BriefBuilder] already gives the overnight brief. The brief was
 * always the better-organised of the two screens; the live feed had the harder problem
 * and the flatter answer.
 */
data class FeedLayout(
    val lead: List<ScoredArticle>,
    val sections: List<BriefSection>,
) {
    val isFlat: Boolean get() = sections.isEmpty()

    companion object {

        /** Ranked above the grouping. Two, because three pushes the first heading off-screen. */
        const val LEAD_COUNT: Int = 2

        /**
         * Stories per section before it collapses to a count.
         *
         * Tighter than the brief's five. The brief is read once, in a sitting, and can
         * afford depth; the live feed is checked twenty times a day and wants the shape
         * of what has happened, not the contents.
         */
        const val SECTION_LIMIT: Int = 3

        /**
         * Below this the list stays flat.
         *
         * Sections over a handful of stories are headings with one item under each, which
         * reads worse than the plain list they were meant to improve — and a quiet
         * pre-open feed is exactly when that happens.
         */
        const val MIN_FOR_SECTIONS: Int = 8

        /**
         * @param expanded section keys the reader has opened; those skip the cap.
         */
        fun build(
            stories: List<ScoredArticle>,
            watchlist: Set<String>,
            leadCount: Int = LEAD_COUNT,
            limit: Int = SECTION_LIMIT,
            expanded: Set<String> = emptySet(),
        ): FeedLayout {
            if (stories.size < MIN_FOR_SECTIONS) return FeedLayout(stories, emptyList())
            val lead = stories.take(leadCount)
            val sections = BriefBuilder.sections(
                stories = stories.drop(leadCount),
                watchlist = watchlist,
                limit = limit,
                expanded = expanded,
            )
            // Everything landed in the lead, or grouping produced a single section that
            // holds the lot: either way the headings are decoration. Fall back to flat.
            if (sections.isEmpty()) return FeedLayout(stories, emptyList())
            return FeedLayout(lead, sections)
        }
    }
}

/**
 * Whether this is an exchange filing rather than a news story.
 *
 * Keyed on the feed it came from. The first version used `SourceTier.OFFICIAL`, which was
 * close and wrong at the edges: a ministry press release carries the same tier and is
 * prose, so it rendered as a one-line register entry truncated mid-sentence. The exchange
 * feeds are both the ones that arrive in bulk and the ones whose entries genuinely are a
 * ticker, a subject and a time.
 */
val ArticleSummary.isFiling: Boolean
    get() = feedKind.isNse
