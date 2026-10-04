package com.abhinavxt.newsforge.core.rank

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Where the trading day currently is. Drives both ranking decay and the feed's mode. */
enum class MarketPhase {
    /** Weekday before the 09:15 open — the window the overnight brief covers. */
    PRE_OPEN,

    /** 09:15 to 15:30 on a trading day. */
    OPEN,

    /** After 15:30 on a weekday. */
    POST_CLOSE,

    /** Saturday or Sunday. */
    WEEKEND,
}

/**
 * Session times for NSE/BSE cash equity, in IST.
 *
 * Trading holidays are not handled yet, so a Diwali Monday reads as [PRE_OPEN] then
 * [OPEN]. The only consequence is a faster decay rate on a day with no prices to react
 * to, which is not worth shipping a holiday calendar for until the calendar has somewhere
 * else to earn its keep.
 */
object MarketClock {

    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    val OPEN_TIME: LocalTime = LocalTime.of(9, 15)
    val CLOSE_TIME: LocalTime = LocalTime.of(15, 30)

    fun phase(nowMillis: Long, zone: ZoneId = ZONE): MarketPhase {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) {
            return MarketPhase.WEEKEND
        }
        val time = now.toLocalTime()
        return when {
            time < OPEN_TIME -> MarketPhase.PRE_OPEN
            time < CLOSE_TIME -> MarketPhase.OPEN
            else -> MarketPhase.POST_CLOSE
        }
    }

    /**
     * How fast a headline's rank should decay, in minutes.
     *
     * During the session, relevance really does halve in under an hour — a 10:00 order
     * win is not a 14:00 trade. Overnight the opposite holds: news from 23:00 has to
     * still be near the top when the pre-market brief is read at 08:30, so the half-life
     * is long enough to survive the gap.
     */
    fun halfLifeMinutes(phase: MarketPhase): Double = when (phase) {
        MarketPhase.OPEN -> 45.0
        MarketPhase.PRE_OPEN -> 240.0
        MarketPhase.POST_CLOSE -> 180.0
        MarketPhase.WEEKEND -> 720.0
    }

    /**
     * The most recent session close at or before [nowMillis]; the start of the window the
     * overnight brief covers.
     */
    fun lastCloseMillis(nowMillis: Long, zone: ZoneId = ZONE): Long {
        var day = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        repeat(MAX_LOOKBACK_DAYS) {
            val close = day.with(CLOSE_TIME)
            val isTradingDay = day.dayOfWeek != DayOfWeek.SATURDAY &&
                day.dayOfWeek != DayOfWeek.SUNDAY
            if (isTradingDay && close.toInstant().toEpochMilli() <= nowMillis) {
                return close.toInstant().toEpochMilli()
            }
            day = day.minusDays(1)
        }
        return nowMillis - MAX_LOOKBACK_DAYS * 24L * 60 * 60 * 1000
    }

    private const val MAX_LOOKBACK_DAYS = 7
}
