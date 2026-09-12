package com.abhinavxt.newsforge.core.tag

import com.abhinavxt.newsforge.core.model.Category
import org.junit.Assert.assertEquals
import org.junit.Test

class CategorizerTest {

    private fun categorize(title: String) = Categorizer.categorize(title)

    @Test
    fun detectsRegulatoryAction() {
        assertEquals(Category.REGULATORY, categorize("SEBI bars three entities from the market"))
        assertEquals(Category.REGULATORY, categorize("Enforcement Directorate raids firm's Mumbai office"))
        assertEquals(Category.REGULATORY, categorize("Statutory auditor resigns citing lack of information"))
    }

    @Test
    fun detectsOrderWinsWithValueBetweenVerbAndNoun() {
        assertEquals(Category.ORDER_WIN, categorize("BEL bags Rs 500 crore order from Indian Navy"))
        assertEquals(Category.ORDER_WIN, categorize("Tata Steel wins Rs 2,000 crore contract"))
        assertEquals(Category.ORDER_WIN, categorize("Company emerges L1 bidder for highway package"))
    }

    @Test
    fun detectsResults() {
        assertEquals(Category.RESULTS, categorize("Reliance Q2 net profit rises 12% YoY"))
        assertEquals(Category.RESULTS, categorize("Infosys raises FY27 revenue guidance"))
    }

    @Test
    fun detectsDeals() {
        assertEquals(Category.MERGER, categorize("HDFC Bank acquires stake in fintech startup"))
        assertEquals(Category.MERGER, categorize("Board approves demerger of the consumer business"))
        assertEquals(Category.BLOCK_DEAL, categorize("Promoter sells 2% via block deal"))
    }

    @Test
    fun detectsFundraisingAndRatings() {
        assertEquals(Category.FUNDRAISE, categorize("Board approves Rs 3,000 crore QIP"))
        assertEquals(Category.RATING, categorize("CRISIL upgrades long-term rating to AA"))
    }

    @Test
    fun detectsMacroBeforePolicyForMonetaryStories() {
        // "monetary policy" contains "policy"; macro must win or every RBI story misfiles.
        assertEquals(Category.MACRO, categorize("RBI keeps repo rate unchanged in monetary policy review"))
        assertEquals(Category.MACRO, categorize("CPI inflation eases to 4.1% in August"))
    }

    @Test
    fun detectsPolicy() {
        assertEquals(Category.POLICY, categorize("Cabinet approves PLI scheme for semiconductors"))
        assertEquals(Category.POLICY, categorize("Government raises import duty on edible oil"))
    }

    @Test
    fun detectsGlobalAndCommodity() {
        assertEquals(Category.GLOBAL, categorize("Fed signals one more rate cut this year"))
        assertEquals(Category.COMMODITY, categorize("Brent crude slips below \$70 a barrel"))
    }

    @Test
    fun theMoreDecisiveEventWinsWhenTwoAppear() {
        // An order win is irrelevant next to a regulator freezing the company's accounts.
        assertEquals(
            Category.REGULATORY,
            categorize("SEBI probes company that recently bagged Rs 900 crore order"),
        )
    }

    @Test
    fun fallsBackToTheFeedHintThenOther() {
        assertEquals(
            Category.POLICY,
            Categorizer.categorize("Secretary addresses industry gathering", hint = Category.POLICY),
        )
        assertEquals(Category.OTHER, categorize("Secretary addresses industry gathering"))
    }

    @Test
    fun readsTheSummaryWhenTheHeadlineIsUninformative() {
        assertEquals(
            Category.RESULTS,
            Categorizer.categorize(
                title = "Company update",
                summary = "The firm reported Q2 net profit of Rs 210 crore.",
            ),
        )
    }
}
