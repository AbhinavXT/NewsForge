package com.abhinavxt.newsforge.core.learn

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.core.quote.PricePoint
import com.abhinavxt.newsforge.core.rank.MarketPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportanceLabelsTest {

    private val minute = 60_000L
    private val published = 1_000_000_000L

    private fun features() = FeatureInput(
        category = Category.RESULTS,
        tier = SourceTier.OFFICIAL,
        isFiling = true,
        outletCount = 1,
        spanMinutes = 0,
        symbolCount = 1,
        title = "Financial results",
        phase = MarketPhase.OPEN,
    )

    /** Flat to publication, then stepped, sampled every minute for two hours. */
    private fun step(before: Double, after: Double): List<PricePoint> =
        (-30L..120L).map { offset ->
            PricePoint(published + offset * minute, if (offset <= 0) before else after)
        }

    /** Ten peers that went nowhere, so the benchmark is zero. */
    private val flatMarket: Map<String, List<PricePoint>> =
        (1..10).associate { "PEER$it" to step(50.0, 50.0) }

    private fun candidate(symbol: String = "INFY", at: Long = published) =
        LabelCandidate(symbol, at, features())

    @Test
    fun aStoryFollowedByAnAbnormalMoveIsLabelledAsMattering() {
        val batch = ImportanceLabels.sweep(
            candidates = listOf(candidate()),
            series = flatMarket + ("INFY" to step(100.0, 103.0)),
            watermark = 0L,
            nowMillis = published + 3 * 60 * minute,
        )
        assertEquals(1.0, batch.examples.single().label, 1e-9)
    }

    @Test
    fun aStoryThatMovedWithTheMarketIsLabelledAsNot() {
        // Everything up 3%, including this stock: a rising tide, not a reaction.
        val risingMarket = (1..10).associate { "PEER$it" to step(50.0, 51.5) }
        val batch = ImportanceLabels.sweep(
            candidates = listOf(candidate()),
            series = risingMarket + ("INFY" to step(100.0, 103.0)),
            watermark = 0L,
            nowMillis = published + 3 * 60 * minute,
        )
        assertEquals(0.0, batch.examples.single().label, 1e-9)
    }

    @Test
    fun anUnsettledWindowWaitsAndHoldsTheWatermarkBack() {
        val batch = ImportanceLabels.sweep(
            candidates = listOf(candidate()),
            series = flatMarket + ("INFY" to step(100.0, 103.0)),
            watermark = 0L,
            // Forty minutes in: the hour has not closed.
            nowMillis = published + 40 * minute,
        )
        assertTrue(batch.examples.isEmpty())
        // Held back, or the story would be skipped forever once its hour did close.
        assertEquals(0L, batch.watermark)
    }

    @Test
    fun anUnmeasurableStoryStillAdvancesTheWatermark() {
        // No prices for this company at all. That will not change tomorrow, since
        // samples are only written when they are observed.
        val batch = ImportanceLabels.sweep(
            candidates = listOf(candidate(symbol = "UNSAMPLED")),
            series = flatMarket,
            watermark = 0L,
            nowMillis = published + 3 * 60 * minute,
        )
        assertTrue(batch.examples.isEmpty())
        assertEquals(published, batch.watermark)
    }

    @Test
    fun withoutABenchmarkThereIsNoLabel() {
        // A raw move is not evidence of a reaction, and learning from one would teach
        // the model the market's direction.
        val batch = ImportanceLabels.sweep(
            candidates = listOf(candidate()),
            series = mapOf("INFY" to step(100.0, 103.0)),
            watermark = 0L,
            nowMillis = published + 3 * 60 * minute,
        )
        assertTrue(batch.examples.isEmpty())
    }

    @Test
    fun storiesAtOrBeforeTheWatermarkAreNotTrainedTwice() {
        val batch = ImportanceLabels.sweep(
            candidates = listOf(candidate(at = published)),
            series = flatMarket + ("INFY" to step(100.0, 103.0)),
            watermark = published,
            nowMillis = published + 3 * 60 * minute,
        )
        assertTrue(batch.examples.isEmpty())
        assertEquals(published, batch.watermark)
    }

    @Test
    fun theModelWatermarkOnlyMovesForward() {
        val model = ImportanceModel().withWatermark(500L)
        assertEquals(500L, model.watermark)
        assertEquals(500L, model.withWatermark(100L).watermark)
        // Moving the watermark learns nothing.
        assertEquals(0, model.examplesSeen)
    }
}
