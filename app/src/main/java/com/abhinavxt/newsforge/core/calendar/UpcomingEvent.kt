package com.abhinavxt.newsforge.core.calendar

import com.abhinavxt.newsforge.core.rank.MarketClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/** What kind of dated obligation this is. */
enum class EventType(val label: String) {
    /** Board meeting called to consider results. The one that carries gap risk. */
    RESULTS("Results"),

    /** Dividend declaration, with its record or ex date. */
    DIVIDEND("Dividend"),

    /** Board meeting for anything else — fundraise, buyback, capex approval. */
    BOARD_MEETING("Board meeting"),

    /** Bonus, split, buyback, rights — anything with a record date. */
    CORPORATE_ACTION("Corporate action"),
}

/**
 * A dated event that has not happened yet.
 *
 * Deliberately a separate model from an article. Everything else in this app is a report
 * of something that already happened; this is a commitment about the future, and merging
 * the two would mean a results date competing with news for a slot in the brief and
 * decaying out of relevance as it *approached*, which is exactly backwards.
 */
data class UpcomingEvent(
    val id: String,
    val symbol: String,
    val type: EventType,
    val title: String,
    val dateMillis: Long,
    /** Feed id this came from, for the same reason articles carry one. */
    val sourceFeedId: String,
)

/** Coarse buckets the calendar screen renders as sections. */
enum class CalendarBucket(val label: String) {
    TODAY("Today"),
    TOMORROW("Tomorrow"),
    THIS_WEEK("This week"),
    LATER("Later"),
    PAST("Past"),
}

data class CalendarSection(
    val bucket: CalendarBucket,
    val events: List<UpcomingEvent>,
)

object CalendarGrouping {

    /** Beyond this the calendar is speculation, not planning. */
    const val HORIZON_DAYS: Long = 45

    /**
     * Whole calendar days between now and the event, negative for the past.
     *
     * Calendar days, not elapsed hours: an event at 09:00 tomorrow is one day away at
     * both 22:00 tonight and 08:00 tomorrow, which is how anyone holding over it thinks
     * about it.
     */
    fun daysAway(eventMillis: Long, nowMillis: Long): Long =
        dateOf(eventMillis).toEpochDay() - dateOf(nowMillis).toEpochDay()

    fun bucketOf(eventMillis: Long, nowMillis: Long): CalendarBucket {
        val daysAway = daysAway(eventMillis, nowMillis)
        return when {
            daysAway < 0 -> CalendarBucket.PAST
            daysAway == 0L -> CalendarBucket.TODAY
            daysAway == 1L -> CalendarBucket.TOMORROW
            daysAway <= 7 -> CalendarBucket.THIS_WEEK
            else -> CalendarBucket.LATER
        }
    }

    /**
     * @param watchlist symbols to keep when [followedOnly] is set.
     * @return sections in chronological bucket order, each sorted by date then symbol.
     *   Past events are dropped: the calendar answers "what is coming", and yesterday's
     *   results date belongs in the news feed, where the actual filing already is.
     */
    fun group(
        events: List<UpcomingEvent>,
        nowMillis: Long,
        watchlist: Set<String> = emptySet(),
        followedOnly: Boolean = false,
    ): List<CalendarSection> {
        val horizon = nowMillis + HORIZON_DAYS * DAY_MS
        val relevant = events
            .filter { if (followedOnly) it.symbol in watchlist else true }
            .filter { bucketOf(it.dateMillis, nowMillis) != CalendarBucket.PAST }
            .filter { it.dateMillis <= horizon }

        return CalendarBucket.entries
            .filter { it != CalendarBucket.PAST }
            .mapNotNull { bucket ->
                val inBucket = relevant
                    .filter { bucketOf(it.dateMillis, nowMillis) == bucket }
                    .sortedWith(compareBy({ it.dateMillis }, { it.symbol }))
                if (inBucket.isEmpty()) null else CalendarSection(bucket, inBucket)
            }
    }

    /**
     * The next dated event for a symbol, if any.
     *
     * This is what makes the calendar useful outside its own screen: a story about a
     * company whose results land tomorrow reads differently from the same story with
     * nothing scheduled.
     */
    fun nextFor(symbol: String, events: List<UpcomingEvent>, nowMillis: Long): UpcomingEvent? =
        events
            .filter { it.symbol == symbol && bucketOf(it.dateMillis, nowMillis) != CalendarBucket.PAST }
            .minByOrNull { it.dateMillis }

    private fun dateOf(millis: Long): LocalDate =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), MarketClock.ZONE).toLocalDate()

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
