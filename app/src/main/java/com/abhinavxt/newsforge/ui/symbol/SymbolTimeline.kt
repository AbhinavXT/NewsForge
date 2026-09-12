package com.abhinavxt.newsforge.ui.symbol

import com.abhinavxt.newsforge.core.calendar.CalendarGrouping
import com.abhinavxt.newsforge.core.calendar.UpcomingEvent
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.data.model.ScoredArticle
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/** What has been said about a company, and what is scheduled for it. */
data class TimelineSummary(
    val symbol: String,
    val storyCount: Int,
    /** Category counts, most frequent first. Only categories actually present. */
    val byCategory: List<Pair<Category, Int>>,
    val nextEvent: UpcomingEvent?,
)

data class TimelineSection(
    val label: String,
    val stories: List<ScoredArticle>,
)

/**
 * Groups one company's stored coverage chronologically.
 *
 * The value here is not any single headline — you have already read those — but the shape
 * of them together. Four regulatory items in two months reads differently from four
 * separate headlines encountered a fortnight apart, and that difference is the whole
 * reason this screen exists.
 */
object SymbolTimeline {

    fun summarize(
        symbol: String,
        stories: List<ScoredArticle>,
        events: List<UpcomingEvent>,
        nowMillis: Long,
    ): TimelineSummary = TimelineSummary(
        symbol = symbol,
        storyCount = stories.size,
        byCategory = stories
            .groupingBy { it.article.category }
            .eachCount()
            .toList()
            // Frequency first, then the taxonomy's own weighting, so two categories with
            // equal counts order by which one matters more.
            .sortedWith(compareByDescending<Pair<Category, Int>> { it.second }
                .thenByDescending { it.first.weight })
            .toList(),
        nextEvent = CalendarGrouping.nextFor(symbol, events, nowMillis),
    )

    /**
     * Chronological sections, newest first.
     *
     * Deliberately coarse. Retention keeps most coverage inside a week, so grouping by
     * month would produce one section containing everything and prove nothing.
     */
    fun sections(stories: List<ScoredArticle>, nowMillis: Long): List<TimelineSection> {
        val ordered = stories.sortedByDescending { it.article.publishedAt }
        val today = dateOf(nowMillis)

        val buckets = linkedMapOf(
            "Today" to ArrayList<ScoredArticle>(),
            "This week" to ArrayList(),
            "This month" to ArrayList(),
            "Earlier" to ArrayList(),
        )
        for (story in ordered) {
            val daysAgo = today.toEpochDay() - dateOf(story.article.publishedAt).toEpochDay()
            val key = when {
                daysAgo <= 0 -> "Today"
                daysAgo <= 7 -> "This week"
                daysAgo <= 31 -> "This month"
                else -> "Earlier"
            }
            buckets.getValue(key).add(story)
        }
        return buckets.mapNotNull { (label, items) ->
            if (items.isEmpty()) null else TimelineSection(label, items)
        }
    }

    private fun dateOf(millis: Long): LocalDate =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), MarketClock.ZONE).toLocalDate()
}
