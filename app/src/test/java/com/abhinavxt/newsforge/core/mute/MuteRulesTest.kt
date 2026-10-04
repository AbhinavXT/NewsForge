package com.abhinavxt.newsforge.core.mute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteRulesTest {

    private fun muted(rule: MuteRule, source: String = "Mint", text: String = "", symbols: List<String> = emptyList()) =
        MuteRules.isMuted(source, text, symbols, listOf(rule))

    @Test
    fun sourceRulesMatchTheOutletExactly() {
        assertTrue(muted(MuteRule(MuteKind.SOURCE, "Mint"), source = "Mint"))
        assertTrue(muted(MuteRule(MuteKind.SOURCE, "  mint "), source = "Mint"))
        // Substring matching here would silence "Livemint" too.
        assertFalse(muted(MuteRule(MuteKind.SOURCE, "Mint"), source = "Livemint"))
    }

    @Test
    fun tickerRulesAreExactNotSubstring() {
        // Muting BEL must not silence BELRISE.
        assertTrue(muted(MuteRule(MuteKind.SYMBOL, "BEL"), symbols = listOf("BEL")))
        assertTrue(muted(MuteRule(MuteKind.SYMBOL, "bel"), symbols = listOf("BEL")))
        assertFalse(muted(MuteRule(MuteKind.SYMBOL, "BEL"), symbols = listOf("BELRISE")))
        assertFalse(muted(MuteRule(MuteKind.SYMBOL, "BEL"), symbols = emptyList()))
    }

    @Test
    fun keywordRulesReadHeadlineAndSummaryTogether() {
        // The word you want gone is as often in the first line of the body as the title.
        assertTrue(muted(MuteRule(MuteKind.KEYWORD, "IPO"), text = "Three IPOs open this week"))
        assertTrue(muted(MuteRule(MuteKind.KEYWORD, "ipo"), text = "Company update. The IPO opens Monday."))
        assertFalse(muted(MuteRule(MuteKind.KEYWORD, "IPO"), text = "Quarterly results out"))
    }

    @Test
    fun aBlankRuleMatchesNothing() {
        // Otherwise an empty keyword would silence the entire feed.
        assertFalse(muted(MuteRule(MuteKind.KEYWORD, "   "), text = "anything at all"))
        assertFalse(muted(MuteRule(MuteKind.SOURCE, ""), source = "Mint"))
    }

    @Test
    fun anyMatchingRuleMutes() {
        val rules = listOf(
            MuteRule(MuteKind.SOURCE, "Nobody"),
            MuteRule(MuteKind.KEYWORD, "bonus"),
        )
        assertTrue(MuteRules.isMuted("Mint", "Board declares bonus issue", emptyList(), rules))
        assertFalse(MuteRules.isMuted("Mint", "Quarterly results", emptyList(), rules))
    }

    @Test
    fun noRulesMuteNothing() {
        assertFalse(MuteRules.isMuted("Mint", "anything", listOf("BEL"), emptyList()))
    }

    @Test
    fun normalisationTrimsAndDeduplicatesByCase() {
        val rules = MuteRules.normalise(
            listOf(
                MuteRule(MuteKind.SOURCE, "Mint"),
                MuteRule(MuteKind.SOURCE, " mint "),
                MuteRule(MuteKind.KEYWORD, "Mint"),
                MuteRule(MuteKind.SOURCE, "  "),
            )
        )
        // Same kind and value differing only in case is one rule; a keyword "Mint" is a
        // different rule from a source "Mint".
        assertEquals(2, rules.size)
        assertEquals("Mint", rules.first().value)
    }
}

class AlertSensitivityTest {

    @Test
    fun levelsAreOrderedAndConsistent() {
        // A quieter level must be strictly harder to clear and send strictly fewer.
        assertTrue(AlertSensitivity.QUIET.minScore > AlertSensitivity.BALANCED.minScore)
        assertTrue(AlertSensitivity.BALANCED.minScore > AlertSensitivity.LOUD.minScore)
        assertTrue(AlertSensitivity.QUIET.maxPerSync < AlertSensitivity.BALANCED.maxPerSync)
        assertTrue(AlertSensitivity.BALANCED.maxPerSync < AlertSensitivity.LOUD.maxPerSync)
    }

    @Test
    fun balancedMatchesTheShippedDefault() {
        // The default has been calibrated against real ranker output since patch 05;
        // the levels are a UI over it, not a replacement for it.
        assertEquals(
            com.abhinavxt.newsforge.core.notify.AlertSettings().minScore,
            AlertSensitivity.BALANCED.minScore,
            1e-9,
        )
        assertEquals(AlertSensitivity.DEFAULT, AlertSensitivity.BALANCED)
    }

    @Test
    fun parseRoundTripsAndFallsBack() {
        for (level in AlertSensitivity.entries) {
            assertEquals(level, AlertSensitivity.parse(level.name))
        }
        assertEquals(AlertSensitivity.DEFAULT, AlertSensitivity.parse("NONSENSE"))
        assertEquals(AlertSensitivity.DEFAULT, AlertSensitivity.parse(null))
    }

    @Test
    fun everyLevelHasSomethingLegibleToShow() {
        for (level in AlertSensitivity.entries) {
            assertTrue(level.label.isNotBlank())
            assertTrue(level.description.isNotBlank())
        }
    }
}
