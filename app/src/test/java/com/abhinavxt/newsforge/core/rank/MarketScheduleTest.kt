package com.abhinavxt.newsforge.core.rank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class MarketScheduleTest {

    private fun ist(text: String): Long =
        OffsetDateTime.parse("${text}+05:30").toInstant().toEpochMilli()

    // 2026-09-09 Wednesday, 09-11 Friday, 09-12 Saturday, 09-14 Monday.

    @Test
    fun theWindowOpensBeforeTheBellAndClosesAfterIt() {
        // 09:00 catches the pre-open gap list; 15:45 catches the first wave of results
        // and board-meeting outcomes, which land within minutes of the close.
        assertFalse(MarketSchedule.isSessionActive(ist("2026-09-09T08:59:00")))
        assertTrue(MarketSchedule.isSessionActive(ist("2026-09-09T09:00:00")))
        assertTrue(MarketSchedule.isSessionActive(ist("2026-09-09T15:40:00")))
        assertFalse(MarketSchedule.isSessionActive(ist("2026-09-09T15:45:00")))
    }

    @Test
    fun weekendsAreNeverActive() {
        assertFalse(MarketSchedule.isSessionActive(ist("2026-09-12T11:00:00")))
        assertFalse(MarketSchedule.isSessionActive(ist("2026-09-13T11:00:00")))
    }

    @Test
    fun beforeTheOpenTheNextSessionIsToday() {
        assertEquals(
            ist("2026-09-09T09:00:00"),
            MarketSchedule.nextSessionStartMillis(ist("2026-09-09T06:30:00")),
        )
    }

    @Test
    fun duringASessionTheStartIsNowSoADelayIsNeverNegative() {
        val now = ist("2026-09-09T11:00:00")
        assertEquals(now, MarketSchedule.nextSessionStartMillis(now))
        assertEquals(0L, MarketSchedule.millisUntilNextSession(now))
    }

    @Test
    fun afterTheCloseTheNextSessionIsTomorrow() {
        assertEquals(
            ist("2026-09-10T09:00:00"),
            MarketSchedule.nextSessionStartMillis(ist("2026-09-09T18:00:00")),
        )
    }

    @Test
    fun fridayEveningRollsToMonday() {
        assertEquals(
            ist("2026-09-14T09:00:00"),
            MarketSchedule.nextSessionStartMillis(ist("2026-09-11T18:00:00")),
        )
    }

    @Test
    fun saturdayAndSundayBothRollToMonday() {
        val monday = ist("2026-09-14T09:00:00")
        assertEquals(monday, MarketSchedule.nextSessionStartMillis(ist("2026-09-12T11:00:00")))
        assertEquals(monday, MarketSchedule.nextSessionStartMillis(ist("2026-09-13T23:00:00")))
    }

    @Test
    fun theEndIsTheSameDayAsTheStart() {
        assertEquals(
            ist("2026-09-14T15:45:00"),
            MarketSchedule.sessionEndMillis(ist("2026-09-11T18:00:00")),
        )
        assertEquals(
            ist("2026-09-09T15:45:00"),
            MarketSchedule.sessionEndMillis(ist("2026-09-09T11:00:00")),
        )
    }

    @Test
    fun theDelayIsNeverNegativeAtAnyHour() {
        // A negative delay would make WorkManager run immediately, every time.
        for (hour in 0..23) {
            val now = ist(String.format("2026-09-11T%02d:30:00", hour))
            assertTrue(MarketSchedule.millisUntilNextSession(now) >= 0)
        }
    }

    @Test
    fun theWatchWindowContainsTheTradingSession() {
        // If it did not, the watch would be idle while prices were moving.
        assertTrue(MarketSchedule.WATCH_START <= MarketClock.OPEN_TIME)
        assertTrue(MarketSchedule.WATCH_END > MarketClock.CLOSE_TIME)
    }
}
