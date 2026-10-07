package com.abhinavxt.newsforge.core.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationTest {

    @Test
    fun aStoryReadsTopicHeadlineSummaryThenOutletWithStopsBetween() {
        assertEquals(
            "Science. ISRO launches Chandrayaan-4. It aims to return samples. From The Hindu.",
            Narration.story("Science", "ISRO launches Chandrayaan-4", "It aims to return samples", "The Hindu"),
        )
    }

    @Test
    fun aStoryWithoutASummarySkipsIt() {
        assertEquals("World. Talks resume! From BBC.", Narration.story("World", "Talks resume!", null, "BBC"))
    }

    @Test
    fun chunksStayUnderTheLimitAndBreakAtSentences() {
        val sentence = "The committee met again on Tuesday to discuss the plan. "
        val text = sentence.repeat(200)
        val chunks = Narration.chunks(text, max = 500)
        assertTrue(chunks.all { it.length <= 500 })
        assertTrue(chunks.all { it.endsWith(".") })
        // Nothing lost or duplicated across the cuts.
        assertEquals(text.trim().replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun aShortTextIsOneChunk() {
        assertEquals(listOf("Hello there."), Narration.chunks("Hello there."))
    }

    @Test
    fun anUnbreakableRunIsCutHard() {
        val url = "x".repeat(1_200)
        val chunks = Narration.chunks(url, max = 500)
        assertTrue(chunks.all { it.length <= 500 })
        assertEquals(1_200, chunks.sumOf { it.length })
    }
}
