package com.abhinavxt.newsforge.core.quote

import com.abhinavxt.newsforge.core.rank.MarketClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One results story, as much of it as the history needs. */
data class ResultsStory(val publishedAt: Long, val title: String, val source: String)

/**
 * One results announcement and what the stock did next.
 *
 * Moves are close to close from the last session before the news could trade, so a
 * result published after the bell is measured from that day's close to the next day's —
 * the first session that could react to it.
 *
 * @param stories every results story in the cluster of days, newest last; the first is
 *   shown as the event's headline.
 */
data class ResultsEvent(
    val publishedAt: Long,
    val title: String,
    val source: String,
    val storyCount: Int,
    /** First session that could react. Null without a bar on both sides. */
    val dayMovePercent: Double?,
    /** Five sessions on, for whether the first move held. */
    val weekMovePercent: Double?,
)

/**
 * A company's recent results, with the price reaction after each.
 *
 * The question this answers is "how does this stock usually take its numbers" — whether
 * a beat tends to be sold, whether misses get forgiven — and that needs several quarters
 * side by side rather than one story at a time on the timeline.
 */
object EarningsHistory {

    /** Stories this close together are coverage of one announcement, not two. */
    const val SAME_EVENT_DAYS = 4L

    const val WEEK_SESSIONS = 5

    fun of(
        stories: List<ResultsStory>,
        dailyBars: List<Candle>,
        zone: ZoneId = MarketClock.ZONE,
    ): List<ResultsEvent> {
        val bars = dailyBars.sortedBy { it.openTimeMillis }
        val barDates = bars.map { dateOf(it.openTimeMillis, zone) }
        return group(stories.sortedBy { it.publishedAt }, zone)
            .map { cluster ->
                val first = cluster.first()
                val session = reactingSession(first.publishedAt, zone)
                val baseIndex = barDates.indexOfLast { it < session }
                val dayIndex = barDates.indexOfFirst { it >= session }
                val base = bars.getOrNull(baseIndex)?.close?.takeIf { baseIndex >= 0 && it > 0 }
                ResultsEvent(
                    publishedAt = first.publishedAt,
                    title = first.title,
                    source = first.source,
                    storyCount = cluster.size,
                    dayMovePercent = move(base, bars, dayIndex),
                    weekMovePercent = move(
                        base,
                        bars,
                        if (dayIndex < 0) -1 else dayIndex + WEEK_SESSIONS - 1,
                    ),
                )
            }
            .sortedByDescending { it.publishedAt }
    }

    private fun group(sorted: List<ResultsStory>, zone: ZoneId): List<List<ResultsStory>> {
        val groups = ArrayList<MutableList<ResultsStory>>()
        for (story in sorted) {
            val current = groups.lastOrNull()
            val startedOn = current?.first()?.let { dateOf(it.publishedAt, zone) }
            if (startedOn != null &&
                !dateOf(story.publishedAt, zone).isAfter(startedOn.plusDays(SAME_EVENT_DAYS))
            ) {
                current.add(story)
            } else {
                groups += mutableListOf(story)
            }
        }
        return groups
    }

    /** The trading date a story first had a chance to move: today, or tomorrow after the bell. */
    internal fun reactingSession(publishedAt: Long, zone: ZoneId): LocalDate {
        val at = Instant.ofEpochMilli(publishedAt).atZone(zone)
        return if (at.toLocalTime() >= MarketClock.CLOSE_TIME) {
            at.toLocalDate().plusDays(1)
        } else {
            at.toLocalDate()
        }
    }

    private fun move(base: Double?, bars: List<Candle>, index: Int): Double? {
        if (base == null || index < 0) return null
        val close = bars.getOrNull(index)?.close ?: return null
        return (close - base) / base * 100.0
    }

    private fun dateOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
