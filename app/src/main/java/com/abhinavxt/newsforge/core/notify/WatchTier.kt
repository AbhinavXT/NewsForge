package com.abhinavxt.newsforge.core.notify

/**
 * How much exposure you have to a name, which is what should decide how loudly it
 * interrupts you.
 *
 * The app previously treated every followed symbol identically. That is wrong in one
 * direction that matters: a management change at a company you are merely tracking is a
 * shrug, and the same filing against a leveraged position is something you may have to
 * act on at the open.
 */
enum class WatchTier(val label: String, val order: Int) {
    /** Tracking it. Interested, not exposed. */
    WATCHING("Watching", 0),

    /** Holding it unlevered. */
    HOLDING("Holding", 1),

    /** Holding it on margin. Adverse news here can force a decision, not just prompt one. */
    LEVERAGED("Leveraged", 2),
    ;

    /**
     * Score a story must reach to alert for a symbol at this tier.
     *
     * Calibrated against what the ranker actually emits rather than picked as a pleasing
     * ladder. At full recency a wire story about a tagged company scores category weight
     * × 1.2 × 1.5, which puts General at ~1.26 and everything from Management upward at
     * 2.16 or more. So:
     *
     * - 2.0 admits Management and above, and excludes uncategorised noise.
     * - 1.5 admits the same categories but tolerates roughly half an hour more decay.
     * - 1.0 admits even a General story on the name, at full recency.
     *
     * The leveraged bar is deliberately the loosest: a false alert costs a glance, a
     * missed one can cost a forced exit.
     */
    val alertThreshold: Double
        get() = when (this) {
            WATCHING -> 2.0
            HOLDING -> 1.5
            LEVERAGED -> 1.0
        }

    companion object {
        val DEFAULT: WatchTier = WATCHING

        fun parse(name: String?): WatchTier =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        /** The strongest tier among a story's symbols; null when none are followed. */
        fun strongest(symbols: List<String>, tiers: Map<String, WatchTier>): WatchTier? =
            symbols.mapNotNull { tiers[it] }.maxByOrNull { it.order }
    }
}
