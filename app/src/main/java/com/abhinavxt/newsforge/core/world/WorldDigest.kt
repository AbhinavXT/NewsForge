package com.abhinavxt.newsforge.core.world

import com.abhinavxt.newsforge.core.model.Category
import java.time.Instant
import java.time.ZoneId

/**
 * The evening world digest: when it goes out, and what is in it.
 *
 * Kept free of Android and of the store so both decisions are pinned by tests — "once a
 * day, after the chosen hour" is exactly the kind of rule that quietly sends twice or
 * never.
 */
object WorldDigest {

    /** Lines in the notification; past this the shade truncates anyway. */
    const val MAX_STORIES = 5

    /**
     * No more than this many from one topic, so a big sports night cannot fill a digest
     * that is meant to be the day's news across the board.
     */
    const val MAX_PER_TOPIC = 2

    /** How far back a story may have been published to count as today's. */
    const val WINDOW_MS = 24L * 60 * 60 * 1000

    /**
     * Whether the digest should be sent now.
     *
     * Any time after [hour] on a day it has not yet gone out — not only within that hour —
     * because the background worker's runs drift, and a phone in deep sleep at 19:00 should
     * still get the digest when it wakes at 19:40.
     *
     * @param lastSentDay the local epoch day it last went out, or -1.
     */
    fun isDue(nowMillis: Long, hour: Int, lastSentDay: Long, zone: ZoneId): Boolean {
        val local = Instant.ofEpochMilli(nowMillis).atZone(zone)
        return local.hour >= hour && local.toLocalDate().toEpochDay() != lastSentDay
    }

    fun dayOf(nowMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toEpochDay()

    /** One candidate, in the shape this needs and no more. */
    data class Story(
        val clusterId: String,
        val category: Category,
        val publishedAt: Long,
        val read: Boolean,
    )

    /**
     * Picks the digest from stories already in rank order.
     *
     * Unread only — a story already opened today is not news to this reader — from the
     * last day, outside hidden topics, with at most [MAX_PER_TOPIC] per topic.
     *
     * @return cluster ids, in rank order.
     */
    fun select(
        ranked: List<Story>,
        hidden: Set<Category>,
        nowMillis: Long,
    ): List<String> {
        val perTopic = HashMap<Category, Int>()
        val picked = ArrayList<String>(MAX_STORIES)
        for (story in ranked) {
            if (picked.size == MAX_STORIES) break
            if (story.read || story.category in hidden) continue
            if (nowMillis - story.publishedAt > WINDOW_MS) continue
            val used = perTopic[story.category] ?: 0
            if (used >= MAX_PER_TOPIC) continue
            perTopic[story.category] = used + 1
            picked += story.clusterId
        }
        return picked
    }
}
