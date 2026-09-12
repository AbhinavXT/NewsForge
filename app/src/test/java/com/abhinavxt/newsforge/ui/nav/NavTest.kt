package com.abhinavxt.newsforge.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NavTest {

    private val symbol = Detail.Symbol("TATASTEEL")
    private val other = Detail.Symbol("INFY")

    @Test
    fun startsOnNewsWithNothingPushed() {
        val state = NavState()
        assertEquals(Tab.NEWS, state.tab)
        assertNull(state.current)
    }

    @Test
    fun pushAndPopWalkTheStack() {
        var state = Nav.push(NavState(), symbol)
        assertEquals(symbol, state.current)
        state = Nav.push(state, other)
        assertEquals(other, state.current)
        state = Nav.pop(state)!!
        assertEquals(symbol, state.current)
        state = Nav.pop(state)!!
        assertNull(state.current)
    }

    @Test
    fun pushingTheSameDestinationTwiceIsANoOp() {
        // Otherwise a double tap needs two presses of back to undo.
        val once = Nav.push(NavState(), symbol)
        assertSame(once, Nav.push(once, symbol))
    }

    @Test
    fun eachTabKeepsItsOwnStack() {
        // Open a company from the calendar, look at News, come back: you should be where
        // you left off, not at the calendar root.
        var state = Nav.pushOn(NavState(), Tab.CALENDAR, symbol)
        assertEquals(symbol, state.current)
        state = Nav.selectTab(state, Tab.NEWS)
        assertNull(state.current)
        state = Nav.selectTab(state, Tab.CALENDAR)
        assertEquals(symbol, state.current)
    }

    @Test
    fun reselectingTheCurrentTabPopsItToItsRoot() {
        var state = Nav.push(NavState(), symbol)
        state = Nav.selectTab(state, Tab.NEWS)
        assertNull(state.current)
    }

    @Test
    fun selectingADifferentTabDoesNotClearTheOneYouLeft() {
        var state = Nav.push(NavState(), symbol)
        state = Nav.selectTab(state, Tab.FEEDS)
        state = Nav.selectTab(state, Tab.NEWS)
        assertEquals(symbol, state.current)
    }

    @Test
    fun backFromAnotherTabsRootReturnsToNews() {
        val state = Nav.pop(NavState(tab = Tab.FEEDS))
        assertEquals(Tab.NEWS, state?.tab)
    }

    @Test
    fun backAtTheNewsRootHandsOffToTheSystem() {
        // Returning a state here instead of null would trap the user in the app.
        assertNull(Nav.pop(NavState()))
    }
}
