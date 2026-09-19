package com.abhinavxt.newsforge.core.notify

import com.abhinavxt.newsforge.core.calendar.CalendarGrouping
import com.abhinavxt.newsforge.core.calendar.EventType
import com.abhinavxt.newsforge.core.calendar.UpcomingEvent

/** One scheduled event, surfaced at a particular lead time. */
data class EventAlert(
    val event: UpcomingEvent,
    val tier: WatchTier,
    val daysAway: Long,
) {
    /** Dedupe key: one alert per event per lead, not one per event. */
    val key: String get() = "${event.id}@$daysAway"

    val level: AlertLevel
        get() = if (tier == WatchTier.WATCHING) AlertLevel.NORMAL else AlertLevel.HIGH

    /** "Results tomorrow", "Goes ex-dividend today", "Board meeting in 3 days". */
    fun headline(): String {
        val whenText = when (daysAway) {
            0L -> "today"
            1L -> "tomorrow"
            else -> "in ${daysAway}d"
        }
        val what = when (event.type) {
            EventType.RESULTS -> "results"
            EventType.DIVIDEND -> if (daysAway == 0L) "goes ex-dividend" else "ex-dividend"
            EventType.BOARD_MEETING -> "board meeting"
            EventType.CORPORATE_ACTION -> "corporate action"
        }
        return "${event.symbol} — $what $whenText"
    }
}

/**
 * Decides when to warn about something scheduled.
 *
 * The calendar is only worth building if it reaches you without being opened. The risk it
 * addresses is specific: holding over a date you did not know about. So the lead times
 * scale with exposure, and everything not followed is excluded outright — there are
 * thousands of results dates in a season and none of them are actionable for a company
 * you have no position in.
 */
object EventAlertPolicy {

    /**
     * How far back to look for an alert already sent about the same event and lead.
     *
     * Wider than the news window, and wider than the longest lead in [leadsFor]: a
     * leveraged three-day warning has to still be remembered when the one-day warning
     * comes round, or the same date is announced twice. Storage has to outlive this —
     * see `Retention.NOTIFIED_KEEP_DAYS`, which is what actually bounds it.
     */
    const val DEDUPE_WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * Days ahead at which each tier gets warned.
     *
     * A leveraged position wants time to reduce, which means more than one night's notice.
     * Watching wants to know on the morning, so the news is expected rather than a
     * surprise. Every lead fires once, so a leveraged holder gets three reminders about a
     * results date and no more.
     */
    fun leadsFor(tier: WatchTier): List<Long> = when (tier) {
        WatchTier.LEVERAGED -> listOf(3L, 1L, 0L)
        WatchTier.HOLDING -> listOf(1L, 0L)
        WatchTier.WATCHING -> listOf(0L)
    }

    /**
     * Types worth warning about at each tier.
     *
     * A board meeting on a company you merely track is not worth a notification; the same
     * meeting on a leveraged position is, because it can produce anything.
     */
    fun typesFor(tier: WatchTier): Set<EventType> = when (tier) {
        WatchTier.WATCHING -> setOf(EventType.RESULTS)
        WatchTier.HOLDING -> setOf(EventType.RESULTS, EventType.DIVIDEND, EventType.CORPORATE_ACTION)
        WatchTier.LEVERAGED -> EventType.entries.toSet()
    }

    /** Hard cap, so results season cannot turn the morning into a wall of reminders. */
    const val MAX_PER_SYNC: Int = 5

    fun select(
        events: List<UpcomingEvent>,
        tiers: Map<String, WatchTier>,
        alreadyNotified: Set<String>,
        settings: AlertSettings,
        nowMillis: Long,
    ): List<EventAlert> {
        if (!settings.enabled) return emptyList()
        if (NotificationPolicy.isQuiet(nowMillis, settings)) return emptyList()

        return events
            .asSequence()
            .mapNotNull { event ->
                val tier = tiers[event.symbol] ?: return@mapNotNull null
                if (event.type !in typesFor(tier)) return@mapNotNull null
                val daysAway = CalendarGrouping.daysAway(event.dateMillis, nowMillis)
                if (daysAway !in leadsFor(tier)) return@mapNotNull null
                EventAlert(event, tier, daysAway)
            }
            .filterNot { it.key in alreadyNotified }
            // Soonest first, then exposure: what happens today outranks what happens in
            // three days, whatever the position size.
            .sortedWith(compareBy<EventAlert> { it.daysAway }.thenByDescending { it.tier.order })
            .take(MAX_PER_SYNC)
            .toList()
    }
}
