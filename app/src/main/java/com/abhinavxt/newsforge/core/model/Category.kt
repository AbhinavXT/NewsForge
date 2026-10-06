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

    /**
     * A broker's price target, rating call or coverage note on a stock.
     *
     * Kept apart from [RATING], which is a credit agency's verdict on a company's debt —
     * a different question, from a different body, with different consequences. Sharing
     * a bucket would put "CRISIL downgrades to A-" next to "Jefferies sees 30% upside"
     * under one heading.
     *
     * Weighted below every category that describes something that actually happened. A
     * broker raising a target is an opinion about a company; an order win is the company.
     * They routinely move a stock more than the events do, which is a fact about the
     * market rather than a reason to rank them as news.
     */
    PRICE_TARGET("Target", 1.1, CategoryGroup.VIEWS),

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

    // ---------------------------------------------------------------- world desk
    //
    // General news, read for its own sake rather than for what it does to a price. These
    // come only from a feed's hint — the text rules above are about market events and have
    // nothing useful to say about a science story — and they never reach the market feed,
    // the alert path or the learned model. See [Desk].
    //
    // Weights are close together on purpose. Nothing here is "more likely to move a
    // price"; the small spread only lets the day's hard news edge ahead of features when
    // recency and coverage are otherwise equal.

    /** National news: Parliament, states, courts, public life in India. */
    INDIA("India", 1.1, CategoryGroup.NEWS),

    /** Elections, parties, government and opposition, at home or abroad. */
    POLITICS("Politics", 1.1, CategoryGroup.NEWS),

    /** International affairs: diplomacy, conflict, other countries' news. */
    WORLD("World", 1.1, CategoryGroup.NEWS),

    /** Research, space, discoveries. */
    SCIENCE("Science", 1.0, CategoryGroup.NEWS),

    /** Technology as a subject: products, platforms, AI, the internet. */
    TECHNOLOGY("Tech", 1.0, CategoryGroup.NEWS),

    /** Medicine, public health, outbreaks. */
    HEALTH("Health", 1.0, CategoryGroup.NEWS),

    /** Climate, weather extremes, the environment. */
    ENVIRONMENT("Climate", 1.0, CategoryGroup.NEWS),

    SPORTS("Sports", 0.9, CategoryGroup.NEWS),

    /** Film, books, music, the arts. */
    CULTURE("Culture", 0.9, CategoryGroup.NEWS),
    ;

    val desk: Desk get() = group.desk

    companion object {
        /**
         * The market taxonomy, in its original order.
         *
         * Anything that enumerates categories for the market side — the learned model's
         * feature vector above all — iterates this rather than [entries]. Adding the world
         * topics to that vector would change its length and orphan every weight the model
         * has already learned.
         */
        val MARKETS: List<Category> = entries.filter { it.desk == Desk.MARKETS }

        /** The world topics, in the order the World tab shows them. */
        val WORLD_TOPICS: List<Category> = entries.filter { it.desk == Desk.WORLD }

        /** Stored names per desk, for queries that select one side of the store. */
        fun namesOn(desk: Desk): List<String> =
            entries.filter { it.desk == desk }.map { it.name }
    }
}

/**
 * Which half of the app a story belongs to.
 *
 * The market feed and the World tab read from one article store, kept apart by category
 * rather than by a column, because the category is already stored on every row and a
 * feed's topic is the only thing that decides which side it is on.
 */
enum class Desk {
    MARKETS,
    WORLD,
}

/** Coarse buckets backing the UI filter chips. */
enum class CategoryGroup(val label: String, val desk: Desk = Desk.MARKETS) {
    MOVERS("Movers"),
    RESULTS("Results"),

    /**
     * Somebody's opinion about a stock rather than news about it.
     *
     * Its own bucket so the Movers section stays things that happened. A reader
     * skimming for what changed should not have to sort a regulatory probe from a
     * brokerage note, and a reader who wants the notes can now filter to them.
     */
    VIEWS("Calls"),
    POLICY("Policy"),
    GLOBAL("Global"),
    OTHER("General"),

    /** Everything on the World tab, which filters by topic rather than by bucket. */
    NEWS("News", Desk.WORLD),
    ;

    companion object {
        /** The buckets the market feed's chips and sections are built from. */
        val MARKETS: List<CategoryGroup> = entries.filter { it.desk == Desk.MARKETS }
    }
}
