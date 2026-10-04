package com.abhinavxt.newsforge.data.ingest

import com.abhinavxt.newsforge.core.feed.FeedKind
import com.abhinavxt.newsforge.core.learn.ImportanceFeatures
import com.abhinavxt.newsforge.core.learn.ImportanceModel
import com.abhinavxt.newsforge.core.learn.TrainingExample
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.core.notify.EventAlertPolicy
import com.abhinavxt.newsforge.data.model.ArticleSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        outletCount: Int = 1,
        symbols: List<String> = emptyList(),
        feedKind: FeedKind = FeedKind.RSS,
        tier: SourceTier = SourceTier.WIRE,
    ) = ArticleSummary(
        id = id,
        clusterId = id,
        title = id,
        summary = null,
        link = "https://example.com/$id",
        sourceName = "Wire",
        category = category,
        tier = tier,
        publishedAt = publishedAt,
        symbols = symbols,
        outletCount = outletCount,
        otherSources = emptyList(),
        read = false,
        saved = false,
        feedKind = feedKind,
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
            listOf(summary("covered", outletCount = 4, symbols = listOf("RELIANCE"))),
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

    /**
     * A model that has learned filings move stocks and wire copy does not — the opposite
     * of what the hand-tuned weights would say about an OTHER-category filing versus a
     * RESULTS-category wire story.
     */
    private fun filingsMatter(): ImportanceModel {
        val filing = summary("f", feedKind = FeedKind.NSE_ANNOUNCEMENT, symbols = listOf("X"))
        val wire = summary("w", category = Category.RESULTS, symbols = listOf("X"))
        val examples = (1..ImportanceModel.MIN_EXAMPLES).flatMap { index ->
            listOf(
                TrainingExample(ImportanceFeatures.of(featuresOf(filing)), 1.0, index.toLong()),
                TrainingExample(ImportanceFeatures.of(featuresOf(wire)), 0.0, index.toLong()),
            )
        }
        return ImportanceModel().trained(examples).trained(examples)
    }

    @Test
    fun searchResultsKeepTheQuerysOrder() {
        // Newest first from the database, and a strong older story must not jump ahead
        // of a weak newer one: a history search is asking when, not how important.
        val newer = summary("newer", category = Category.OTHER)
        val older = summary(
            "older", category = Category.REGULATORY, outletCount = 6,
            publishedAt = midSession - 3_600_000,
        )
        assertEquals(
            "older",
            FeedRanking.rank(listOf(newer, older), midSession).first().article.id,
        )
        val scored = FeedRanking.scoreInOrder(listOf(newer, older), midSession)
        assertEquals(listOf("newer", "older"), scored.map { it.article.id })
        // Still scored, so the story sheet can explain each one.
        assertTrue(scored.all { it.score > 0.0 })
    }

    @Test
    fun anUntrainedModelChangesNothing() {
        // Cold start is the hand-tuned ranking exactly, not an approximation of it.
        val items = listOf(
            summary("a", category = Category.RESULTS),
            summary("b", category = Category.OTHER, outletCount = 4),
            summary("c", category = Category.REGULATORY, publishedAt = midSession - 3_600_000),
        )
        val hand = FeedRanking.rank(items, midSession)
        val cold = FeedRanking.rank(items, midSession, ImportanceModel())
        assertEquals(hand.map { it.article.id }, cold.map { it.article.id })
        assertEquals(hand.map { it.score }, cold.map { it.score })
        assertNull(cold.first().moveProbability)
    }

    @Test
    fun aTrainedModelDecidesTheOrder() {
        val model = filingsMatter()
        assertTrue(model.isTrained)
        val filing = summary(
            "filing", category = Category.OTHER, symbols = listOf("X"),
            feedKind = FeedKind.NSE_ANNOUNCEMENT,
        )
        val wire = summary("wire", category = Category.RESULTS, symbols = listOf("X"))

        // By hand, results outrank "other" whatever the source.
        assertEquals("wire", FeedRanking.rank(listOf(filing, wire), midSession).first().article.id)
        // By the model, the filing wins, because that is what the evidence said.
        val learned = FeedRanking.rank(listOf(filing, wire), midSession, model)
        assertEquals("filing", learned.first().article.id)
        assertTrue(learned.first().moveProbability!! > learned.last().moveProbability!!)
    }

    @Test
    fun ageStillSinksAStoryTheModelLikes() {
        // The model was trained on whether a story moved a price, which does not decay.
        // Without the age term yesterday's filing would top the feed forever.
        val model = filingsMatter()
        val fresh = summary("fresh", symbols = listOf("X"), feedKind = FeedKind.NSE_ANNOUNCEMENT)
        val stale = summary(
            "stale", symbols = listOf("X"), feedKind = FeedKind.NSE_ANNOUNCEMENT,
            // One day, not three: three days before a Wednesday is a Sunday, the phase
            // at publication would be WEEKEND, and the two stories would no longer share
            // features — which would test something other than age.
            publishedAt = midSession - 24L * 3_600_000,
        )
        val ranked = FeedRanking.rank(listOf(stale, fresh), midSession, model)
        assertEquals("fresh", ranked.first().article.id)
        // Same features, so the same probability: only age separated them.
        assertEquals(ranked[0].moveProbability!!, ranked[1].moveProbability!!, 1e-9)
    }

    @Test
    fun theLearnedOrderNamesItsReasons() {
        val model = filingsMatter()
        val filing = summary("f", symbols = listOf("X"), feedKind = FeedKind.NSE_ANNOUNCEMENT)
        val scored = FeedRanking.rank(listOf(filing), midSession, model).single()
        assertTrue("filing" in scored.drivers)
        // The bias is the same for every story, so it is never a reason for this one.
        assertTrue("bias" !in scored.drivers)
    }

    @Test
    fun theBriefUsesTheSameModel() {
        val model = filingsMatter()
        val filing = summary("filing", symbols = listOf("X"), feedKind = FeedKind.NSE_ANNOUNCEMENT)
        val wire = summary("wire", category = Category.RESULTS, symbols = listOf("X"))
        val brief = FeedRanking.overnight(listOf(filing, wire), midSession, model)
        assertEquals("filing", brief.first().article.id)
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
