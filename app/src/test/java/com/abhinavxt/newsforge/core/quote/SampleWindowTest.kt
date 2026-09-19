package com.abhinavxt.newsforge.core.quote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The window a sparkline is drawn over.
 *
 * Worth pinning because getting it wrong is invisible: the chart still renders, the prices
 * are all real, and the only symptom is a long flat stretch where the market was shut and
 * a publication mark in the wrong place.
 */
class SampleWindowTest {

    private val minute = 60_000L

    private fun samples(vararg offsets: Long, base: Double = 100.0): List<PriceSample> =
        offsets.mapIndexed { i, at -> PriceSample("BEL", at, base + i) }

    @Test
    fun anUnbrokenRunIsKeptWhole() {
        val run = samples(0, minute, 2 * minute, 3 * minute)
        assertEquals(run, SampleWindow.latestSession(run))
    }

    /** Everything before the overnight hole goes; the session on screen is today's. */
    @Test
    fun theRunEndsAtTheFirstLargeGap() {
        val yesterday = listOf(0L, minute, 2 * minute)
        val gap = 20L * 60 * 60 * 1000
        val today = listOf(gap, gap + minute, gap + 2 * minute)
        val kept = SampleWindow.latestSession(samples(*(yesterday + today).toLongArray()))

        assertEquals(3, kept.size)
        assertEquals(today, kept.map { it.atMillis })
    }

    /**
     * A gap inside the tolerance is not a boundary.
     *
     * A phone asleep for an hour of the session should still produce one chart of the
     * session rather than two of the halves either side.
     */
    @Test
    fun aShortGapDoesNotSplitTheSession() {
        val run = samples(0, minute, 90 * minute, 91 * minute)
        assertEquals(4, SampleWindow.latestSession(run).size)
    }

    @Test
    fun aRunThatIsAllOneGapKeepsOnlyTheNewest() {
        val day = 24L * 60 * 60 * 1000
        val kept = SampleWindow.latestSession(samples(0, day, 2 * day))
        assertEquals(1, kept.size)
        assertEquals(2 * day, kept.single().atMillis)
    }

    /** Ordered on the way in, so a reversed series does not draw the session backwards. */
    @Test
    fun samplesAreSortedBeforeTheWindowIsTaken() {
        val jumbled = samples(2 * minute, 0, minute)
        val kept = SampleWindow.latestSession(jumbled)
        assertEquals(listOf(0L, minute, 2 * minute), kept.map { it.atMillis })
    }

    @Test
    fun tooFewSamplesAreReturnedUnchanged() {
        assertEquals(emptyList<PriceSample>(), SampleWindow.latestSession(emptyList()))
        assertEquals(1, SampleWindow.latestSession(samples(0)).size)
    }
}
