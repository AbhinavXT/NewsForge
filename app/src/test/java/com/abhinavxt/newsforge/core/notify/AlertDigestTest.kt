package com.abhinavxt.newsforge.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDigestTest {

    private fun alert(
        title: String,
        symbols: List<String> = emptyList(),
        followed: Boolean = true,
        postedAt: Long = 0L,
    ) = ShadeAlert(title, symbols, followed, postedAt)

    @Test
    fun emptyShadeHasNoSummary() {
        assertNull(AlertDigests.of(AlertLevel.HIGH, emptyList()))
    }

    @Test
    fun countsEverythingInTheShadeNotJustTheBatch() {
        val digest = AlertDigests.of(
            AlertLevel.HIGH,
            List(4) { alert("Story $it", postedAt = it.toLong()) },
        )!!
        assertEquals("4 updates on your watchlist", digest.title)
        assertEquals(0, digest.more)
    }

    @Test
    fun regulatoryAlertOnAnUnfollowedNameIsNotCalledAWatchlistUpdate() {
        val digest = AlertDigests.of(
            AlertLevel.HIGH,
            listOf(alert("Results"), alert("SEBI order", followed = false)),
        )!!
        assertEquals("2 watchlist and regulatory alerts", digest.title)
    }

    @Test
    fun marketLevelIsCountedAsStories() {
        assertEquals(
            "1 market story",
            AlertDigests.of(AlertLevel.NORMAL, listOf(alert("x", followed = false)))!!.title,
        )
        assertEquals(
            "3 market stories",
            AlertDigests.of(AlertLevel.NORMAL, List(3) { alert("x", followed = false) })!!.title,
        )
    }

    @Test
    fun newestFirstAndCappedWithOverflow() {
        val digest = AlertDigests.of(
            AlertLevel.HIGH,
            List(7) { alert("Story $it", postedAt = it.toLong()) },
        )!!
        assertEquals(AlertDigests.MAX_LINES, digest.lines.size)
        assertEquals("Story 6", digest.lines.first())
        assertEquals(2, digest.more)
    }

    @Test
    fun lineLeadsWithAtMostTwoSymbols() {
        assertEquals(
            "INFY TCS · IT deal",
            AlertDigests.line(alert("IT deal", symbols = listOf("INFY", "TCS", "WIPRO"))),
        )
        assertEquals("Plain", AlertDigests.line(alert("Plain")))
    }

    @Test
    fun onlyABurstRingsOnTheSummary() {
        assertFalse(AlertDigests.ringsOnSummary(1))
        assertTrue(AlertDigests.ringsOnSummary(2))
    }
}
