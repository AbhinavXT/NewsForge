package com.abhinavxt.newsforge.core.feed

/**
 * Decides which built-in feeds a given install is still missing.
 *
 * The naive rule — seed only when the table is empty — silently strands anyone who
 * installed before a built-in was added. That is exactly what happened when the NSE feeds
 * shipped: existing installs had a populated table, seeding never ran again, and the new
 * feeds simply never appeared.
 *
 * The naive fix is worse. Merging the defaults on every launch resurrects feeds the user
 * deliberately deleted, which is the most irritating behaviour an app of this kind can
 * have. So the decision needs three inputs, not two: what ships, what is in the table, and
 * what this install has *ever* been offered.
 */
object FeedSeeding {

    /**
     * @param shipped ids in the current [DefaultFeeds.ALL].
     * @param existing ids already in the feed table.
     * @param everSeeded ids this install has been offered at some point, deleted or not.
     * @return ids to insert now.
     */
    fun missingIds(
        shipped: List<String>,
        existing: Set<String>,
        everSeeded: Set<String>,
    ): List<String> = shipped.filter { it !in existing && it !in everSeeded }

    /**
     * What to record as offered after seeding.
     *
     * Everything shipped, including ids skipped because they were already present. On an
     * install that predates this bookkeeping the table is the only evidence of what was
     * offered before, so recording the whole shipped set now is what stops the *next*
     * upgrade re-offering feeds this user has already seen and removed.
     */
    fun seededAfter(shipped: List<String>, everSeeded: Set<String>): Set<String> =
        everSeeded + shipped

    /**
     * Id of the one-time correction that switches the NSE filing feeds on.
     *
     * A correction, not a migration. The seeding rules above are about which feeds an
     * install has been *offered*, and by those rules the NSE feeds were offered long ago
     * — they simply arrived switched off because the session handling was not ready. No
     * amount of re-seeding fixes that, because re-seeding is precisely what must not
     * happen to a feed the user has already seen.
     *
     * Versioned in the name so a future correction is a new id rather than a rerun of
     * this one.
     */
    const val ENABLE_NSE_FEEDS = "enable-nse-feeds-v1"

    /**
     * @param correction the id being considered.
     * @param applied corrections this install has already run.
     * @param candidates ids the correction would touch.
     * @param existing ids actually present in the feed table.
     * @return ids to switch on now — empty once the correction has run, so a feed the
     *   user turns off afterwards stays off.
     */
    fun correctionTargets(
        correction: String,
        applied: Set<String>,
        candidates: List<String>,
        existing: Set<String>,
    ): List<String> =
        if (correction in applied) emptyList() else candidates.filter { it in existing }
}
