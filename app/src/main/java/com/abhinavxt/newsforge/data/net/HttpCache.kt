package com.abhinavxt.newsforge.data.net

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Cache validators carried between polls of the same feed. */
data class CacheValidators(
    val etag: String? = null,
    val lastModified: String? = null,
) {
    val isEmpty: Boolean get() = etag.isNullOrBlank() && lastModified.isNullOrBlank()
}

/**
 * Conditional-request bookkeeping, kept separate from OkHttp so it can be unit-tested.
 *
 * Worth doing properly: a feed polled every fifteen minutes all day is ~60 requests, and
 * with validators most of them come back as a 304 with no body. That is the difference
 * between a few hundred kilobytes a day and several megabytes, which on mobile data
 * during market hours is the whole reason to bother.
 */
object HttpCache {

    /** Feeds are tens of kilobytes; anything past this is not a feed. */
    const val MAX_BODY_BYTES: Long = 8L * 1024 * 1024

    const val HEADER_ETAG = "ETag"
    const val HEADER_LAST_MODIFIED = "Last-Modified"
    const val HEADER_IF_NONE_MATCH = "If-None-Match"
    const val HEADER_IF_MODIFIED_SINCE = "If-Modified-Since"

    fun conditionalHeaders(validators: CacheValidators?): Map<String, String> {
        if (validators == null || validators.isEmpty) return emptyMap()
        val headers = LinkedHashMap<String, String>(2)
        validators.etag?.takeIf { it.isNotBlank() }?.let { headers[HEADER_IF_NONE_MATCH] = it }
        // Only send If-Modified-Since when there is no ETag: the ETag is the stronger
        // validator and some servers get confused by receiving both.
        if (!headers.containsKey(HEADER_IF_NONE_MATCH)) {
            validators.lastModified?.takeIf { it.isNotBlank() }
                ?.let { headers[HEADER_IF_MODIFIED_SINCE] = it }
        }
        return headers
    }

    /**
     * Reads validators out of a response.
     *
     * @param previous carried forward when the response omits a validator it sent before,
     *   which servers behind a CDN do intermittently. Dropping it would silently turn
     *   every subsequent poll into a full download.
     */
    fun validatorsFrom(
        header: (String) -> String?,
        previous: CacheValidators? = null,
    ): CacheValidators = CacheValidators(
        etag = header(HEADER_ETAG)?.takeIf { it.isNotBlank() } ?: previous?.etag,
        lastModified = header(HEADER_LAST_MODIFIED)?.takeIf { it.isNotBlank() }
            ?: previous?.lastModified,
    )

    /**
     * @return true when the declared body size is implausible for a feed.
     *
     * A cheap early exit only. `Content-Length` is a hint, and a chunked response omits
     * it entirely, so this cannot be the limit — see [readBounded].
     */
    fun isTooLarge(contentLength: Long?): Boolean =
        contentLength != null && contentLength > MAX_BODY_BYTES

    /**
     * Reads a response body into memory, refusing rather than truncating past [limit].
     *
     * The declared length check above only holds for well-behaved servers; anything
     * chunked sails past it, and the bytes then go straight into a DOM parser. A feed URL
     * that starts redirecting to a video, or a block page that streams, should fail the
     * poll rather than take the process down with it.
     *
     * Truncating instead of refusing would be worse than either: a half-read feed parses
     * as a short feed, and the missing items look like the publisher went quiet.
     *
     * @return the bytes, or null when the stream ran past [limit].
     */
    fun readBounded(stream: InputStream, limit: Long = MAX_BODY_BYTES): ByteArray? {
        val out = ByteArrayOutputStream(DEFAULT_CHUNK_BYTES)
        val chunk = ByteArray(DEFAULT_CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private const val DEFAULT_CHUNK_BYTES = 16 * 1024
}
