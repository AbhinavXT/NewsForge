package com.abhinavxt.newsforge.core.model

/**
 * Event taxonomy for a headline.
 *
 * [weight] is a multiplier used by the ranker and encodes "how likely is this to move a
 * price today". It is deliberately hand-tuned rather than learned: it needs to be
 * explainable and editable, and there is no labelled dataset here.
 *
 * [group] is the coarse bucket the UI filter chips will use, so the chip row stays short
 * even as the taxonomy grows.
 */
enum class Category(
    val label: String,
    val weight: Double,
    val group: CategoryGroup,
) {
    /** Board approves / signs / bags an order, contract, LOI, or large customer win. */
    ORDER_WIN("Order win", 1.6, CategoryGroup.MOVERS),

    /** Acquisition, merger, stake sale, demerger, open offer. */
    MERGER("M&A", 1.7, CategoryGroup.MOVERS),

    /** Quarterly / annual numbers, and guidance revisions. */
    RESULTS("Results", 1.5, CategoryGroup.RESULTS),

    /** Dividend declarations and record/ex dates. */
    DIVIDEND("Dividend", 1.3, CategoryGroup.MOVERS),

    /** Bulk/block deals, promoter pledge or stake changes, large investor entries. */
    BLOCK_DEAL("Block deal", 1.4, CategoryGroup.MOVERS),

    /** SEBI / ED / CBI / IT action, probe, auditor resignation, exchange penalty. */
    REGULATORY("Regulatory", 1.8, CategoryGroup.MOVERS),

    /** Credit rating upgrade / downgrade / watch. */
    RATING("Rating", 1.3, CategoryGroup.MOVERS),

    /** CEO/CFO/MD appointment or exit, board changes. */
    MANAGEMENT("Management", 1.2, CategoryGroup.MOVERS),

    /** QIP, rights issue, preferential allotment, IPO, buyback, fundraise approval. */
    FUNDRAISE("Fundraise", 1.3, CategoryGroup.MOVERS),

    /** Government policy: budget, GST, PLI, tariffs, ministry announcements. */
    POLICY("Policy", 1.4, CategoryGroup.POLICY),

    /** RBI, inflation, GDP, IIP, rates, rupee, FII/DII flows. */
    MACRO("Macro", 1.2, CategoryGroup.POLICY),

    /** Crude, gold, metals, freight — the inputs that reprice whole sectors. */
    COMMODITY("Commodity", 1.1, CategoryGroup.GLOBAL),

    /** Fed/ECB, US and Asian markets, global corporate news. */
    GLOBAL("Global", 1.0, CategoryGroup.GLOBAL),

    /** War, sanctions, shipping lanes, elections abroad. */
    GEOPOLITICS("Geopolitics", 1.1, CategoryGroup.GLOBAL),

    /** Matched nothing. Still shown, just ranked below everything that matched. */
    OTHER("General", 0.7, CategoryGroup.OTHER),
}

/** Coarse buckets backing the UI filter chips. */
enum class CategoryGroup(val label: String) {
    MOVERS("Movers"),
    RESULTS("Results"),
    POLICY("Policy"),
    GLOBAL("Global"),
    OTHER("General"),
}
