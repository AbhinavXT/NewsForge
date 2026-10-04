package com.abhinavxt.newsforge.core.quote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class EarningsAndComparisonTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(date: String, time: String = "09:15"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun bar(date: String, close: Double) = Candle(at(date), close, close, close, close, 1.0)

    private val bars = listOf(
        bar("2026-07-20", 100.0),
        bar("2026-07-21", 100.0), // results after the bell this day
        bar("2026-07-22", 110.0), // first session that could react
        bar("2026-07-23", 108.0),
        bar("2026-07-24", 106.0),
        bar("2026-07-27", 104.0),
        bar("2026-07-28", 105.0), // fifth session from the 22nd
    )

    @Test
    fun aResultAfterTheBellIsMeasuredFromThatCloseToTheNextSessions() {
        val events = EarningsHistory.of(
            listOf(
                ResultsStory(at("2026-07-21", "16:05"), "Q1 profit up 20%", "Moneycontrol"),
                ResultsStory(at("2026-07-22", "08:00"), "Q1: margins beat", "ET"),
            ),
            bars,
            zone,
        )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("Q1 profit up 20%", event.title)
        assertEquals(2, event.storyCount)
        assertEquals(10.0, event.dayMovePercent!!, 1e-9)
        assertEquals(5.0, event.weekMovePercent!!, 1e-9)
    }

    @Test
    fun aResultDuringTheSessionReactsTheSameDay() {
        val event = EarningsHistory.of(
            listOf(ResultsStory(at("2026-07-22", "11:30"), "Q1", "ET")),
            bars,
            zone,
        ).single()

        // Base is the 21st's close; the 22nd itself is the reaction.
        assertEquals(10.0, event.dayMovePercent!!, 1e-9)
    }

    @Test
    fun separateQuartersAreSeparateEventsNewestFirst_andMissingBarsSayNothing() {
        val events = EarningsHistory.of(
            listOf(
                ResultsStory(at("2026-04-20", "17:00"), "Q4", "ET"),
                ResultsStory(at("2026-07-21", "17:00"), "Q1", "ET"),
            ),
            bars,
            zone,
        )

        assertEquals(listOf("Q1", "Q4"), events.map { it.title })
        assertNull(events[1].dayMovePercent)
        assertNull(events[1].weekMovePercent)
    }

    @Test
    fun comparisonIsRebasedToStartTogetherAndHoldsThroughGaps() {
        val main = listOf(bar("2026-07-20", 200.0), bar("2026-07-21", 210.0), bar("2026-07-22", 220.0))
        val other = listOf(bar("2026-07-20", 50.0), bar("2026-07-22", 55.0)) // no bar on the 21st

        val line = Comparison.rebased(main, other)

        assertEquals(listOf(200.0, 200.0, 220.0), line)
        assertEquals(10.0, Comparison.lineChangePercent(line)!!, 1e-9)
        assertEquals(10.0, Comparison.changePercent(main)!!, 1e-9)
    }

    @Test
    fun comparisonStartsWhereTheOtherSeriesDoes() {
        val main = listOf(bar("2026-07-20", 200.0), bar("2026-07-21", 210.0), bar("2026-07-22", 231.0))
        val other = listOf(bar("2026-07-21", 50.0), bar("2026-07-22", 60.0))

        assertEquals(listOf(null, 210.0, 252.0), Comparison.rebased(main, other))
        assertEquals(main.map { null }, Comparison.rebased(main, emptyList()))
    }
}
