package com.abhinavxt.newsforge.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCacheTest {

    private fun headers(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = pairs.toMap()
        return { name -> map[name] }
    }

    @Test
    fun noValidatorsMeansAnUnconditionalRequest() {
        assertTrue(HttpCache.conditionalHeaders(null).isEmpty())
        assertTrue(HttpCache.conditionalHeaders(CacheValidators()).isEmpty())
        assertTrue(HttpCache.conditionalHeaders(CacheValidators("", "  ")).isEmpty())
    }

    @Test
    fun etagBecomesIfNoneMatch() {
        val sent = HttpCache.conditionalHeaders(CacheValidators(etag = "\"abc123\""))
        assertEquals(mapOf(HttpCache.HEADER_IF_NONE_MATCH to "\"abc123\""), sent)
    }

    @Test
    fun lastModifiedIsUsedOnlyWhenThereIsNoEtag() {
        val date = "Wed, 09 Sep 2026 09:00:00 GMT"
        assertEquals(
            mapOf(HttpCache.HEADER_IF_MODIFIED_SINCE to date),
            HttpCache.conditionalHeaders(CacheValidators(lastModified = date)),
        )
        // ETag is the stronger validator, and some servers mishandle receiving both.
        val both = HttpCache.conditionalHeaders(CacheValidators(etag = "\"x\"", lastModified = date))
        assertEquals(setOf(HttpCache.HEADER_IF_NONE_MATCH), both.keys)
    }

    @Test
    fun validatorsAreReadFromTheResponse() {
        val validators = HttpCache.validatorsFrom(
            headers(
                HttpCache.HEADER_ETAG to "\"v2\"",
                HttpCache.HEADER_LAST_MODIFIED to "Wed, 09 Sep 2026 09:00:00 GMT",
            )
        )
        assertEquals("\"v2\"", validators.etag)
        assertFalse(validators.isEmpty)
    }

    @Test
    fun aMissingValidatorFallsBackToWhatWeAlreadyHad() {
        // CDNs intermittently drop the ETag; forgetting it would turn every later poll
        // into a full download.
        val previous = CacheValidators(etag = "\"v1\"", lastModified = "old")
        val validators = HttpCache.validatorsFrom(headers(), previous)
        assertEquals("\"v1\"", validators.etag)
        assertEquals("old", validators.lastModified)
    }

    @Test
    fun aFreshValidatorOverridesThePreviousOne() {
        val previous = CacheValidators(etag = "\"v1\"")
        val validators = HttpCache.validatorsFrom(headers(HttpCache.HEADER_ETAG to "\"v2\""), previous)
        assertEquals("\"v2\"", validators.etag)
        assertNull(validators.lastModified)
    }

    @Test
    fun blankHeaderValuesAreTreatedAsAbsent() {
        val validators = HttpCache.validatorsFrom(headers(HttpCache.HEADER_ETAG to "   "))
        assertTrue(validators.isEmpty)
    }

    @Test
    fun oversizedBodiesAreRejectedAndUnknownSizesAreNot() {
        assertTrue(HttpCache.isTooLarge(HttpCache.MAX_BODY_BYTES + 1))
        assertFalse(HttpCache.isTooLarge(HttpCache.MAX_BODY_BYTES))
        assertFalse(HttpCache.isTooLarge(null))
    }
}
