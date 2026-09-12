package com.abhinavxt.newsforge.ui.feed

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.CategoryGroup
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.data.model.ScoredArticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class FeedFilteringTest {

    private fun scored(
        id: String,
        category: Category = Category.OTHER,
        symbols: List<String> = emptyList(),
        read: Boolean = false,
        saved: Boolean = false,
        score: Double = 1.0,
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
            read = read,
            saved = saved,
        ),
        score,
    )

    private val items = listOf(
        scored("order", Category.ORDER_WIN, symbols = listOf("TATASTEEL"), score = 3.0),
        scored("results", Category.RESULTS, symbols = listOf("INFY"), read = true, score = 2.0),
        scored("policy", Category.POLICY, score = 1.5),
        scored("global", Category.GLOBAL, saved = true, score = 1.0),
    )

    @Test
    fun noFilterKeepsEverything() {
        assertEquals(items, FeedFiltering.apply(items, FeedFilter()))
    }

    @Test
    fun groupFilterKeepsOnlyThatGroup() {
        val movers = FeedFiltering.apply(items, FeedFilter(group = CategoryGroup.MOVERS))
        assertEquals(listOf("order"), movers.map { it.article.id })
    }

    @Test
    fun filteringPreservesRankOrder() {
        // Chips are views of one ranked list, not separate feeds; reordering here would
        // make the same story sit in different places depending on the chip.
        val filtered = FeedFiltering.apply(items, FeedFilter(unreadOnly = true))
        assertEquals(listOf("order", "policy", "global"), filtered.map { it.article.id })
    }

    @Test
    fun taggedFilterDropsUntaggedStories() {
        val tagged = FeedFiltering.apply(items, FeedFilter(watchlistOnly = true))
        assertEquals(listOf("order", "results"), tagged.map { it.article.id })
    }

    @Test
    fun savedAndUnreadFiltersCombine() {
        assertEquals(
            listOf("global"),
            FeedFiltering.apply(items, FeedFilter(savedOnly = true, unreadOnly = true))
                .map { it.article.id },
        )
    }

    @Test
    fun chipCountsAreComputedBeforeFilteringSoNoChipReadsZeroSpuriously() {
        val counts = FeedFiltering.chipCounts(items)
        assertEquals(1, counts[CategoryGroup.MOVERS])
        assertEquals(1, counts[CategoryGroup.RESULTS])
        assertEquals(1, counts[CategoryGroup.POLICY])
        assertEquals(1, counts[CategoryGroup.GLOBAL])
        assertEquals(0, counts[CategoryGroup.OTHER])
        // Every group is present as a key even at zero, so the chip row is stable.
        assertEquals(CategoryGroup.entries.size, counts.size)
    }

    @Test
    fun watchlistNarrowsTheTaggedFilterToFollowedSymbols() {
        val filtered = FeedFiltering.apply(
            items,
            FeedFilter(watchlistOnly = true),
            watchlist = setOf("INFY"),
        )
        assertEquals(listOf("results"), filtered.map { it.article.id })
    }

    @Test
    fun anEmptyWatchlistFallsBackToAnyRecognisedCompany() {
        // Otherwise the chip shows nothing until a symbol is added, which reads as a
        // broken filter rather than an empty watchlist.
        assertTrue(FeedFiltering.matchesWatchlist(listOf("TATASTEEL"), emptySet()))
        assertTrue(!FeedFiltering.matchesWatchlist(emptyList(), emptySet()))
        assertTrue(!FeedFiltering.matchesWatchlist(listOf("TATASTEEL"), setOf("INFY")))
    }

    @Test
    fun searchMatchesHeadlineOutletAndTicker() {
        val stories = listOf(
            scored("a", Category.ORDER_WIN, symbols = listOf("TATASTEEL")),
            scored("b", Category.RESULTS, symbols = listOf("INFY")),
        )
        assertEquals(
            listOf("a"),
            FeedFiltering.apply(stories, FeedFilter(query = "tatasteel")).map { it.article.id },
        )
        // Title match, case-insensitive.
        assertEquals(
            listOf("b"),
            FeedFiltering.apply(stories, FeedFilter(query = "B")).map { it.article.id },
        )
    }

    @Test
    fun everySearchTermMustMatchSoAddingWordsNarrows() {
        val stories = listOf(scored("order", Category.ORDER_WIN, symbols = listOf("TATASTEEL")))
        assertEquals(1, FeedFiltering.apply(stories, FeedFilter(query = "order tatasteel")).size)
        assertEquals(0, FeedFiltering.apply(stories, FeedFilter(query = "order infy")).size)
    }

    @Test
    fun searchIsSubstringNotTokenBased() {
        // Half of what gets typed here is a partial company name; a token matcher would
        // find nothing for "tatas".
        val stories = listOf(scored("x", symbols = listOf("TATASTEEL")))
        assertEquals(1, FeedFiltering.apply(stories, FeedFilter(query = "tatas")).size)
    }

    @Test
    fun blankQueryDoesNotFilter() {
        assertEquals(items, FeedFiltering.apply(items, FeedFilter(query = "   ")))
    }

    @Test
    fun symbolFilterKeepsOnlyStoriesMentioningIt() {
        assertEquals(
            listOf("order"),
            FeedFiltering.apply(items, FeedFilter(symbol = "TATASTEEL")).map { it.article.id },
        )
        assertTrue(FeedFiltering.apply(items, FeedFilter(symbol = "NOSUCH")).isEmpty())
    }

    @Test
    fun filtersCompose() {
        val narrowed = FeedFiltering.apply(
            items,
            FeedFilter(unreadOnly = true, query = "policy"),
        )
        assertEquals(listOf("policy"), narrowed.map { it.article.id })
    }

    @Test
    fun isNarrowedReportsWhetherAnythingIsActive() {
        assertFalse(FeedFilter().isNarrowed)
        assertTrue(FeedFilter(query = "x").isNarrowed)
        assertTrue(FeedFilter(symbol = "INFY").isNarrowed)
        assertTrue(FeedFilter(savedOnly = true).isNarrowed)
        // A blank query is not a filter.
        assertFalse(FeedFilter(query = "  ").isNarrowed)
    }

    @Test
    fun emptyInputFiltersToEmpty() {
        assertTrue(FeedFiltering.apply(emptyList(), FeedFilter(watchlistOnly = true)).isEmpty())
        assertTrue(FeedFiltering.chipCounts(emptyList()).values.all { it == 0 })
    }
}

class FeedModeTest {

    private fun ist(text: String): Long =
        OffsetDateTime.parse("${text}+05:30").toInstant().toEpochMilli()

    @Test
    fun morningsDefaultToTheBrief() {
        // 2026-09-09 is a Wednesday.
        assertEquals(FeedMode.BRIEF, FeedMode.defaultFor(ist("2026-09-09T08:30:00")))
    }

    @Test
    fun theSessionDefaultsToLive() {
        assertEquals(FeedMode.LIVE, FeedMode.defaultFor(ist("2026-09-09T11:00:00")))
        assertEquals(FeedMode.LIVE, FeedMode.defaultFor(ist("2026-09-09T17:00:00")))
    }

    @Test
    fun weekendsDefaultToTheBrief() {
        // Nothing is live on a Saturday; the useful read is the accumulated digest.
        assertEquals(FeedMode.BRIEF, FeedMode.defaultFor(ist("2026-09-12T11:00:00")))
    }
}
