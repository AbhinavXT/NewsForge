package com.abhinavxt.newsforge.core.rank

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankerTest {

    private val now = 1_757_400_000_000L
    private val minute = 60_000L

    private fun input(
        category: Category = Category.RESULTS,
        tier: SourceTier = SourceTier.WIRE,
        ageMinutes: Long = 0,
        symbolCount: Int = 0,
        clusterSize: Int = 1,
    ) = RankInput(category, tier, now - ageMinutes * minute, symbolCount, clusterSize)

    @Test
    fun recencyIsOneAtZeroAgeAndHalfAtTheHalfLife() {
        assertEquals(1.0, Ranker.recency(now, now, MarketPhase.OPEN), 1e-9)
        val halfLife = MarketClock.halfLifeMinutes(MarketPhase.OPEN).toLong()
        assertEquals(
            0.5,
            Ranker.recency(now - halfLife * minute, now, MarketPhase.OPEN),
            1e-9,
        )
    }

    @Test
    fun futureTimestampsAreClampedRatherThanRewarded() {
        // Publishers do emit timestamps a few minutes ahead; without the clamp those
        // items would outscore everything permanently.
        assertEquals(1.0, Ranker.recency(now + 30 * minute, now, MarketPhase.OPEN), 1e-9)
    }

    @Test
    fun overnightNewsSurvivesUntilThePreMarketBrief() {
        // 23:00 news read at 08:30 is nine and a half hours old.
        val age = 570L
        val open = Ranker.recency(now - age * minute, now, MarketPhase.OPEN)
        val preOpen = Ranker.recency(now - age * minute, now, MarketPhase.PRE_OPEN)
        assertTrue(preOpen > open * 100)
    }

    @Test
    fun higherWeightCategoryOutranksLowerAtEqualAge() {
        val regulatory = Ranker.score(input(category = Category.REGULATORY), now, MarketPhase.OPEN)
        val general = Ranker.score(input(category = Category.OTHER), now, MarketPhase.OPEN)
        assertTrue(regulatory > general)
    }

    @Test
    fun officialSourcesOutrankAggregators() {
        val official = Ranker.score(input(tier = SourceTier.OFFICIAL), now, MarketPhase.OPEN)
        val aggregator = Ranker.score(input(tier = SourceTier.AGGREGATOR), now, MarketPhase.OPEN)
        assertTrue(official > aggregator)
    }

    @Test
    fun recognisedSymbolLiftsTheScoreOnceOnly() {
        val none = Ranker.score(input(symbolCount = 0), now, MarketPhase.OPEN)
        val one = Ranker.score(input(symbolCount = 1), now, MarketPhase.OPEN)
        val three = Ranker.score(input(symbolCount = 3), now, MarketPhase.OPEN)
        assertTrue(one > none)
        assertEquals(one, three, 1e-9)
    }

    @Test
    fun broaderCoverageLiftsTheScoreButSaturates() {
        val single = Ranker.score(input(clusterSize = 1), now, MarketPhase.OPEN)
        val four = Ranker.score(input(clusterSize = 4), now, MarketPhase.OPEN)
        val eight = Ranker.score(input(clusterSize = 8), now, MarketPhase.OPEN)
        val twenty = Ranker.score(input(clusterSize = 20), now, MarketPhase.OPEN)
        assertTrue(four > single)
        assertTrue(eight > four)
        assertEquals(eight, twenty, 1e-9)
    }

    @Test
    fun ageDominatesCategoryDuringTheSession() {
        // A stale regulatory story must sink below a fresh general one, which an
        // additive scoring model would not do.
        val staleImportant = Ranker.score(
            input(category = Category.REGULATORY, ageMinutes = 480),
            now,
            MarketPhase.OPEN,
        )
        val freshTrivial = Ranker.score(
            input(category = Category.OTHER, ageMinutes = 0),
            now,
            MarketPhase.OPEN,
        )
        assertTrue(freshTrivial > staleImportant)
    }

    @Test
    fun clusterSizeBelowOneIsTreatedAsOne() {
        assertEquals(
            Ranker.score(input(clusterSize = 1), now, MarketPhase.OPEN),
            Ranker.score(input(clusterSize = 0), now, MarketPhase.OPEN),
            1e-9,
        )
    }
}
