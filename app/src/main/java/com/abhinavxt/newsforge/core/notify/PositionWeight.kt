package com.abhinavxt.newsforge.core.notify

/**
 * How much of the portfolio a name actually is, and what that should change.
 *
 * The tiers say what kind of exposure you have. They say nothing about how much, so a six
 * per cent position and a half per cent starter both sit in [WatchTier.HOLDING] and
 * interrupt you identically — which is wrong in both directions at once. The large one is
 * under-alerted, and the small one contributes most of the notifications you learn to
 * ignore.
 *
 * Weight shifts the tier the alert is judged at rather than scaling its threshold. The
 * thresholds were calibrated against what the ranker actually produces; inventing a
 * multiplier on top would put a second, uncalibrated number in front of them and make
 * both harder to reason about. Saying "a six per cent holding is alerted like a leveraged
 * position" is a claim you can check.
 */
object PositionWeight {

    /**
     * At or above this share of the portfolio, a position is alerted a tier louder.
     *
     * Five per cent is roughly where a name stops being one of twenty and starts being
     * able to move the account on its own.
     */
    const val SIGNIFICANT_PERCENT: Double = 5.0

    /**
     * At or below this, a tier quieter.
     *
     * A one per cent position is a placeholder — bought to have a reason to pay attention.
     * That is worth a line in the feed and rarely worth a notification.
     */
    const val TOKEN_PERCENT: Double = 1.0

    /**
     * @param percent share of the portfolio, or null when unknown — a manually added
     *   symbol, or a desk that sends no weights. Unknown leaves the tier alone rather than
     *   guessing, because the guess would be wrong in one direction for everybody.
     */
    fun effectiveTier(tier: WatchTier, percent: Double?): WatchTier = when {
        percent == null || percent <= 0.0 -> tier
        percent >= SIGNIFICANT_PERCENT -> louder(tier)
        percent <= TOKEN_PERCENT -> quieter(tier)
        else -> tier
    }

    /**
     * The weight of the symbol that earned the tier.
     *
     * A story naming four companies is judged on the one you are most exposed to, and
     * among equals on the largest position — which is the same question the tier is
     * already answering, asked one level finer.
     */
    fun strongestWeight(
        symbols: List<String>,
        tiers: Map<String, WatchTier>,
        weights: Map<String, Double>,
    ): Double? = symbols
        .filter { it in tiers }
        .maxWithOrNull(
            compareBy<String>({ tiers.getValue(it).order }, { weights[it] ?: 0.0 })
        )
        ?.let { weights[it] }

    /** Clamped: there is nothing above leveraged, and nothing below watching. */
    private fun louder(tier: WatchTier): WatchTier =
        WatchTier.entries.filter { it.order > tier.order }.minByOrNull { it.order } ?: tier

    private fun quieter(tier: WatchTier): WatchTier =
        WatchTier.entries.filter { it.order < tier.order }.maxByOrNull { it.order } ?: tier
}
