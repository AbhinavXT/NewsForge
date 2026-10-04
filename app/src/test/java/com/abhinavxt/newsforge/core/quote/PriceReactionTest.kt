package com.abhinavxt.newsforge.core.quote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceReactionTest {

    private val published = 1_789_600_000_000L
    private fun minutes(n: Long) = n * 60 * 1000

    private fun samples(vararg pairs: Pair<Long, Double>) =
        pairs.map { (offset, price) -> PriceSample("BEL", published + offset, price) }

    @Test
    fun measuresFromThePriceJustBeforePublication() {
        val reaction = PriceReaction.of(
            symbol = "BEL",
            samples = samples(-minutes(2) to 400.0, -minutes(30) to 380.0),
            publishedAt = published,
            lastPrice = 412.0,
        )
        assertEquals(ReactionBasis.PUBLICATION, reaction?.basis)
        assertEquals(3.0, reaction?.percent ?: 0.0, 0.01)
    }

    @Test
    fun aSampleAfterPublicationIsNotABaseline() {
        // The whole point is what it was worth before anyone had read this. Measuring
        // from a price five minutes later would quietly hide the move it is meant to show.
        val reaction = PriceReaction.of(
            symbol = "BEL",
            samples = samples(minutes(5) to 411.0),
            publishedAt = published,
            lastPrice = 412.0,
            previousClose = 400.0,
        )
        assertEquals(ReactionBasis.PREVIOUS_CLOSE, reaction?.basis)
    }

    @Test
    fun aSampleTooFarBackIsNotTreatedAsTheMoment() {
        // Beyond the lookback the gap stops being polling jitter and becomes a different
        // time of day — the app was backgrounded, or the watch was in its midday break.
        val reaction = PriceReaction.of(
            symbol = "BEL",
            samples = samples(-minutes(40) to 400.0),
            publishedAt = published,
            lastPrice = 412.0,
            previousClose = 405.0,
        )
        assertEquals(ReactionBasis.PREVIOUS_CLOSE, reaction?.basis)
        assertEquals(1.73, reaction?.percent ?: 0.0, 0.01)
    }

    @Test
    fun withNothingToMeasureAgainstThereIsNoReaction() {
        // Null is the common case early in a session, and saying nothing is correct —
        // a fabricated baseline would be worse than an empty space on the card.
        assertNull(
            PriceReaction.of("BEL", emptyList(), published, lastPrice = 412.0)
        )
    }

    @Test
    fun aMoveTooSmallToMeanAnythingIsNotReported() {
        // Every price moves. Printing "+0.1% since this broke" implies a connection that
        // a tick does not support.
        assertNull(
            PriceReaction.of(
                symbol = "BEL",
                samples = samples(-minutes(1) to 400.0),
                publishedAt = published,
                lastPrice = 400.4,
            )
        )
    }

    @Test
    fun fallsDownwardsAsWellAsUp() {
        val reaction = PriceReaction.of(
            symbol = "BEL",
            samples = samples(-minutes(1) to 400.0),
            publishedAt = published,
            lastPrice = 388.0,
        )
        assertEquals(-3.0, reaction?.percent ?: 0.0, 0.01)
    }

    @Test
    fun aZeroOrMissingBaselineIsRefusedRatherThanDividedBy() {
        assertNull(
            PriceReaction.of("BEL", samples(-minutes(1) to 0.0), published, lastPrice = 412.0)
        )
        assertNull(
            PriceReaction.of("BEL", emptyList(), published, lastPrice = 412.0, previousClose = 0.0)
        )
        assertNull(PriceReaction.of("BEL", samples(-minutes(1) to 400.0), published, null))
    }

    @Test
    fun theStrongestMoveWins() {
        // A policy story touching four metals names is interesting because of whichever
        // one the market repriced; tag order says nothing about that.
        val chosen = PriceReaction.strongest(
            listOf(
                Reaction("A", 1.2, published, ReactionBasis.PUBLICATION),
                Reaction("B", -4.8, published, ReactionBasis.PUBLICATION),
                Reaction("C", 3.0, published, ReactionBasis.PUBLICATION),
            )
        )
        assertEquals("B", chosen?.symbol)
    }

    @Test
    fun theStrongestOfNothingIsNothing() {
        assertNull(PriceReaction.strongest(emptyList()))
    }
}
