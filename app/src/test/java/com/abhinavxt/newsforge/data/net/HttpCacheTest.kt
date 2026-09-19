package com.abhinavxt.newsforge.data.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

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

    @Test
    fun aBodyWithinTheLimitIsReadWhole() {
        val body = ByteArray(5_000) { (it % 251).toByte() }
        assertArrayEquals(body, HttpCache.readBounded(ByteArrayInputStream(body), limit = 8_192))
    }

    @Test
    fun anEmptyBodyReadsAsEmptyRatherThanNull() {
        // null means "too large"; the caller reports an empty body separately.
        assertArrayEquals(ByteArray(0), HttpCache.readBounded(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun aBodyPastTheLimitIsRefusedRatherThanTruncated() {
        // This is the case Content-Length cannot catch: a chunked response declares no
        // size at all, so the limit has to hold during the read.
        val body = ByteArray(9_000)
        assertNull(HttpCache.readBounded(ByteArrayInputStream(body), limit = 8_192))
    }

    @Test
    fun theLimitIsInclusive() {
        val exact = ByteArray(8_192)
        val read = HttpCache.readBounded(ByteArrayInputStream(exact), limit = 8_192)
        assertEquals(8_192, read?.size ?: -1)
        assertNull(HttpCache.readBounded(ByteArrayInputStream(ByteArray(8_193)), limit = 8_192))
    }

    @Test
    fun aStreamThatYieldsShortReadsIsStillReadWhole() {
        // ByteArrayInputStream fills the buffer in one go; a socket does not, and the
        // loop has to keep going until it actually sees the end of the stream.
        val body = ByteArray(5_000) { (it % 251).toByte() }
        val dribbling = object : InputStream() {
            private var position = 0
            override fun read(): Int =
                if (position >= body.size) -1 else body[position++].toInt() and 0xFF

            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (position >= body.size) return -1
                val take = minOf(7, length, body.size - position)
                System.arraycopy(body, position, target, offset, take)
                position += take
                return take
            }
        }
        assertArrayEquals(body, HttpCache.readBounded(dribbling))
    }
}
