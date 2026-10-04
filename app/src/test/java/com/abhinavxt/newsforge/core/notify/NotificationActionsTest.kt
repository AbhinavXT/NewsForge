package com.abhinavxt.newsforge.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationActionsTest {

    private fun actions(
        symbols: List<String> = listOf("INFY"),
        source: String = "Moneycontrol",
        followed: Boolean = false,
    ) = NotificationActions.forAlert(symbols, source, followed).map { it.action }

    @Test
    fun aStoryAboutAnUnfollowedCompanyLeadsWithWatch() {
        assertEquals(
            listOf(AlertAction.WATCH, AlertAction.CHART, AlertAction.MUTE_SOURCE),
            actions(),
        )
    }

    @Test
    fun aCompanyAlreadyFollowedGetsSaveInstead() {
        // A Watch button on a name already on the watchlist does nothing but occupy one
        // of the three slots Android will draw.
        val result = actions(followed = true)
        assertFalse(AlertAction.WATCH in result)
        assertEquals(
            listOf(AlertAction.CHART, AlertAction.MUTE_SOURCE, AlertAction.SAVE),
            result,
        )
    }

    @Test
    fun aStoryWithNoCompanyOffersOnlyWhatMakesSense() {
        // Policy and macro stories name no ticker, so there is nothing to watch or chart.
        val result = actions(symbols = emptyList())
        assertEquals(listOf(AlertAction.MUTE_SOURCE, AlertAction.SAVE), result)
    }

    @Test
    fun neverMoreThanAndroidWillDraw() {
        for (followed in listOf(true, false)) {
            for (symbols in listOf(emptyList(), listOf("INFY"), listOf("INFY", "TCS", "WIPRO"))) {
                val result = NotificationActions.forAlert(symbols, "Mint", followed)
                assertTrue(result.size <= NotificationActions.MAX_BUTTONS)
            }
        }
    }

    @Test
    fun theButtonActsOnTheLeadingSymbolAndSaysSo() {
        // A story tagging six companies is a round-up. Acting on one of them silently
        // would leave the reader opening the app to find out what the button did.
        val buttons = NotificationActions.forAlert(
            listOf("TATASTEEL", "JSWSTEEL", "SAIL"),
            "Mint",
            followed = false,
        )
        val watch = buttons.first { it.action == AlertAction.WATCH }
        assertEquals("TATASTEEL", watch.symbol)
        assertEquals("Watch TATASTEEL", watch.label)
    }

    @Test
    fun aLongOutletNameFallsBackToGenericWording() {
        val short = NotificationActions.forAlert(emptyList(), "Mint", false)
            .first { it.action == AlertAction.MUTE_SOURCE }
        assertEquals("Mute Mint", short.label)

        val long = NotificationActions.forAlert(emptyList(), "The Hindu BusinessLine", false)
            .first { it.action == AlertAction.MUTE_SOURCE }
        // Truncated to "Mute The Hindu Busin…" it would be less legible than this.
        assertEquals("Mute source", long.label)
        // The rule it acts on still carries the real name.
        assertEquals("The Hindu BusinessLine", long.source)
    }

    @Test
    fun blankFieldsDoNotProduceButtons() {
        val result = NotificationActions.forAlert(listOf("  "), "  ", followed = false)
        assertEquals(listOf(AlertAction.SAVE), result.map { it.action })
    }
}
