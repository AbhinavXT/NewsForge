package com.abhinavxt.newsforge.core.notify

/**
 * How much a followed company is allowed to interrupt you, set per company.
 *
 * Separate from [WatchTier], which says how exposed you are. The two are often related and
 * not the same: a core holding you want every headline on, and a leveraged position whose
 * news you would rather read on your own schedule, are both reasonable.
 *
 * Replaced a single "every story on your watchlist" switch, which applied to the whole
 * list when most people want it for two or three names.
 */
enum class CompanyAlertLevel(val label: String) {
    /** Every fresh story about the company, whatever it scores. */
    ALL("All news"),

    /** Stories that clear the company's tier bar. What every followed name got before. */
    MATERIAL("Material only"),

    /**
     * No alerts about this company — stories, reminders or price moves.
     *
     * Not a mute: its stories still appear in the feed, and a story about it that would
     * clear the market-wide bar on its own still alerts as market news. Silent removes the
     * watchlist's extra attention, it does not hide the company.
     */
    SILENT("Silent"),
    ;

    companion object {
        val DEFAULT: CompanyAlertLevel = MATERIAL

        fun parse(name: String?): CompanyAlertLevel =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        /**
         * The watchlist with silenced companies taken out.
         *
         * Every alert path judges exposure through this rather than the raw watchlist, so
         * "silent" means the same thing to stories, reminders and price moves.
         */
        fun audible(
            watchlist: Map<String, WatchTier>,
            levels: Map<String, CompanyAlertLevel>,
        ): Map<String, WatchTier> =
            if (levels.isEmpty()) watchlist
            else watchlist.filterKeys { levels[it] != SILENT }
    }
}
