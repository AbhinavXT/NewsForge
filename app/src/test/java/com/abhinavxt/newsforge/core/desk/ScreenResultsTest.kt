package com.abhinavxt.newsforge.core.desk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenResultsTest {

    private fun record(vararg pairs: Pair<String, String?>): Map<String, String?> =
        mapOf("nf" to "1", "kind" to "screen", *pairs)

    @Test
    fun readsEachScreenAsItsOwnList() {
        val screens = ScreenResults.fromFlat(
            record(
                "ts" to "1789600000",
                "s.Momentum" to "BEL,HCLTECH,SBIN",
                "s.Breakdown" to "TATASTEEL",
            )
        )
        assertEquals(listOf("Breakdown", "Momentum"), screens.map { it.name })
        assertEquals(listOf("BEL", "HCLTECH", "SBIN"), screens.last().symbols)
        assertEquals(1789600000L * 1000, screens.first().takenAtMillis)
    }

    @Test
    fun namesKeepTheSendersCasing() {
        // They are labels on a chip, not identifiers to match on.
        assertEquals("Momentum", ScreenResults.fromFlat(record("s.Momentum" to "BEL")).single().name)
    }

    @Test
    fun symbolsAreNormalisedAndDeduplicated() {
        val screen = ScreenResults.fromFlat(
            record("s.X" to " bel , BEL ,hcltech,, ")
        ).single()
        assertEquals(listOf("BEL", "HCLTECH"), screen.symbols)
    }

    @Test
    fun anEmptyRunIsAnAnswerRatherThanAMalformedMessage() {
        // "Nothing passed this morning" is worth showing. Dropping it would make the
        // screener look like it had stopped running.
        val screen = ScreenResults.fromFlat(record("s.Momentum" to "")).single()
        assertTrue(screen.isEmpty)
        assertEquals("Momentum", screen.name)
    }

    @Test
    fun otherKindsAreNotScreens() {
        assertTrue(
            ScreenResults.fromFlat(
                mapOf("nf" to "1", "kind" to "positions", "holding" to "BEL")
            ).isEmpty()
        )
        assertTrue(
            ScreenResults.fromFlat(
                mapOf("nf" to "1", "kind" to "quotes", "q.BEL.ltp" to "412")
            ).isEmpty()
        )
    }

    @Test
    fun anUnknownSchemaVersionIsIgnoredRatherThanGuessedAt() {
        assertTrue(
            ScreenResults.fromFlat(mapOf("nf" to "2", "kind" to "screen", "s.X" to "BEL")).isEmpty()
        )
    }

    @Test
    fun aMessageWithNoScreensYieldsNone() {
        assertTrue(ScreenResults.fromFlat(record("note" to "hello")).isEmpty())
    }
}
