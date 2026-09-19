package com.abhinavxt.newsforge.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionWeightTest {

    @Test
    fun aLargePositionIsAlertedATierLouder() {
        // Six per cent can move the account on its own; it should not be judged at the
        // same bar as the twentieth name in the book.
        assertEquals(
            WatchTier.LEVERAGED,
            PositionWeight.effectiveTier(WatchTier.HOLDING, 6.0),
        )
    }

    @Test
    fun aTokenPositionIsAlertedATierQuieter() {
        // The half-per-cent placeholders are where most of the ignorable notifications
        // come from.
        assertEquals(
            WatchTier.WATCHING,
            PositionWeight.effectiveTier(WatchTier.HOLDING, 0.5),
        )
    }

    @Test
    fun anOrdinaryPositionIsLeftAlone() {
        assertEquals(
            WatchTier.HOLDING,
            PositionWeight.effectiveTier(WatchTier.HOLDING, 3.0),
        )
    }

    @Test
    fun anUnknownWeightChangesNothing() {
        // Manual rows and a desk that sends no weights. Guessing would be wrong in one
        // direction for everybody.
        assertEquals(WatchTier.HOLDING, PositionWeight.effectiveTier(WatchTier.HOLDING, null))
        assertEquals(WatchTier.HOLDING, PositionWeight.effectiveTier(WatchTier.HOLDING, 0.0))
    }

    @Test
    fun theShiftIsClampedAtBothEnds() {
        // Nothing is louder than leveraged and nothing quieter than watching; the bands
        // must not fall off either end into a tier that does not exist.
        assertEquals(
            WatchTier.LEVERAGED,
            PositionWeight.effectiveTier(WatchTier.LEVERAGED, 12.0),
        )
        assertEquals(
            WatchTier.WATCHING,
            PositionWeight.effectiveTier(WatchTier.WATCHING, 0.2),
        )
    }

    @Test
    fun theWeightComesFromTheSymbolThatEarnedTheTier() {
        // A story naming four companies is judged on the one you are most exposed to —
        // taking a different symbol's weight would rate it on a position it is not about.
        val tiers = mapOf("BEL" to WatchTier.LEVERAGED, "HCLTECH" to WatchTier.HOLDING)
        val weights = mapOf("BEL" to 2.0, "HCLTECH" to 9.0)
        assertEquals(
            2.0,
            PositionWeight.strongestWeight(listOf("HCLTECH", "BEL"), tiers, weights) ?: 0.0,
            1e-9,
        )
    }

    @Test
    fun amongEqualTiersTheLargestPositionWins() {
        val tiers = mapOf("A" to WatchTier.HOLDING, "B" to WatchTier.HOLDING)
        val weights = mapOf("A" to 1.0, "B" to 7.0)
        assertEquals(
            7.0,
            PositionWeight.strongestWeight(listOf("A", "B"), tiers, weights) ?: 0.0,
            1e-9,
        )
    }

    @Test
    fun aStoryTouchingNothingHeldHasNoWeight() {
        assertNull(PositionWeight.strongestWeight(listOf("ZZZZ"), emptyMap(), emptyMap()))
    }
}
