package com.abhinavxt.newsforge.ui.util

import com.abhinavxt.newsforge.core.rank.MarketClock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Timestamps for the feed, in IST.
 *
 * Deliberately short at the top end — "4m", "2h" — because during a session the age of a
 * headline is the single most important thing about it after the headline itself, and it
 * has to be readable at a glance in a dense list. Older items switch to a clock time,
 * since "19h" is harder to place than "yesterday 15:40".
 */
object RelativeTime {

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DAY_AND_CLOCK = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.US)

    fun format(thenMillis: Long, nowMillis: Long): String {
        val ageMinutes = (nowMillis - thenMillis) / 60_000
        return when {
            // Future timestamps are clamped at ingest, so this only catches clock skew.
            ageMinutes < 1 -> "now"
            ageMinutes < 60 -> "${ageMinutes}m"
            ageMinutes < 12 * 60 -> "${ageMinutes / 60}h"
            isSameDay(thenMillis, nowMillis) -> at(thenMillis, CLOCK)
            isYesterday(thenMillis, nowMillis) -> "Yesterday " + at(thenMillis, CLOCK)
            else -> at(thenMillis, DAY_AND_CLOCK)
        }
    }

    /** Byline under a headline: "Mint · 12m" or "Mint +3 · 12m" when several carried it. */
    fun byline(source: String, otherSourceCount: Int, thenMillis: Long, nowMillis: Long): String {
        val outlet = if (otherSourceCount > 0) "$source +$otherSourceCount" else source
        return "$outlet · " + format(thenMillis, nowMillis)
    }

    /**
     * "since Friday's close" / "since yesterday's close".
     *
     * Named rather than numeric because the brief's window is a session boundary, and
     * "since 15:30 on the 11th" makes the reader do the arithmetic that the market clock
     * already did.
     */
    fun closeLabel(closeMillis: Long, nowMillis: Long): String = when {
        isSameDay(closeMillis, nowMillis) -> "since today's close"
        isYesterday(closeMillis, nowMillis) -> "since yesterday's close"
        else -> {
            val day = ZonedDateTime
                .ofInstant(Instant.ofEpochMilli(closeMillis), MarketClock.ZONE)
                .dayOfWeek
                .getDisplayName(java.time.format.TextStyle.FULL, Locale.US)
            "since $day's close"
        }
    }

    /**
     * A future date: "Today", "Tomorrow", "in 4d", "18 Sep".
     *
     * Forward-looking dates need the opposite emphasis to past ones. "2h ago" is precise
     * because a fresh headline's age is the point; a results date four days out only has
     * to answer "before or after the weekend", and a clock time would be noise.
     */
    fun dayLabel(thenMillis: Long, nowMillis: Long): String {
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), MarketClock.ZONE)
            .toLocalDate()
        val day = ZonedDateTime.ofInstant(Instant.ofEpochMilli(thenMillis), MarketClock.ZONE)
            .toLocalDate()
        val away = day.toEpochDay() - today.toEpochDay()
        return when {
            away == 0L -> "Today"
            away == 1L -> "Tomorrow"
            away in 2..6 -> "in ${away}d"
            else -> day.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
        }
    }

    private fun at(millis: Long, format: DateTimeFormatter): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), MarketClock.ZONE).format(format)

    private fun isSameDay(a: Long, b: Long): Boolean = dayOf(a) == dayOf(b)

    private fun isYesterday(then: Long, now: Long): Boolean =
        dayOf(then) == dayOf(now).minusDays(1)

    private fun dayOf(millis: Long) =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), MarketClock.ZONE).toLocalDate()
}
