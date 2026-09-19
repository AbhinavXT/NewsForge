package com.abhinavxt.newsforge.data.ingest

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.core.notify.EventAlertPolicy
import com.abhinavxt.newsforge.data.model.ArticleSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class FeedRankingTest {

    private fun ist(text: String): Long =
        OffsetDateTime.parse("${text}+05:30").toInstant().toEpochMilli()

    /** 2026-09-09 is a Wednesday; 10:30 is mid-session. */
    private val midSession = ist("2026-09-09T10:30:00")

    private fun summary(
        id: String,
        category: Category = Category.OTHER,
        publishedAt: Long = midSession,
        clusterSize: Int = 1,
        symbols: List<String> = emptyList(),
    ) = ArticleSummary(
        id = id,
        clusterId = id,
        title = id,
        summary = null,
        link = "https://example.com/$id",
        sourceName = "Wire",
        category = category,
        tier = SourceTier.WIRE,
        publishedAt = publishedAt,
        symbols = symbols,
        clusterSize = clusterSize,
        otherSources = emptyList(),
        read = false,
        saved = false,
    )

    @Test
    fun ordersByScoreDescending() {
        val ranked = FeedRanking.rank(
            listOf(
                summary("trivial", Category.OTHER),
                summary("regulatory", Category.REGULATORY),
                summary("results", Category.RESULTS),
            ),
            midSession,
        )
        assertEquals(listOf("regulatory", "results", "trivial"), ranked.map { it.article.id })
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun tiesBreakOnRecencyThenIdSoOrderIsStable() {
        val older = summary("b", publishedAt = midSession - 60_000)
        val newer = summary("a", publishedAt = midSession)
        val ranked = FeedRanking.rank(listOf(older, newer), midSession)
        assertEquals(listOf("a", "b"), ranked.map { it.article.id })
    }

    @Test
    fun broaderCoverageAndTaggedSymbolsBothLiftAStory() {
        val plain = FeedRanking.rank(listOf(summary("plain")), midSession).single().score
        val covered = FeedRanking.rank(
            listOf(summary("covered", clusterSize = 4, symbols = listOf("RELIANCE"))),
            midSession,
        ).single().score
        assertTrue(covered > plain)
    }

    @Test
    fun overnightBriefStartsAtThePreviousSessionClose() {
        val morning = ist("2026-09-09T08:30:00")
        val items = listOf(
            summary("beforeClose", publishedAt = ist("2026-09-08T14:00:00")),
            summary("afterClose", publishedAt = ist("2026-09-08T19:00:00")),
            summary("earlyToday", publishedAt = ist("2026-09-09T07:00:00")),
        )
        val brief = FeedRanking.overnight(items, morning).map { it.article.id }
        assertTrue("intraday news from before the close is not overnight news", "beforeClose" !in brief)
        assertTrue("afterClose" in brief)
        assertTrue("earlyToday" in brief)
    }

    @Test
    fun theBriefIsTheSameWhetherTakenBeforeOrAfterRanking() {
        // The feed holds one subscription and derives the brief from the ranked live
        // list. That is only sound because a score depends on the article alone, never
        // on the set it sits in — if that ever stops being true, this fails.
        val morning = ist("2026-09-09T08:30:00")
        val items = listOf(
            summary("beforeClose", publishedAt = ist("2026-09-08T14:00:00")),
            summary("afterClose", Category.ORDER_WIN, ist("2026-09-08T19:00:00")),
            summary("earlyToday", Category.RESULTS, ist("2026-09-09T07:00:00"), symbols = listOf("INFY")),
        )
        assertEquals(
            FeedRanking.overnight(items, morning),
            FeedRanking.overnightOf(FeedRanking.rank(items, morning), morning),
        )
    }

    @Test
    fun emptyInputRanksToEmpty() {
        assertEquals(emptyList<Any>(), FeedRanking.rank(emptyList(), midSession))
        assertEquals(emptyList<Any>(), FeedRanking.overnight(emptyList(), midSession))
    }
}

class RetentionTest {

    private val now = 1_757_400_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun cutoffIsSevenDaysBack() {
        assertEquals(now - 7 * day, Retention.cutoffMillis(now))
    }

    @Test
    fun notifiedRowsOutliveTheEventDedupeWindow() {
        // They used to be pruned on the article cutoff, which silently capped a thirty-day
        // dedupe window at seven. If the policy window ever grows past retention, the same
        // event starts announcing itself twice.
        val kept = now - Retention.notifiedCutoffMillis(now)
        assertTrue(kept > EventAlertPolicy.DEDUPE_WINDOW_MS)
        assertTrue(kept > now - Retention.cutoffMillis(now))
    }

    @Test
    fun clusterWindowIsWiderThanTheClustererWindow() {
        // The query has to return every row that could be within six hours of any item in
        // an incoming batch, and batches contain items older than now.
        val span = now - Retention.clusterWindowStart(now)
        assertTrue(span > com.abhinavxt.newsforge.core.dedupe.Clusterer.DEFAULT_WINDOW_MS)
        assertTrue(Retention.clusterWindowStart(now) > Retention.cutoffMillis(now))
    }
}
