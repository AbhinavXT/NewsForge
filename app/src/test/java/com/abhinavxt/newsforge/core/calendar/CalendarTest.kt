package com.abhinavxt.newsforge.core.calendar

import com.abhinavxt.newsforge.core.feed.FeedKind
import com.abhinavxt.newsforge.core.feed.NseFilings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class CalendarExtractionTest {

    private fun event(kind: FeedKind, record: Map<String, String?>) =
        NseFilings.calendarEvent(kind, record, "nse")

    @Test
    fun boardMeetingsForResultsAreTypedAsResults() {
        val e = event(
            FeedKind.NSE_BOARD_MEETING,
            mapOf(
                "bm_symbol" to "reliance",
                "bm_purpose" to "To consider unaudited financial results",
                "bm_date" to "18-Sep-2026",
            ),
        )!!
        assertEquals("RELIANCE", e.symbol)
        assertEquals(EventType.RESULTS, e.type)
    }

    @Test
    fun otherBoardMeetingsKeepTheirOwnType() {
        val e = event(
            FeedKind.NSE_BOARD_MEETING,
            mapOf(
                "bm_symbol" to "ITC",
                "bm_purpose" to "To consider raising of funds",
                "bm_date" to "18-Sep-2026",
            ),
        )!!
        assertEquals(EventType.BOARD_MEETING, e.type)
    }

    @Test
    fun corporateActionsSplitOnDividend() {
        val dividend = event(
            FeedKind.NSE_CORP_ACTION,
            mapOf("symbol" to "ITC", "subject" to "Dividend Rs 6.25", "exDate" to "12-Sep-2026"),
        )!!
        assertEquals(EventType.DIVIDEND, dividend.type)

        val bonus = event(
            FeedKind.NSE_CORP_ACTION,
            mapOf("symbol" to "INFY", "subject" to "Bonus 1:1", "exDate" to "12-Sep-2026"),
        )!!
        assertEquals(EventType.CORPORATE_ACTION, bonus.type)
    }

    @Test
    fun exDateIsPreferredOverRecordDate() {
        // Ex-date is what decides whether holding through the date entitles you to the
        // action; record date is only the fallback.
        val e = event(
            FeedKind.NSE_CORP_ACTION,
            mapOf(
                "symbol" to "ITC",
                "subject" to "Dividend",
                "exDate" to "12-Sep-2026",
                "recDate" to "15-Sep-2026",
            ),
        )!!
        assertEquals(
            OffsetDateTime.parse("2026-09-12T00:00:00+05:30").toInstant().toEpochMilli(),
            e.dateMillis,
        )
    }

    @Test
    fun materialityIsNotAppliedToCalendarEvents() {
        // A board meeting about a fundraise is not news yet, but it is absolutely a date
        // you want before holding over it.
        val e = event(
            FeedKind.NSE_BOARD_MEETING,
            mapOf(
                "bm_symbol" to "XYZ",
                "bm_purpose" to "Change of registered office",
                "bm_date" to "18-Sep-2026",
            ),
        )
        assertTrue(e != null)
    }

    @Test
    fun rowsWithNoUsableDateAreSkipped() {
        assertNull(
            event(
                FeedKind.NSE_BOARD_MEETING,
                mapOf("bm_symbol" to "XYZ", "bm_purpose" to "Results", "bm_date" to "soon"),
            )
        )
        assertNull(event(FeedKind.NSE_ANNOUNCEMENT, mapOf("symbol" to "X", "desc" to "Results")))
    }

    @Test
    fun aRescheduledMeetingIsADifferentEvent() {
        // The id includes the date, so moving a meeting produces a new row rather than
        // silently overwriting the old one with a date nobody was told about.
        val base = mapOf("bm_symbol" to "ITC", "bm_purpose" to "Results")
        val first = event(FeedKind.NSE_BOARD_MEETING, base + ("bm_date" to "18-Sep-2026"))!!
        val moved = event(FeedKind.NSE_BOARD_MEETING, base + ("bm_date" to "22-Sep-2026"))!!
        assertTrue(first.id != moved.id)
    }
}

