package com.abhinavxt.newsforge.core.tag

import com.abhinavxt.newsforge.core.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Broker calls, and the things that look like them.
 *
 * The interesting half of this category is what it must not claim. "Target" is a common
 * word in market copy and belongs to a government divestment goal or a company's own
 * revenue plan far more often than to an analyst's number, and both of those are matched
 * by rules that sit *after* this one — so a loose pattern here silently empties Policy.
 */
class PriceTargetCategoryTest {

    private fun of(title: String) = Categorizer.categorize(title)

    @Test
    fun brokerUpsideCallsAreTargets() {
        assertEquals(Category.PRICE_TARGET, of("HAL, BEL, L&T: CLSA sees up to 35% upside"))
        assertEquals(Category.PRICE_TARGET, of("Analysts see 20% upside in Cochin Shipyard"))
    }

    @Test
    fun priceTargetsAreTargets() {
        assertEquals(
            Category.PRICE_TARGET,
            of("Motilal Oswal raises target price to Rs 4,200 on Tata Motors"),
        )
        assertEquals(Category.PRICE_TARGET, of("Kotak cuts price target on Zomato to 240"))
    }

    @Test
    fun ratingCallsAndCoverageAreTargets() {
        assertEquals(Category.PRICE_TARGET, of("Jefferies initiates coverage on Swiggy with Buy"))
        assertEquals(Category.PRICE_TARGET, of("Morgan Stanley upgrades ITC to Overweight"))
        assertEquals(Category.PRICE_TARGET, of("Macquarie downgrades Paytm to Underperform"))
        assertEquals(Category.PRICE_TARGET, of("Nomura maintains Buy on Infosys"))
        assertEquals(Category.PRICE_TARGET, of("GRSE gets buy call from ICICI Securities"))
    }

    @Test
    fun brokerageSentimentIsATarget() {
        assertEquals(Category.PRICE_TARGET, of("Brokerages bullish on defence stocks"))
        assertEquals(Category.PRICE_TARGET, of("Top picks for 2026: five stocks to watch"))
    }

    // ------------------------------------------------------------ not targets

    /**
     * A credit agency's verdict on debt is not a broker's view on a share price.
     *
     * The two overlap on "upgrade" and "downgrade", which is why this rule sits before
     * RATING — and why the agency names must not fire it.
     */
    @Test
    fun creditRatingsStayRatings() {
        assertEquals(Category.RATING, of("CRISIL upgrades long-term rating of Bharat Forge"))
        assertEquals(Category.RATING, of("Moody's changes outlook to stable on SBI"))
    }

    /**
     * The failure this rule was tightened to avoid.
     *
     * Asserted as "not a broker call" rather than against a specific bucket: where these
     * land is the business of the rules that already existed — a divestment reads as a
     * stake sale and lands in M&A — and pinning that here would make this test fail for
     * reasons that have nothing to do with broker calls.
     */
    @Test
    fun aTargetThatIsNotAPriceTargetIsNotClaimed() {
        assertNotEquals(
            Category.PRICE_TARGET,
            of("Government sets divestment target of Rs 50,000 crore"),
        )
        assertNotEquals(
            Category.PRICE_TARGET,
            of("Tata Power targets 30 GW renewable capacity by 2030"),
        )
        assertNotEquals(
            Category.PRICE_TARGET,
            of("Company targets Rs 10,000 crore revenue by FY28"),
        )
    }

    /** Broker houses also publish economics; matching the house would capture that too. */
    @Test
    fun anEconomicsNoteFromABrokerIsMacro() {
        assertEquals(Category.MACRO, of("HSBC cuts India GDP forecast to 6.2%"))
    }

    @Test
    fun ordinaryCorporateNewsIsUnaffected() {
        assertEquals(Category.ORDER_WIN, of("L&T bags Rs 5,000 crore order from NHAI"))
        assertEquals(Category.RESULTS, of("Reliance Q2 results beat estimates"))
        assertEquals(Category.MERGER, of("Adani acquires 26% stake in solar unit"))
    }

    /** A probe mentioning a brokerage is still a probe — REGULATORY is matched first. */
    @Test
    fun aProbeOutranksTheWordBrokerage() {
        assertEquals(Category.REGULATORY, of("SEBI probes brokerage for misselling"))
    }
}
