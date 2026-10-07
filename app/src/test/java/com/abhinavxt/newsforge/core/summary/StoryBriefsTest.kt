package com.abhinavxt.newsforge.core.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryBriefsTest {

    private val title = "ISRO launches Chandrayaan-4 from Sriharikota"

    private val coverage = listOf(
        OutletText(
            "The Hindu", title,
            "The Indian Space Research Organisation launched the lunar mission on Tuesday morning. " +
                "The spacecraft will attempt to bring lunar samples back to Earth by 2028. " +
                "Read more on our science page.",
        ),
        OutletText(
            "Indian Express", "Chandrayaan-4 lifts off",
            "The lunar mission was launched by the Indian Space Research Organisation on Tuesday. " +
                "Officials said the spacecraft will bring lunar samples back to Earth. " +
                "Crowds gathered on nearby beaches to watch the launch.",
        ),
        OutletText(
            "BBC", "India launches Moon sample mission",
            "India's space agency launched a mission on Tuesday that aims to bring lunar samples back. " +
                "It would make India the fourth country to return samples from the Moon.",
        ),
    )

    @Test
    fun picksWhatSeveralOutletsAgreeOnAndSkipsBoilerplate() {
        val brief = StoryBriefs.summarize(title, coverage)!!
        assertTrue(brief.points.isNotEmpty())
        assertTrue(brief.points.size <= StoryBriefs.MAX_POINTS)
        assertFalse(brief.points.any { it.contains("Read more") })
        // Both corroborated facts make it: the launch, and the samples coming back.
        assertTrue(brief.points.toString(), brief.points.any { it.contains("launched") })
        assertTrue(brief.points.toString(), brief.points.any { it.contains("samples") })
    }

    @Test
    fun neverRepeatsAPoint() {
        val brief = StoryBriefs.summarize(title, coverage)!!
        // Three outlets say "launched on Tuesday" in three ways; only one survives.
        assertEquals(brief.points.toString(), 1, brief.points.count { it.contains("Tuesday") })
    }

    @Test
    fun returnsNullWhenSummariesOnlyRepeatHeadlines() {
        val echoes = listOf(
            OutletText("A", title, "$title — The Hindu"),
            OutletText("B", title, null),
        )
        assertNull(StoryBriefs.summarize(title, echoes))
    }

    @Test
    fun dropsSentencesTheFeedCutOffMidWord() {
        val cut = StoryBriefs.sentences(
            "The committee approved the new framework after a long debate. The minister added that the rul"
        )
        assertEquals(listOf("The committee approved the new framework after a long debate."), cut)
    }
}
