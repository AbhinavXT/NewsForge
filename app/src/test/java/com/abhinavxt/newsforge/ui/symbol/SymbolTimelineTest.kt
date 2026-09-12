package com.abhinavxt.newsforge.ui.symbol

import com.abhinavxt.newsforge.core.calendar.EventType
import com.abhinavxt.newsforge.core.calendar.UpcomingEvent
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.data.ingest.Retention
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.data.model.ScoredArticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class SymbolTimelineTest {

    private fun ist(text: String): Long =
        OffsetDateTime.parse("${text}+05:30").toInstant().toEpochMilli()

    private val now = ist("2026-09-09T10:30:00")

    private fun story(id: String, category: Category, day: String) = ScoredArticle(
        ArticleSummary(
            id = id,
            clusterId = id,
            title = id,
            summary = null,
            link = "https://example.com/$id",
            sourceName = "Wire",
            category = category,
            tier = SourceTier.WIRE,
            publishedAt = ist("${day}T09:00:00"),
            symbols = listOf("TATASTEEL"),
            clusterSize = 1,
            otherSources = emptyList(),
            read = false,
            saved = false,
        ),
        1.0,
    )

    private fun event(day: String) = UpcomingEvent(
        id = day,
        symbol = "TATASTEEL",
        type = EventType.RESULTS,
        title = "Quarterly results",
        dateMillis = ist("${day}T00:00:00"),
        sourceFeedId = "nse",
    )

    @Test
    fun countsCategoriesByFrequency() {
        val summary = SymbolTimeline.summarize(
            "TATASTEEL",
            listOf(
                story("a", Category.REGULATORY, "2026-09-09"),
                story("b", Category.REGULATORY, "2026-09-05"),
                story("c", Category.REGULATORY, "2026-08-20"),
                story("d", Category.RESULTS, "2026-09-01"),
            ),
            emptyList(),
            now,
        )
        assertEquals(4, summary.storyCount)
        assertEquals(Category.REGULATORY to 3, summary.byCategory.first())
    }

    @Test
    fun equalCountsOrderByCategoryWeight() {
        val summary = SymbolTimeline.summarize(
            "TATASTEEL",
            listOf(
                story("a", Category.OTHER, "2026-09-09"),
                story("b", Category.REGULATORY, "2026-09-09"),
            ),
            emptyList(),
            now,
        )
        // One each, so the taxonomy's own weighting breaks the tie.
        assertEquals(Category.REGULATORY, summary.byCategory.first().first)
    }

    @Test
    fun onlyPresentCategoriesAreListed() {
        val summary = SymbolTimeline.summarize(
            "TATASTEEL",
            listOf(story("a", Category.RESULTS, "2026-09-09")),
            emptyList(),
            now,
        )
        assertEquals(1, summary.byCategory.size)
    }

    @Test
    fun theNextEventIsTheSoonestFutureOne() {
        val summary = SymbolTimeline.summarize(
            "TATASTEEL",
            emptyList(),
            listOf(event("2026-09-25"), event("2026-09-14"), event("2026-09-01")),
            now,
        )
        assertEquals("2026-09-14", summary.nextEvent?.id)
    }

    @Test
    fun noScheduledEventIsNull() {
        assertNull(SymbolTimeline.summarize("TATASTEEL", emptyList(), emptyList(), now).nextEvent)
    }

    @Test
    fun sectionsAreCoarseAndNewestFirst() {
        val sections = SymbolTimeline.sections(
            listOf(
                story("old", Category.RESULTS, "2026-06-01"),
                story("today", Category.RESULTS, "2026-09-09"),
                story("week", Category.RESULTS, "2026-09-05"),
                story("month", Category.RESULTS, "2026-08-25"),
            ),
            now,
        )
        assertEquals(listOf("Today", "This week", "This month", "Earlier"), sections.map { it.label })
    }

    @Test
    fun emptySectionsAreDropped() {
        val sections = SymbolTimeline.sections(
            listOf(story("today", Category.RESULTS, "2026-09-09")),
            now,
        )
        assertEquals(listOf("Today"), sections.map { it.label })
    }

    @Test
    fun withinASectionStoriesAreNewestFirst() {
        val sections = SymbolTimeline.sections(
            listOf(
                story("older", Category.RESULTS, "2026-09-04"),
                story("newer", Category.RESULTS, "2026-09-07"),
            ),
            now,
        )
        assertEquals(listOf("newer", "older"), sections.single().stories.map { it.article.id })
    }

    @Test
    fun emptyInputProducesNothing() {
        assertTrue(SymbolTimeline.sections(emptyList(), now).isEmpty())
        assertEquals(0, SymbolTimeline.summarize("X", emptyList(), emptyList(), now).storyCount)
    }

    @Test
    fun followedCompaniesOutliveTheGeneralRetentionWindow() {
        // Without this the timeline would only ever be a week deep, which is no timeline.
        assertTrue(Retention.FOLLOWED_KEEP_DAYS > Retention.KEEP_DAYS)
        assertTrue(Retention.followedCutoffMillis(now) < Retention.cutoffMillis(now))
    }
}
