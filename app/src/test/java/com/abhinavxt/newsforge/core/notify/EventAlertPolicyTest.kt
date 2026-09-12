package com.abhinavxt.newsforge.core.notify

import com.abhinavxt.newsforge.core.calendar.EventType
import com.abhinavxt.newsforge.core.calendar.UpcomingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class EventAlertPolicyTest {

    private fun ist(text: String): Long =
        OffsetDateTime.parse("${text}+05:30").toInstant().toEpochMilli()

    /** 2026-09-09 is a Wednesday; 10:30 is mid-session and outside quiet hours. */
    private val now = ist("2026-09-09T10:30:00")

    private fun event(
        symbol: String,
        day: String,
        type: EventType = EventType.RESULTS,
    ) = UpcomingEvent(
        id = "$symbol-$day-${type.name}",
        symbol = symbol,
        type = type,
        title = type.label,
        dateMillis = ist("${day}T00:00:00"),
        sourceFeedId = "nse",
    )

    private fun select(
        events: List<UpcomingEvent>,
        tiers: Map<String, WatchTier>,
        alreadyNotified: Set<String> = emptySet(),
        settings: AlertSettings = AlertSettings(),
        nowMillis: Long = now,
    ) = EventAlertPolicy.select(events, tiers, alreadyNotified, settings, nowMillis)

    @Test
    fun onlyFollowedSymbolsProduceAlerts() {
        // The exchange schedules thousands of results dates a season; alerting on a
        // company you have no position in is indistinguishable from noise.
        val events = listOf(event("INFY", "2026-09-09"), event("XYZ", "2026-09-09"))
        val alerts = select(events, mapOf("INFY" to WatchTier.WATCHING))
        assertEquals(listOf("INFY"), alerts.map { it.event.symbol })
    }

    @Test
    fun leadTimesScaleWithExposure() {
        val threeDaysOut = listOf(event("INFY", "2026-09-12"))
        assertTrue(select(threeDaysOut, mapOf("INFY" to WatchTier.WATCHING)).isEmpty())
        assertTrue(select(threeDaysOut, mapOf("INFY" to WatchTier.HOLDING)).isEmpty())
        // Leveraged gets enough notice to reduce before the event, not react after it.
        assertEquals(1, select(threeDaysOut, mapOf("INFY" to WatchTier.LEVERAGED)).size)
    }

    @Test
    fun everyTierIsToldOnTheDay() {
        val today = listOf(event("INFY", "2026-09-09"))
        for (tier in WatchTier.entries) {
            assertEquals(1, select(today, mapOf("INFY" to tier)).size)
        }
    }

    @Test
    fun holdingIsWarnedTheNightBeforeButWatchingIsNot() {
        val tomorrow = listOf(event("INFY", "2026-09-10"))
        assertTrue(select(tomorrow, mapOf("INFY" to WatchTier.WATCHING)).isEmpty())
        assertEquals(1, select(tomorrow, mapOf("INFY" to WatchTier.HOLDING)).size)
    }

    @Test
    fun nonLeadDaysAreSilent() {
        // Two days out is nobody's lead, so nothing fires until it becomes one.
        val twoDaysOut = listOf(event("INFY", "2026-09-11"))
        for (tier in WatchTier.entries) {
            assertTrue(select(twoDaysOut, mapOf("INFY" to tier)).isEmpty())
        }
    }

    @Test
    fun pastEventsNeverFire() {
        val yesterday = listOf(event("INFY", "2026-09-08"))
        assertTrue(select(yesterday, mapOf("INFY" to WatchTier.LEVERAGED)).isEmpty())
    }

    @Test
    fun boardMeetingsOnlyMatterWhenLeveraged() {
        val meeting = listOf(event("INFY", "2026-09-09", EventType.BOARD_MEETING))
        assertTrue(select(meeting, mapOf("INFY" to WatchTier.WATCHING)).isEmpty())
        assertTrue(select(meeting, mapOf("INFY" to WatchTier.HOLDING)).isEmpty())
        assertEquals(1, select(meeting, mapOf("INFY" to WatchTier.LEVERAGED)).size)
    }

    @Test
    fun eachLeadFiresOnceAndOnlyOnce() {
        val results = listOf(event("INFY", "2026-09-10"))
        val alert = select(results, mapOf("INFY" to WatchTier.HOLDING)).single()
        // Same event, same lead, already sent.
        assertTrue(select(results, mapOf("INFY" to WatchTier.HOLDING), setOf(alert.key)).isEmpty())
    }

    @Test
    fun aLaterLeadStillFiresAfterAnEarlierOneWasSent() {
        // A leveraged holder gets three-days, one-day and same-day warnings for one date;
        // sending the first must not swallow the rest.
        val results = event("INFY", "2026-09-12")
        val tiers = mapOf("INFY" to WatchTier.LEVERAGED)
        val threeDays = select(listOf(results), tiers).single()
        assertEquals(3L, threeDays.daysAway)

        val dayBefore = select(listOf(results), tiers, setOf(threeDays.key), nowMillis = ist("2026-09-11T08:00:00"))
        assertEquals(1L, dayBefore.single().daysAway)
    }

    @Test
    fun soonestFirstThenExposureWhenTheCapBites() {
        val events = listOf(
            event("FAR", "2026-09-12"),
            event("NEARWATCH", "2026-09-09"),
            event("NEARLEV", "2026-09-09"),
        )
        val alerts = select(
            events,
            mapOf(
                "FAR" to WatchTier.LEVERAGED,
                "NEARWATCH" to WatchTier.WATCHING,
                "NEARLEV" to WatchTier.LEVERAGED,
            ),
        )
        assertEquals(listOf("NEARLEV", "NEARWATCH", "FAR"), alerts.map { it.event.symbol })
    }

    @Test
    fun quietHoursAndTheMasterSwitchBothSuppress() {
        val today = listOf(event("INFY", "2026-09-09"))
        val tiers = mapOf("INFY" to WatchTier.LEVERAGED)
        assertTrue(select(today, tiers, nowMillis = ist("2026-09-09T02:00:00")).isEmpty())
        assertTrue(select(today, tiers, settings = AlertSettings(enabled = false)).isEmpty())
    }

    @Test
    fun headlinesReadNaturally() {
        val tiers = mapOf("ITC" to WatchTier.HOLDING)
        val exToday = select(
            listOf(event("ITC", "2026-09-09", EventType.DIVIDEND)), tiers
        ).single()
        assertEquals("ITC — goes ex-dividend today", exToday.headline())

        val resultsTomorrow = select(listOf(event("ITC", "2026-09-10")), tiers).single()
        assertEquals("ITC — results tomorrow", resultsTomorrow.headline())
    }

    @Test
    fun watchingIsQuieterThanExposure() {
        assertEquals(AlertLevel.NORMAL, EventAlert(event("A", "2026-09-09"), WatchTier.WATCHING, 0).level)
        assertEquals(AlertLevel.HIGH, EventAlert(event("A", "2026-09-09"), WatchTier.LEVERAGED, 0).level)
    }

    @Test
    fun emptyInputProducesNothing() {
        assertTrue(select(emptyList(), mapOf("INFY" to WatchTier.LEVERAGED)).isEmpty())
    }
}