class CalendarGroupingTest {

    private fun ist(text: String): Long =
        OffsetDateTime.parse("${text}+05:30").toInstant().toEpochMilli()

    private val now = ist("2026-09-09T10:30:00")

    private fun ev(symbol: String, day: String, type: EventType = EventType.RESULTS) =
        UpcomingEvent(
            id = "$symbol-$day",
            symbol = symbol,
            type = type,
            title = type.label,
            dateMillis = ist("${day}T00:00:00"),
            sourceFeedId = "nse",
        )

    @Test
    fun bucketsFollowCalendarDaysNotElapsedHours() {
        // 23:00 tonight is "today" even though it is 12 hours away; 06:00 tomorrow is
        // "tomorrow" even though it is closer to 19.
        assertEquals(CalendarBucket.TODAY, CalendarGrouping.bucketOf(ist("2026-09-09T23:00:00"), now))
        assertEquals(
            CalendarBucket.TOMORROW,
            CalendarGrouping.bucketOf(ist("2026-09-10T06:00:00"), now),
        )
        assertEquals(CalendarBucket.THIS_WEEK, CalendarGrouping.bucketOf(ist("2026-09-15T00:00:00"), now))
        assertEquals(CalendarBucket.LATER, CalendarGrouping.bucketOf(ist("2026-09-30T00:00:00"), now))
        assertEquals(CalendarBucket.PAST, CalendarGrouping.bucketOf(ist("2026-09-08T23:59:00"), now))
    }

    @Test
    fun sectionsAreChronologicalAndPastIsDropped() {
        val sections = CalendarGrouping.group(
            listOf(
                ev("D", "2026-09-30"),
                ev("A", "2026-09-09"),
                ev("C", "2026-09-14"),
                ev("B", "2026-09-10"),
                ev("OLD", "2026-09-01"),
            ),
            now,
        )
        assertEquals(
            listOf(CalendarBucket.TODAY, CalendarBucket.TOMORROW, CalendarBucket.THIS_WEEK, CalendarBucket.LATER),
            sections.map { it.bucket },
        )
        assertTrue(sections.flatMap { it.events }.none { it.symbol == "OLD" })
    }

    @Test
    fun eventsBeyondTheHorizonAreDropped() {
        val far = ev("FAR", "2026-12-31")
        assertTrue(CalendarGrouping.group(listOf(far), now).isEmpty())
    }

    @Test
    fun followedOnlyNarrowsToTheWatchlist() {
        val events = listOf(ev("INFY", "2026-09-10"), ev("XYZ", "2026-09-10"))
        val mine = CalendarGrouping.group(events, now, setOf("INFY"), followedOnly = true)
        assertEquals(listOf("INFY"), mine.flatMap { it.events }.map { it.symbol })
        assertEquals(2, CalendarGrouping.group(events, now).flatMap { it.events }.size)
    }

    @Test
    fun withinABucketOrderIsDateThenSymbol() {
        val sections = CalendarGrouping.group(
            listOf(ev("ZZZ", "2026-09-12"), ev("AAA", "2026-09-12"), ev("MMM", "2026-09-11")),
            now,
        )
        assertEquals(
            listOf("MMM", "AAA", "ZZZ"),
            sections.single().events.map { it.symbol },
        )
    }

    @Test
    fun nextForFindsTheSoonestFutureEvent() {
        val events = listOf(
            ev("INFY", "2026-09-20"),
            ev("INFY", "2026-09-11"),
            ev("INFY", "2026-09-01"),
            ev("TCS", "2026-09-10"),
        )
        assertEquals("INFY-2026-09-11", CalendarGrouping.nextFor("INFY", events, now)?.id)
        assertNull(CalendarGrouping.nextFor("WIPRO", events, now))
    }

    @Test
    fun emptyInputProducesNoSections() {
        assertTrue(CalendarGrouping.group(emptyList(), now).isEmpty())
    }
}
