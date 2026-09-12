package com.abhinavxt.newsforge.core.rank

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When the live watch should be running.
 *
 * A slightly wider window than the trading session itself. It opens at 09:00 because the
 * fifteen minutes before the bell are when the gap list is finalised, and closes at 15:45
 * because results and board-meeting outcomes start landing within minutes of the close —
 * stopping at 15:30 exactly would miss the first wave of the thing you most want.
 */
object MarketSchedule {

    val WATCH_START: LocalTime = LocalTime.of(9, 0)
    val WATCH_END: LocalTime = LocalTime.of(15, 45)

    fun isSessionActive(nowMillis: Long, zone: ZoneId = MarketClock.ZONE): Boolean {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        if (!isTradingDay(now)) return false
        val time = now.toLocalTime()
        return time >= WATCH_START && time < WATCH_END
    }

    /**
     * Start of the session the watch is currently in, or the next one.
     *
     * Returns "now" rather than a past instant when a session is already running, so a
     * caller scheduling with a delay does not compute a negative one.
     */
    fun nextSessionStartMillis(nowMillis: Long, zone: ZoneId = MarketClock.ZONE): Long {
        if (isSessionActive(nowMillis, zone)) return nowMillis

        var day = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        // Today still counts if the window has not opened yet; otherwise roll forward.
        if (!isTradingDay(day) || day.toLocalTime() >= WATCH_END) {
            day = day.plusDays(1)
        }
        repeat(MAX_LOOKAHEAD_DAYS) {
            if (isTradingDay(day)) {
                val start = day.with(WATCH_START)
                if (start.toInstant().toEpochMilli() > nowMillis) {
                    return start.toInstant().toEpochMilli()
                }
            }
            day = day.plusDays(1)
        }
        return nowMillis + 24L * 60 * 60 * 1000
    }

    /** End of the session in progress, or of the next one. */
    fun sessionEndMillis(nowMillis: Long, zone: ZoneId = MarketClock.ZONE): Long {
        val start = nextSessionStartMillis(nowMillis, zone)
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(start), zone)
            .with(WATCH_END)
            .toInstant()
            .toEpochMilli()
    }

    fun millisUntilNextSession(nowMillis: Long, zone: ZoneId = MarketClock.ZONE): Long =
        (nextSessionStartMillis(nowMillis, zone) - nowMillis).coerceAtLeast(0)

    /**
     * Trading holidays are still not modelled, so a holiday looks like a session.
     *
     * The cost is bounded and visible: the watch runs, every feed returns nothing new, and
     * it stops at 15:45. Shipping a holiday calendar to avoid that would mean maintaining
     * one, and a stale calendar fails in the worse direction — skipping a day that is
     * actually trading.
     */
    private fun isTradingDay(day: ZonedDateTime): Boolean =
        day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY

    private const val MAX_LOOKAHEAD_DAYS = 8
}
