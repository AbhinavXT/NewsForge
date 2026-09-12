package com.abhinavxt.newsforge.core.feed

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.tag.Categorizer
import com.abhinavxt.newsforge.core.tag.SeedSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class NseFilingsTest {

    @Test
    fun normalisesAnAnnouncement() {
        val item = NseFilings.normalize(
            FeedKind.NSE_ANNOUNCEMENT,
            mapOf(
                "symbol" to "TATASTEEL",
                "desc" to "Financial Results for the quarter ended June 2026",
                "attchmntText" to "Unaudited standalone and consolidated results",
                "an_dt" to "05-Sep-2026 14:32:10",
                "attchmntFile" to "https://nsearchives.nseindia.com/corporate/x.pdf",
            ),
        )!!
        assertTrue(item.title.startsWith("TATASTEEL — Financial Results"))
        assertEquals("https://nsearchives.nseindia.com/corporate/x.pdf", item.link)
        assertEquals("NSE", item.sourceName)
        assertEquals(
            OffsetDateTime.parse("2026-09-05T14:32:10+05:30").toInstant().toEpochMilli(),
            item.publishedAtMillis,
        )
    }

    @Test
    fun fallsBackThroughTheAlternateKeyNames() {
        // The same logical field arrives under different names depending on the day.
        val item = NseFilings.normalize(
            FeedKind.NSE_ANNOUNCEMENT,
            mapOf(
                "sm_symbol" to "infy",
                "subject" to "Outcome of board meeting",
                "sm_desc" to "Dividend declared",
                "sort_date" to "2026-09-05T09:20:00",
            ),
        )!!
        assertTrue(item.title.startsWith("INFY —"))
        assertNotNull(item.publishedAtMillis)
    }

    @Test
    fun treatsALiteralDashAsAbsent() {
        assertEquals("fallback", NseFilings.first(mapOf("a" to "-", "b" to "fallback"), "a", "b"))
        assertEquals("", NseFilings.first(mapOf("a" to "  "), "a"))
    }

    @Test
    fun normalisesACorporateAction() {
        val item = NseFilings.normalize(
            FeedKind.NSE_CORP_ACTION,
            mapOf(
                "symbol" to "ITC",
                "subject" to "Dividend - Rs 6.25 per share",
                "exDate" to "12-Sep-2026",
            ),
        )!!
        assertEquals("Ex-date: 12-Sep-2026", item.summary)
        assertTrue(item.title.contains("Dividend"))
    }

    @Test
    fun normalisesABoardMeeting() {
        val item = NseFilings.normalize(
            FeedKind.NSE_BOARD_MEETING,
            mapOf(
                "bm_symbol" to "RELIANCE",
                "bm_purpose" to "Quarterly Results",
                "bm_desc" to "To consider unaudited financial results",
                "bm_date" to "18-Sep-2026",
            ),
        )!!
        assertTrue(item.title.startsWith("RELIANCE — Quarterly Results"))
        assertNotNull(item.publishedAtMillis)
    }

    @Test
    fun dropsRowsWithNoSymbolOrHeadline() {
        assertNull(
            NseFilings.normalize(FeedKind.NSE_ANNOUNCEMENT, mapOf("desc" to "Something"))
        )
        assertNull(
            NseFilings.normalize(FeedKind.NSE_ANNOUNCEMENT, mapOf("symbol" to "INFY"))
        )
    }

    @Test
    fun dropsExchangePaperwork() {
        // The exchange feed is mostly this. Excluding by keyword would mean chasing every
        // new species of noise; requiring a positive match ignores new noise by default.
        assertFalse(NseFilings.isMaterial("Allotment of shares under ESOP", ""))
        assertFalse(NseFilings.isMaterial("Closure of trading window", ""))
        assertFalse(NseFilings.isMaterial("Newspaper publication of results", ""))
        assertFalse(NseFilings.isMaterial("Loss of share certificate", ""))
        assertFalse(NseFilings.isMaterial("Change of registered office", ""))
    }

    @Test
    fun exclusionBeatsAKeywordMatch() {
        // "Newspaper publication of financial results" matches a material keyword and
        // must still be dropped: it is a copy of a filing already seen.
        assertFalse(NseFilings.isMaterial("Newspaper publication of financial results", ""))
    }

    @Test
    fun keepsTheFilingsThatMoveAPrice() {
        assertTrue(NseFilings.isMaterial("Financial Results for Q1 FY27", ""))
        assertTrue(NseFilings.isMaterial("Dividend", ""))
        assertTrue(NseFilings.isMaterial("Board approves buyback", ""))
        assertTrue(NseFilings.isMaterial("Scheme of amalgamation", ""))
        assertTrue(NseFilings.isMaterial("Intimation of earnings call", ""))
        // Matched via the detail column, not the headline.
        assertTrue(NseFilings.isMaterial("Outcome of meeting", "record date fixed"))
    }

    @Test
    fun filingsWithNoAttachmentGetADistinctLandingUrl() {
        val a = NseFilings.landingUrl(FeedKind.NSE_CORP_ACTION, "ITC", "Dividend", "12-Sep-2026")
        val b = NseFilings.landingUrl(FeedKind.NSE_CORP_ACTION, "INFY", "Dividend", "12-Sep-2026")
        // The URL is the article's identity, so two filings must never share one.
        assertNotEquals(a, b)
        assertTrue(a.startsWith("https://www.nseindia.com/"))
        assertEquals(a, NseFilings.landingUrl(FeedKind.NSE_CORP_ACTION, "ITC", "Dividend", "12-Sep-2026"))
    }

    @Test
    fun theIdentityQueryParameterSurvivesUrlCanonicalisation() {
        val url = NseFilings.landingUrl(FeedKind.NSE_CORP_ACTION, "ITC", "Dividend", "12-Sep")
        val canonical = com.abhinavxt.newsforge.core.dedupe.UrlCanonicalizer.canonicalize(url)
        assertTrue("nfid was stripped: $canonical", canonical.contains("nfid="))
    }

    @Test
    fun theSymbolIsPrependedSoTaggingWorksOnWordlessFilings() {
        // "Outcome of board meeting" never names the company; without the prefix the
        // filing would carry no symbol at all.
        val item = NseFilings.normalize(
            FeedKind.NSE_BOARD_MEETING,
            mapOf(
                "bm_symbol" to "TATASTEEL",
                "bm_purpose" to "Outcome of board meeting",
                "bm_date" to "18-Sep-2026",
            ),
        )!!
        assertEquals(listOf("TATASTEEL"), SeedSymbols.LEXICON.match(item.title))
    }

    @Test
    fun categorisationOfFilingsLandsInTheRightBuckets() {
        assertEquals(
            Category.RESULTS,
            Categorizer.categorize("TATASTEEL — Financial Results for the quarter"),
        )
        assertEquals(Category.DIVIDEND, Categorizer.categorize("ITC — Dividend Rs 6.25 per share"))
        assertEquals(Category.FUNDRAISE, Categorizer.categorize("XYZ — Board approves buyback"))
    }

    @Test
    fun parseSkipsUnusableRowsRatherThanFailing() {
        val items = NseFilings.parse(
            FeedKind.NSE_ANNOUNCEMENT,
            listOf(
                mapOf("symbol" to "A", "desc" to "Financial Results Q1"),
                emptyMap(),
                mapOf("symbol" to "B", "desc" to "Closure of trading window"),
                mapOf("symbol" to "C", "desc" to "Dividend declared"),
            ),
        )
        assertEquals(2, items.size)
    }
}
