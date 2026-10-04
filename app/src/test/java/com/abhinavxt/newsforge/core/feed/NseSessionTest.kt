package com.abhinavxt.newsforge.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NseSessionTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun eachFilingEndpointIsCalledFromItsOwnPage() {
        assertEquals(
            NseSession.ANNOUNCEMENTS_PAGE,
            NseSession.refererFor(FeedKind.NSE_ANNOUNCEMENT),
        )
        assertEquals(
            NseSession.CORP_ACTIONS_PAGE,
            NseSession.refererFor(FeedKind.NSE_CORP_ACTION),
        )
        assertEquals(
            NseSession.BOARD_MEETINGS_PAGE,
            NseSession.refererFor(FeedKind.NSE_BOARD_MEETING),
        )
    }

    @Test
    fun warmupStartsAtTheHomePage() {
        // Order matters: the section page is fetched with the home page's cookies
        // already held, which is the sequence a browser produces.
        assertEquals(NseSession.HOME, NseSession.WARMUP_PAGES.first())
        assertTrue(NseSession.WARMUP_PAGES.size >= 2)
    }

    @Test
    fun rejectionStatusesAreTheOnesWorthRetrying() {
        assertTrue(NseSession.isRejectionStatus(401))
        assertTrue(NseSession.isRejectionStatus(403))
        assertTrue(NseSession.isRejectionStatus(429))
        // A 404 is a wrong URL, and re-priming would only hide that behind a retry.
        assertFalse(NseSession.isRejectionStatus(404))
        assertFalse(NseSession.isRejectionStatus(500))
        assertFalse(NseSession.isRejectionStatus(200))
    }

    @Test
    fun jsonIsNotABlock() {
        assertFalse(NseSession.isBlocked(bytes("""{"data":[]}""")))
        assertFalse(NseSession.isBlocked(bytes("[]")))
        assertFalse(NseSession.isBlocked(bytes("  \n\t[{\"symbol\":\"INFY\"}]")))
        // A byte-order mark ahead of valid JSON is still valid JSON.
        assertFalse(NseSession.isBlocked(bytes("\uFEFF{\"data\":[]}")))
    }

    @Test
    fun htmlServedWithA200IsTheBlockPage() {
        assertTrue(NseSession.isBlocked(bytes("<!DOCTYPE html><html><body>Access Denied")))
        assertTrue(NseSession.isBlocked(bytes("<html><head><title>Request Rejected")))
        // Whatever wording the block page uses, the shape is what gives it away.
        assertTrue(NseSession.isBlocked(bytes("Resource unavailable")))
    }

    @Test
    fun anEmptyBodyCountsAsBlocked() {
        assertTrue(NseSession.isBlocked(ByteArray(0)))
        assertTrue(NseSession.isBlocked(bytes("   \n  ")))
    }
}
