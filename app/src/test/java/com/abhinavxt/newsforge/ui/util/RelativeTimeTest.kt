package com.abhinavxt.newsforge.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class RelativeTimeTest {

    private fun ist(text: String): Long =
        OffsetDateTime.parse("${text}+05:30").toInstant().toEpochMilli()

    private val now = ist("2026-09-09T10:30:00")
    private val minute = 60_000L

    @Test
    fun freshItemsReadAsMinutes() {
        assertEquals("now", RelativeTime.format(now, now))
        assertEquals("4m", RelativeTime.format(now - 4 * minute, now))
        assertEquals("59m", RelativeTime.format(now - 59 * minute, now))
    }

    @Test
    fun thenHours() {
        assertEquals("1h", RelativeTime.format(now - 60 * minute, now))
        assertEquals("3h", RelativeTime.format(now - 200 * minute, now))
    }

    @Test
    fun olderThanHalfADaySwitchesToAClockTime() {
        // "19h" is harder to place than a clock reading once you cross a session boundary.
        assertEquals("Yesterday 15:40", RelativeTime.format(ist("2026-09-08T15:40:00"), now))
    }

    @Test
    fun sameDayEarlyMorningUsesAClockTimeWithoutADayLabel() {
        val earlyToday = ist("2026-09-09T22:10:00")
        val lateSameDay = ist("2026-09-09T09:00:00")
        assertEquals("09:00", RelativeTime.format(lateSameDay, earlyToday))
    }

    @Test
    fun severalDaysBackIncludesTheDate() {
        assertEquals("5 Sep, 11:20", RelativeTime.format(ist("2026-09-05T11:20:00"), now))
    }

    @Test
    fun clockSkewNeverRendersANegativeAge() {
        assertEquals("now", RelativeTime.format(now + 5 * minute, now))
    }

    @Test
    fun bylineNamesTheOutletAndCountsTheRest() {
        assertEquals("Mint · 12m", RelativeTime.byline("Mint", 0, now - 12 * minute, now))
        assertEquals("Mint +3 · 12m", RelativeTime.byline("Mint", 3, now - 12 * minute, now))
    }
}
