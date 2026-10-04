package com.abhinavxt.newsforge.core.notify

/**
 * Who put a symbol on the watchlist.
 *
 * Tracked because the two sources answer different questions and must not overwrite each
 * other. [MANUAL] is "I want to hear about this", which survives closing the position.
 * [DESK] is "I am exposed to this right now", which is only true until you sell.
 *
 * Both can hold the same symbol at once, at different tiers, and that is the normal case
 * rather than a conflict: a name you have followed for a year and bought on margin this
 * morning is genuinely both. The effective tier is the stronger of the two, so a position
 * can raise how loudly a name interrupts you but closing it can never silence something
 * you asked to follow.
 */
enum class WatchSource {
    MANUAL,
    DESK,
    ;

    companion object {
        val DEFAULT: WatchSource = MANUAL

        fun parse(name: String?): WatchSource = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
