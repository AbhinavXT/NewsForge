package com.abhinavxt.newsforge.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedSeedingTest {

    private val shipped = listOf("rss-a", "rss-b", "nse-announcements")

    @Test
    fun aFreshInstallGetsEverything() {
        assertEquals(
            shipped,
            FeedSeeding.missingIds(shipped, existing = emptySet(), everSeeded = emptySet()),
        )
    }

    @Test
    fun anExistingInstallGetsOnlyTheNewOnes() {
        // The bug this exists for: a table populated before the NSE feeds shipped meant
        // "seed only when empty" never ran again, and they never appeared at all.
        val missing = FeedSeeding.missingIds(
            shipped,
            existing = setOf("rss-a", "rss-b"),
            everSeeded = setOf("rss-a", "rss-b"),
        )
        assertEquals(listOf("nse-announcements"), missing)
    }

    @Test
    fun aDeletedFeedIsNotResurrected() {
        // Offered before, absent now: the user removed it and it must stay removed.
        val missing = FeedSeeding.missingIds(
            shipped,
            existing = setOf("rss-a"),
            everSeeded = setOf("rss-a", "rss-b", "nse-announcements"),
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun aFeedStillPresentIsNotReinserted() {
        // Reinserting would overwrite an edited URL or a disabled switch with the default.
        val missing = FeedSeeding.missingIds(
            shipped,
            existing = shipped.toSet(),
            everSeeded = emptySet(),
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun theOfferedSetRecordsEverythingShippedNotJustWhatWasInserted() {
        // On an install predating this bookkeeping, the table is the only evidence of what
        // was offered before; recording the whole shipped set is what stops the next
        // upgrade re-offering feeds already seen and removed.
        val after = FeedSeeding.seededAfter(shipped, everSeeded = emptySet())
        assertEquals(shipped.toSet(), after)
    }

    @Test
    fun theOfferedSetOnlyGrows() {
        val after = FeedSeeding.seededAfter(shipped, everSeeded = setOf("retired-feed"))
        assertTrue("retired-feed" in after)
        assertTrue(after.containsAll(shipped))
    }

    @Test
    fun seedingIsIdempotentAcrossTwoRuns() {
        var everSeeded = emptySet<String>()
        val existing = HashSet<String>()

        val first = FeedSeeding.missingIds(shipped, existing, everSeeded)
        existing += first
        everSeeded = FeedSeeding.seededAfter(shipped, everSeeded)

        val second = FeedSeeding.missingIds(shipped, existing, everSeeded)
        assertEquals(shipped, first)
        assertTrue(second.isEmpty())
    }

    @Test
    fun everyShippedFeedHasAUniqueIdSoSeedingCannotCollide() {
        val ids = DefaultFeeds.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun theNseFeedsAreAmongTheShippedDefaults() {
        // If these ever drop out of ALL, seeding silently stops offering them again.
        val ids = DefaultFeeds.ALL.map { it.id }
        assertTrue(DefaultFeeds.NSE_FEEDS.all { it.id in ids })
    }
}
