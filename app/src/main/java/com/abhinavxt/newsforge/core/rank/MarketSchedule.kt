package com.abhinavxt.newsforge.core.rank

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When the live watch should be running.
 *
 * Two windows rather than one, and the reason is a platform cap rather than a market one.
 * The watch runs as a `dataSync` foreground service, and from Android 14 the system
 * allows an app six hours of that per day before it stops the service itself. A single
 * 09:00–15:45 window is six hours forty-five, so the system would have killed it at
 * roughly 15:00 — losing precisely the stretch the feature exists for, since results and
 * board-meeting outcomes start landing within minutes of the close.
 *
 * So the middle goes instead. The morning covers the pre-open gap list and the first two
 * and a half hours of trading; the afternoon covers the last two hours and the wave of
 * filings after the bell. Between them sits the lunch lull, which is the cheapest ninety
 * minutes of the day to drop, and even then it is not dark — the periodic background sync
 * still runs every fifteen minutes throughout.
 *
 * The total is deliberately kept under the budget with room to spare: the cap counts time
 * the service was actually up, and a restart after a crash or a doze eviction spends more
 * of it than the schedule implies.
 */
object MarketSchedule {

    /** A stretch of the day the foreground watch runs through. */
    data class Segment(val start: LocalTime, val end: LocalTime) {
        val durationMinutes: Long get() = Duration.between(start, end).toMinutes()

        /** Half-open, so back-to-back segments could never both claim the same instant. */
        fun contains(time: LocalTime): Boolean = time >= start && time < end
    }

    val SEGMENTS: List<Segment> = listOf(
        // Opens at 09:00: the fifteen minutes before the bell are when the gap list is
        // finalised. Closes at noon, by which point the morning trend is set.
        Segment(LocalTime.of(9, 0), LocalTime.of(12, 0)),
        // Back for the last two hours and the post-close wave. Ends at 15:45 rather than
        // 15:30 because stopping at the bell exactly would miss the first filings.
        Segment(LocalTime.of(13, 30), LocalTime.of(15, 45)),
    )

    val WATCH_START: LocalTime get() = SEGMENTS.first().start
    val WATCH_END: LocalTime get() = SEGMENTS.last().end

    /**
     * What Android 14 and above allow a `dataSync` foreground service per app per day.
     *
     * Not enforced here — the system enforces it, by stopping the service — but stated so
     * that [scheduledMinutes] can be checked against it, which is the only thing standing
     * between a change to [SEGMENTS] and the watch dying mid-afternoon again.
     */
    const val FOREGROUND_BUDGET_MINUTES: Long = 6 * 60

    val scheduledMinutes: Long get() = SEGMENTS.sumOf { it.durationMinutes }

    /** The segment covering this instant, or null outside the watch. */
    fun segmentAt(nowMillis: Long, zone: ZoneId = MarketClock.ZONE): Segment? {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        if (!isTradingDay(now)) return null
        val time = now.toLocalTime()
        return SEGMENTS.firstOrNull { it.contains(time) }
    }

    fun isSessionActive(nowMillis: Long, zone: ZoneId = MarketClock.ZONE): Boolean =
        segmentAt(nowMillis, zone) != null

    /**
     * Start of the segment the watch is currently in, or of the next one.
     *
     * Returns "now" rather than a past instant when a segment is already running, so a
     * caller scheduling with a delay does not compute a negative one.
     */
    fun nextSessionStartMillis(nowMillis: Long, zone: ZoneId = MarketClock.ZONE): Long {
        if (isSessionActive(nowMillis, zone)) return nowMillis

        var day = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        repeat(MAX_LOOKAHEAD_DAYS) {
            if (isTradingDay(day)) {
                for (segment in SEGMENTS) {
                    val start = day.with(segment.start).toInstant().toEpochMilli()
                    // Ordered, so the first future start is the nearest one — this is
                    // what makes the midday gap resolve to 13:30 today rather than to
                    // 09:00 tomorrow.
                    if (start > nowMillis) return start
                }
            }
            // Midnight, so tomorrow's earlier segments are candidates too.
            day = day.plusDays(1).with(LocalTime.MIDNIGHT)
        }
        return nowMillis + 24L * 60 * 60 * 1000
    }

    /** End of the segment in progress, or of the next one. */
    fun sessionEndMillis(nowMillis: Long, zone: ZoneId = MarketClock.ZONE): Long {
        val start = nextSessionStartMillis(nowMillis, zone)
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(start), zone)
        val segment = SEGMENTS.firstOrNull { it.contains(at.toLocalTime()) } ?: SEGMENTS.first()
        return at.with(segment.end).toInstant().toEpochMilli()
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
