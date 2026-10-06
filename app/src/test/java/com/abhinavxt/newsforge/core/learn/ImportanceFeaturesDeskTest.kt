package com.abhinavxt.newsforge.core.learn

import com.abhinavxt.newsforge.core.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportanceFeaturesDeskTest {

    @Test
    fun worldTopicsAreNotFeatures() {
        // The vector's length is what a stored model's weights line up against. Adding
        // the world topics to it would shift every weight after the categories onto the
        // wrong feature, silently.
        for (topic in Category.WORLD_TOPICS) {
            assertTrue(
                "${topic.name} leaked into the feature vector",
                "cat:${topic.name.lowercase()}" !in ImportanceFeatures.NAMES,
            )
        }
        assertEquals(
            Category.MARKETS.size,
            ImportanceFeatures.NAMES.count { it.startsWith("cat:") },
        )
    }
}
