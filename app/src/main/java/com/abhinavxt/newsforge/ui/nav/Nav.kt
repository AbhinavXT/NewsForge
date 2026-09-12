package com.abhinavxt.newsforge.ui.nav

/** Top-level destinations, one per bottom-bar entry. */
enum class Tab(val label: String) {
    NEWS("News"),
    CALENDAR("Calendar"),
    DESK("Desk"),
    FEEDS("Feeds"),
}

/** Something pushed on top of a tab. */
sealed interface Detail {
    data class Symbol(val symbol: String) : Detail
}

/**
 * Where the app is.
 *
 * Each tab keeps its own stack, which is the behaviour people expect: opening a company
 * from the calendar, switching to News and switching back should return you to that
 * company, not to the calendar root.
 */
data class NavState(
    val tab: Tab = Tab.NEWS,
    val stacks: Map<Tab, List<Detail>> = emptyMap(),
) {
    val current: Detail? get() = stacks[tab]?.lastOrNull()
}

/**
 * Navigation as pure state transitions.
 *
 * Written out rather than delegated to navigation-compose. That library earns its keep
 * with deep links, typed arguments and complex nested graphs; this app has three tabs and
 * one detail screen taking a single string. What it does buy here is testability — the
 * awkward cases below (re-selecting a tab, back at a root) are exactly the ones that get
 * silently wrong, and they are covered.
 */
object Nav {

    fun selectTab(state: NavState, tab: Tab): NavState =
        if (state.tab == tab) {
            // Re-selecting the current tab pops it to its root — the standard gesture for
            // "get me back to the top of this section".
            state.copy(stacks = state.stacks + (tab to emptyList()))
        } else {
            state.copy(tab = tab)
        }

    fun push(state: NavState, detail: Detail): NavState {
        val stack = state.stacks[state.tab].orEmpty()
        // Pushing the destination already on top is a no-op rather than a duplicate, so a
        // double tap does not need two presses of back to undo.
        if (stack.lastOrNull() == detail) return state
        return state.copy(stacks = state.stacks + (state.tab to stack + detail))
    }

    /** Pushes onto [tab], switching to it first. Used when one tab links into another. */
    fun pushOn(state: NavState, tab: Tab, detail: Detail): NavState =
        push(state.copy(tab = tab), detail)

    /**
     * @return the state after back, or null when there is nothing left to pop — the
     *   caller should then let the system handle it and leave the app, rather than
     *   trapping the user on the News tab forever.
     */
    fun pop(state: NavState): NavState? {
        val stack = state.stacks[state.tab].orEmpty()
        if (stack.isNotEmpty()) {
            return state.copy(stacks = state.stacks + (state.tab to stack.dropLast(1)))
        }
        if (state.tab != Tab.NEWS) return state.copy(tab = Tab.NEWS)
        return null
    }
}
