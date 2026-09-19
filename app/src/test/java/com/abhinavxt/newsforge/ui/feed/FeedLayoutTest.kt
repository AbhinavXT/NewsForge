package com.abhinavxt.newsforge.ui.feed

import com.abhinavxt.newsforge.core.feed.FeedKind
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.data.model.ScoredArticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedLayoutTest {

    private var seq = 0

    private fun story(
        category: Category = Category.OTHER,
        symbols: List<String> = emptyList(),
        tier: SourceTier = SourceTier.WIRE,
        feedKind: FeedKind = FeedKind.RSS,
    ): ScoredArticle {
        val n = seq++
        return ScoredArticle(
            article = ArticleSummary(
                id = "a$n",
                clusterId = "c$n",
                title = "Story $n",
                summary = null,
                link = "https://example.com/$n",
                sourceName = "Source",
                category = category,
                tier = tier,
                feedKind = feedKind,
                publishedAt = 1_000L + n,
                symbols = symbols,
                clusterSize = 1,
                otherSources = emptyList(),
                read = false,
                saved = false,
            ),
            score = 100.0 - n,
        )
    }

    private fun stories(n: Int, category: Category = Category.OTHER) =
        List(n) { story(category = category) }

    @Test
    fun aShortListStaysFlat() {
        // Headings over a handful of stories are worse than the list they replace, and a
        // quiet pre-open feed is exactly when that happens.
        val items = stories(FeedLayout.MIN_FOR_SECTIONS - 1, Category.RESULTS)
        val layout = FeedLayout.build(items, watchlist = emptySet())
        assertTrue(layout.isFlat)
        assertEquals(items, layout.lead)
    }

    @Test
    fun oneKindArrivingInBulkCannotTakeTheWholeScreen() {
        // The whole point: fourteen filings land together and each one ranks fine on its
        // own, because a score never sees its neighbours.
        val layout = FeedLayout.build(stories(14, Category.RESULTS), watchlist = emptySet())
        assertFalse(layout.isFlat)
        assertEquals(FeedLayout.LEAD_COUNT, layout.lead.size)
        assertEquals(1, layout.sections.size)
        assertEquals(FeedLayout.SECTION_LIMIT, layout.sections.single().stories.size)
        // The count stays honest about what was capped.
        assertEquals(12, layout.sections.single().totalCount)
    }

    @Test
    fun theLeadNeverRepeatsIntoASection() {
        // Both feed the same LazyColumn, which keys on cluster id — a duplicate would be
        // a crash, not a cosmetic bug.
        val layout = FeedLayout.build(
            stories(6, Category.RESULTS) + stories(6, Category.POLICY),
            watchlist = emptySet(),
            expanded = setOf("RESULTS", "POLICY"),
        )
        val leadIds = layout.lead.map { it.article.clusterId }
        val sectionIds = layout.sections.flatMap { s -> s.stories.map { it.article.clusterId } }
        assertEquals(emptyList<String>(), leadIds.filter { it in sectionIds })
        assertEquals(12, leadIds.size + sectionIds.size)
    }

    @Test
    fun expandingASectionLiftsItsCapAndNoOthers() {
        val items = stories(8, Category.RESULTS) + stories(8, Category.POLICY)
        val layout = FeedLayout.build(items, watchlist = emptySet(), expanded = setOf("POLICY"))
        val byKey = layout.sections.associateBy { it.key }
        assertEquals(0, byKey.getValue("POLICY").hiddenCount)
        assertTrue(byKey.getValue("RESULTS").hiddenCount > 0)
    }

    @Test
    fun watchlistStoriesLeadTheSections() {
        val layout = FeedLayout.build(
            stories(6, Category.RESULTS) + List(4) { story(Category.POLICY, listOf("INFY")) },
            watchlist = setOf("INFY"),
        )
        assertEquals(BriefBuilder.KEY_WATCHLIST, layout.sections.first().key)
    }

    @Test
    fun onlyExchangeFeedsRenderAsFilings() {
        // Keyed on the feed rather than the source tier. A ministry press release is
        // OFFICIAL too, and it is prose — as a one-line register entry it truncated
        // mid-sentence and read as a rendering fault.
        assertTrue(story(feedKind = FeedKind.NSE_ANNOUNCEMENT).article.isFiling)
        assertTrue(story(feedKind = FeedKind.NSE_BOARD_MEETING).article.isFiling)
        assertFalse(story(tier = SourceTier.OFFICIAL, feedKind = FeedKind.RSS).article.isFiling)
        assertFalse(story(feedKind = FeedKind.RSS).article.isFiling)
    }
}
