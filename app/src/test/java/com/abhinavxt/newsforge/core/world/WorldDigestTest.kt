package com.abhinavxt.newsforge.core.world

import com.abhinavxt.newsforge.core.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WorldDigestTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, 8, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun dueAfterTheHourOnceADay() {
        val today = WorldDigest.dayOf(at(12), zone)
        assertFalse(WorldDigest.isDue(at(18, 59), 19, lastSentDay = -1, zone = zone))
        assertTrue(WorldDigest.isDue(at(19, 0), 19, lastSentDay = -1, zone = zone))
        // A worker that wakes late still sends.
        assertTrue(WorldDigest.isDue(at(21, 40), 19, lastSentDay = today - 1, zone = zone))
        assertFalse(WorldDigest.isDue(at(21, 40), 19, lastSentDay = today, zone = zone))
    }

    private val hour = 3_600_000L
    private fun story(id: String, topic: Category, ageHours: Long = 1, read: Boolean = false) =
        WorldDigest.Story(id, topic, at(19) - ageHours * hour, read)

    @Test
    fun picksUnreadRecentVisibleStoriesWithAtMostTwoPerTopic() {
        val ranked = listOf(
            story("s1", Category.SPORTS),
            story("s2", Category.SPORTS),
            story("s3", Category.SPORTS),
            story("old", Category.WORLD, ageHours = 30),
            story("read", Category.WORLD, read = true),
            story("hidden", Category.GAMING),
            story("w1", Category.WORLD),
            story("p1", Category.POLITICS),
            story("x1", Category.SCIENCE),
            story("x2", Category.HEALTH),
        )
        assertEquals(
            listOf("s1", "s2", "w1", "p1", "x1"),
            WorldDigest.select(ranked, hidden = setOf(Category.GAMING), nowMillis = at(19)),
        )
    }
}
