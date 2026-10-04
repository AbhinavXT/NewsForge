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
        outletCount: Int = 1,
    ) = RankInput(category, tier, now - ageMinutes * minute, symbolCount, outletCount)

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
        val single = Ranker.score(input(outletCount = 1), now, MarketPhase.OPEN)
        val four = Ranker.score(input(outletCount = 4), now, MarketPhase.OPEN)
        val eight = Ranker.score(input(outletCount = 8), now, MarketPhase.OPEN)
        val twenty = Ranker.score(input(outletCount = 20), now, MarketPhase.OPEN)
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
    fun outletCountBelowOneIsTreatedAsOne() {
        assertEquals(
            Ranker.score(input(outletCount = 1), now, MarketPhase.OPEN),
            Ranker.score(input(outletCount = 0), now, MarketPhase.OPEN),
            1e-9,
        )
    }

    @Test
    fun theExplanationMultipliesBackToTheScore() {
        // The whole point of showing the working: if the factors and the number disagree,
        // the explanation is worse than nothing because it invites the wrong fix.
        val input = RankInput(
            category = Category.REGULATORY,
            tier = SourceTier.OFFICIAL,
            publishedAtMillis = now - 30 * 60_000,
            symbolCount = 2,
            outletCount = 4,
        )
        val explanation = Ranker.explain(input, now, MarketPhase.OPEN)
        assertEquals(
            Ranker.score(input, now, MarketPhase.OPEN),
            explanation.score,
            1e-9,
        )
    }

    @Test
    fun aFactorThatDidNothingIsMarkedNeutral() {
        // An untagged story should not have "Companies 1.00x" read as a contribution.
        val explanation = Ranker.explain(
            RankInput(Category.OTHER, SourceTier.WIRE, now, symbolCount = 0, outletCount = 1),
            now,
            MarketPhase.OPEN,
        )
        val byName = explanation.factors.associateBy { it.name }
        assertTrue(byName.getValue("Companies").isNeutral)
        assertTrue(byName.getValue("Coverage").isNeutral)
    }

    @Test
    fun theDominantFactorIsTheOneThatMovedItFurthest() {
        // Two days old during a session: age should be the answer to "why is this low",
        // not the category it happens to belong to.
        val explanation = Ranker.explain(
            RankInput(
                category = Category.REGULATORY,
                tier = SourceTier.OFFICIAL,
                publishedAtMillis = now - 2 * 24 * 60 * 60_000L,
                symbolCount = 1,
                outletCount = 2,
            ),
            now,
            MarketPhase.OPEN,
        )
        assertEquals("Age", explanation.dominant?.name)
    }

    @Test
    fun everyFactorIsNamedAndDescribed() {
        // The row is only useful if it says what it is about; a blank detail would leave
        // a multiplier with nothing to attach it to.
        val explanation = Ranker.explain(
            RankInput(Category.RESULTS, SourceTier.WIRE, now, 1, 3),
            now,
            MarketPhase.OPEN,
        )
        assertEquals(6, explanation.factors.size)
        assertTrue(explanation.factors.all { it.name.isNotBlank() && it.detail.isNotBlank() })
    }

    @Test
    fun aStoryPickedUpFastOutranksTheSameStoryPickedUpSlowly() {
        // Four outlets inside twenty minutes is a break; the same four over two days is
        // a topic. A count alone cannot tell them apart, which is what this term is for.
        val fast = Ranker.score(
            input(outletCount = 4).copy(spanMinutes = 20),
            now,
            MarketPhase.OPEN,
        )
        val slow = Ranker.score(
            input(outletCount = 4).copy(spanMinutes = 2 * 24 * 60),
            now,
            MarketPhase.OPEN,
        )
        assertTrue(fast > slow)
    }

    @Test
    fun anExclusiveIsNotPunishedForHavingNoPickup() {
        // A single-outlet story has no pickup to measure, and treating that as slow
        // would mean every scoop scored as though the market had ignored it.
        assertEquals(1.0, Ranker.velocityFactor(outletCount = 1, spanMinutes = 0), 1e-9)
        assertEquals(1.0, Ranker.velocityFactor(outletCount = 1, spanMinutes = 5_000), 1e-9)
    }

    @Test
    fun aSharedTimestampDoesNotProduceAnInfiniteRate() {
        // Outlets republish a filing on the same minute constantly. Without the floor on
        // the span this divides by zero.
        val factor = Ranker.velocityFactor(outletCount = 5, spanMinutes = 0)
        assertTrue(factor.isFinite())
        // Capped, not unbounded: a burst is a burst however tight the timestamps are.
        assertEquals(Ranker.velocityFactor(outletCount = 5, spanMinutes = 1), factor, 1e-9)
    }

    @Test
    fun theVelocityBoostIsBoundedAtTheReferenceRate() {
        val hot = Ranker.velocityFactor(outletCount = 40, spanMinutes = 5)
        // Half the reference rate: three pickups over two hours. (Four outlets inside
        // one hour is exactly the reference, so it earns the full boost too.)
        val warm = Ranker.velocityFactor(outletCount = 4, spanMinutes = 120)
        assertTrue(hot > warm)
        // Whatever the rate, the term multiplies by at most 1.35 — coverage and category
        // still decide the story, and pickup only tips it.
        assertTrue(hot <= 1.35 + 1e-9)
    }
}
