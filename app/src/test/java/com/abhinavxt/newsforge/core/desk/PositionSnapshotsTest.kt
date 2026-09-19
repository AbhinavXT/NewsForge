package com.abhinavxt.newsforge.core.desk

import com.abhinavxt.newsforge.core.notify.WatchTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionSnapshotsTest {

    private fun record(vararg pairs: Pair<String, String?>): Map<String, String?> =
        mapOf("nf" to "1", "kind" to "positions", *pairs)

    @Test
    fun readsEachTierFromItsOwnField() {
        val snapshot = PositionSnapshots.fromFlat(
            record(
                "ts" to "1789600000",
                "leveraged" to "RELIANCE,INFY",
                "holding" to "TATASTEEL",
                "watching" to "BEL",
            )
        )
        assertEquals(
            mapOf(
                "RELIANCE" to WatchTier.LEVERAGED,
                "INFY" to WatchTier.LEVERAGED,
                "TATASTEEL" to WatchTier.HOLDING,
                "BEL" to WatchTier.WATCHING,
            ),
            snapshot?.tiers,
        )
        assertEquals(1789600000L * 1000, snapshot?.takenAtMillis)
    }

    @Test
    fun aSymbolInTwoTiersTakesTheStrongerOne() {
        // Genuinely happens: part of a holding carried on margin shows up in both the
        // delivery and the intraday book.
        val snapshot = PositionSnapshots.fromFlat(
            record("holding" to "INFY", "leveraged" to "INFY")
        )
        assertEquals(WatchTier.LEVERAGED, snapshot?.tiers?.get("INFY"))
    }

    @Test
    fun symbolsAreTrimmedAndUppercased() {
        val snapshot = PositionSnapshots.fromFlat(record("holding" to " infy , tatasteel ,,"))
        assertEquals(
            setOf("INFY", "TATASTEEL"),
            snapshot?.tiers?.keys,
        )
    }

    @Test
    fun anEmptyFieldIsAFlatBookAndNotAMalformedMessage() {
        // The distinction that matters most: a day with nothing open has to be able to
        // clear yesterday's positions. Treating it as junk would leave the app alerting
        // at leveraged volume on something already sold.
        val snapshot = PositionSnapshots.fromFlat(record("holding" to ""))
        assertTrue(snapshot != null && snapshot.isEmpty)
    }

    @Test
    fun aMessageWithNoTierFieldsIsNotASnapshot() {
        assertNull(PositionSnapshots.fromFlat(record("note" to "hello")))
    }

    @Test
    fun quotesAreNotSnapshots() {
        assertNull(
            PositionSnapshots.fromFlat(
                mapOf("nf" to "1", "kind" to "quote", "symbol" to "INFY", "ltp" to "1500")
            )
        )
    }

    @Test
    fun anUnknownSchemaVersionIsIgnoredRatherThanGuessedAt() {
        assertNull(
            PositionSnapshots.fromFlat(
                mapOf("nf" to "2", "kind" to "positions", "holding" to "INFY")
            )
        )
        assertNull(PositionSnapshots.fromFlat(mapOf("kind" to "positions", "holding" to "INFY")))
    }

    @Test
    fun aMissingTimestampIsNullRatherThanZero() {
        // The caller substitutes its own clock. Zero would date every position to 1970
        // and make any staleness check pass.
        assertNull(PositionSnapshots.fromFlat(record("holding" to "INFY"))?.takenAtMillis)
    }

    @Test
    fun weightsAreReadForSymbolsTheSnapshotAlsoHolds() {
        val snapshot = PositionSnapshots.fromFlat(
            record("holding" to "BEL,HCLTECH", "w.BEL" to "6.4", "w.HCLTECH" to "0.8")
        )
        assertEquals(mapOf("BEL" to 6.4, "HCLTECH" to 0.8), snapshot?.weights)
    }

    @Test
    fun aWeightForSomethingNotHeldIsDropped() {
        // The same message says you do not hold it; storing a size against nothing would
        // leave a stale number for the alert rules to act on.
        val snapshot = PositionSnapshots.fromFlat(
            record("holding" to "BEL", "w.RELIANCE" to "4.0")
        )
        assertEquals(0, snapshot?.weights?.size ?: -1)
    }

    @Test
    fun weightsAreOptional() {
        // A desk that sends none still reports exposure; it simply cannot tell a large
        // position from a small one.
        val snapshot = PositionSnapshots.fromFlat(record("holding" to "BEL"))
        assertTrue(snapshot?.weights?.isEmpty() == true)
    }
}
