package com.abhinavxt.newsforge.ui.feed

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.data.model.ScoredArticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefBuilderTest {

    private var counter = 0

    private fun scored(
        id: String,
        category: Category,
        symbols: List<String> = emptyList(),
        score: Double = (100 - counter++).toDouble(),
    ) = ScoredArticle(
        ArticleSummary(
            id = id,
            clusterId = id,
            title = id,
            summary = null,
            link = "https://example.com/$id",
            sourceName = "Wire",
            category = category,
            tier = SourceTier.WIRE,
            publishedAt = 0L,
            symbols = symbols,
            clusterSize = 1,
            otherSources = emptyList(),
            read = false,
            saved = false,
        ),
        score,
    )

    private val stories = listOf(
        scored("sebi", Category.REGULATORY),
        scored("infy-results", Category.RESULTS, symbols = listOf("INFY")),
        scored("sbin-results", Category.RESULTS),
        scored("order", Category.ORDER_WIN, symbols = listOf("TATASTEEL")),
        scored("pli", Category.POLICY),
        scored("fed", Category.GLOBAL),
        scored("misc", Category.OTHER),
    )

    @Test
    fun sectionsFollowTheEditorialOrder() {
        val brief = BriefBuilder.build(stories, watchlist = setOf("INFY"), sinceMillis = 0L)
        assertEquals(
            listOf("watchlist", "MOVERS", "RESULTS", "POLICY", "GLOBAL", "OTHER"),
            brief.sections.map { it.key },
        )
    }

    @Test
    fun aStoryAppearsInExactlyOneSection() {
        // Duplicating a watchlist story into its category too would inflate every count
        // and make the brief feel longer than it is.
        val brief = BriefBuilder.build(stories, watchlist = setOf("INFY"), sinceMillis = 0L)
        val ids = brief.sections.flatMap { section -> section.stories.map { it.article.id } }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(stories.size, ids.size)

        val results = brief.sections.single { it.key == "RESULTS" }
        assertEquals(listOf("sbin-results"), results.stories.map { it.article.id })
    }

    @Test
    fun watchlistSectionIsAbsentWithoutAWatchlist() {
        val brief = BriefBuilder.build(stories, watchlist = emptySet(), sinceMillis = 0L)
        assertTrue(brief.sections.none { it.key == BriefBuilder.KEY_WATCHLIST })
        assertEquals(0, brief.watchlistCount)
    }

    @Test
    fun emptySectionsAreDropped() {
        val onlyPolicy = listOf(scored("pli", Category.POLICY))
        val brief = BriefBuilder.build(onlyPolicy, emptySet(), 0L)
        assertEquals(listOf("POLICY"), brief.sections.map { it.key })
    }

    @Test
    fun sectionsAreCappedAndReportWhatIsHidden() {
        val many = (1..9).map { scored("p$it", Category.POLICY) }
        val brief = BriefBuilder.build(many, emptySet(), 0L, limit = 3)
        val policy = brief.sections.single()
        assertEquals(3, policy.stories.size)
        assertEquals(9, policy.totalCount)
        assertEquals(6, policy.hiddenCount)
    }

    @Test
    fun anExpandedSectionSkipsTheCap() {
        val many = (1..9).map { scored("p$it", Category.POLICY) }
        val brief = BriefBuilder.build(many, emptySet(), 0L, limit = 3, expanded = setOf("POLICY"))
        val policy = brief.sections.single()
        assertEquals(9, policy.stories.size)
        assertEquals(0, policy.hiddenCount)
    }

    @Test
    fun rankOrderIsPreservedWithinASection() {
        val ranked = listOf(
            scored("high", Category.POLICY, score = 9.0),
            scored("mid", Category.POLICY, score = 5.0),
            scored("low", Category.POLICY, score = 1.0),
        )
        val brief = BriefBuilder.build(ranked, emptySet(), 0L)
        assertEquals(listOf("high", "mid", "low"), brief.sections.single().stories.map { it.article.id })
    }

    @Test
    fun countsDescribeTheWholeBriefNotTheTruncatedView() {
        val many = (1..9).map { scored("p$it", Category.POLICY) } +
            scored("mine", Category.RESULTS, symbols = listOf("INFY"))
        val brief = BriefBuilder.build(many, watchlist = setOf("INFY"), sinceMillis = 0L, limit = 2)
        assertEquals(10, brief.totalStories)
        assertEquals(1, brief.watchlistCount)
    }

    @Test
    fun emptyInputProducesAnEmptyBrief() {
        val brief = BriefBuilder.build(emptyList(), setOf("INFY"), 0L)
        assertEquals(0, brief.totalStories)
        assertTrue(brief.sections.isEmpty())
    }
}
