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
            // Inside the morning segment, so its end — not the end of the whole day.
            ist("2026-09-09T12:00:00"),
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
    fun theMiddayGapIsOutsideTheWatchAndResolvesToTheAfternoon() {
        // The lunch lull is dropped so the day fits inside Android's foreground-service
        // budget. Dropping it must send the worker to 13:30 today, not 09:00 tomorrow.
        assertFalse(MarketSchedule.isSessionActive(ist("2026-09-09T12:00:00")))
        assertFalse(MarketSchedule.isSessionActive(ist("2026-09-09T12:45:00")))
        assertTrue(MarketSchedule.isSessionActive(ist("2026-09-09T13:30:00")))
        assertEquals(
            ist("2026-09-09T13:30:00"),
            MarketSchedule.nextSessionStartMillis(ist("2026-09-09T12:45:00")),
        )
    }

    @Test
    fun theScheduleFitsInsideTheForegroundServiceBudget() {
        // The one test standing between an edit to SEGMENTS and the system killing the
        // watch mid-afternoon. Headroom on purpose: the cap counts time the service was
        // actually up, and a restart after a crash spends more than the schedule implies.
        assertTrue(MarketSchedule.scheduledMinutes < MarketSchedule.FOREGROUND_BUDGET_MINUTES)
        assertTrue(MarketSchedule.scheduledMinutes <= MarketSchedule.FOREGROUND_BUDGET_MINUTES - 30)
    }

    @Test
    fun segmentsAreOrderedAndDoNotOverlap() {
        // nextSessionStartMillis returns the first future start it finds, so an
        // out-of-order list would silently resolve to the wrong one.
        val segments = MarketSchedule.SEGMENTS
        assertTrue(segments.isNotEmpty())
        for (i in 1 until segments.size) {
            assertTrue(segments[i - 1].end <= segments[i].start)
        }
        for (segment in segments) assertTrue(segment.start < segment.end)
    }

    @Test
    fun theWatchWindowContainsTheTradingSession() {
        // If it did not, the watch would be idle while prices were moving.
        assertTrue(MarketSchedule.WATCH_START <= MarketClock.OPEN_TIME)
        assertTrue(MarketSchedule.WATCH_END > MarketClock.CLOSE_TIME)
    }
}
