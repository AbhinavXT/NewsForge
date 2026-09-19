package com.abhinavxt.newsforge.data.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Outcome of one feed poll. */
sealed interface FetchResult {

    /** Body downloaded. [validators] are stored for the next poll. */
    data class Success(val bytes: ByteArray, val validators: CacheValidators) : FetchResult {
        // ByteArray in a data class needs these; the generated ones compare references.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && bytes.contentEquals(other.bytes) &&
                validators == other.validators)

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + validators.hashCode()
    }

    /** Server confirmed nothing changed. Cheapest possible poll. */
    data class NotModified(val validators: CacheValidators) : FetchResult

    /** @param status HTTP status, or -1 when the request never completed. */
    data class Failure(val status: Int, val message: String) : FetchResult
}

/**
 * Fetches a feed over HTTP with conditional-request support.
 *
 * Uses `enqueue` inside [suspendCancellableCoroutine] rather than a blocking `execute` on
 * `Dispatchers.IO`, so that cancelling the sync — the user backgrounding the app mid
 * refresh, or WorkManager stopping the worker — actually cancels the in-flight call
 * instead of leaving it to finish into a dead scope.
 */
class FeedFetcher(private val client: OkHttpClient) {

    /**
     * Primes a session against NSE before the first API call of a run.
     *
     * NSE's JSON endpoints reject a request that arrives without the cookies a browser
     * would have picked up from the site itself — the symptom is a 401 or an HTML block
     * page served with a 200, not a clean error. Fetching the landing page first and
     * keeping its cookies is what makes the API usable at all.
     */
    suspend fun primeNse(): Boolean {
        val result = fetch(NSE_HOME, null, referer = null, prime = false)
        return result is FetchResult.Success || result is FetchResult.NotModified
    }

    suspend fun fetch(
        url: String,
        validators: CacheValidators?,
        referer: String? = null,
        prime: Boolean = false,
        bearer: String? = null,
        /**
         * Sent as a plain-text POST body, which makes this a publish rather than a fetch.
         *
         * The one place the app writes to the network instead of reading from it. It goes
         * through the same call path deliberately: the timeouts, the size guard and the
         * error shape are all things a publish wants too, and a second client would have
         * to reimplement them to be equally careful.
         */
        body: String? = null,
    ): FetchResult {
        if (prime) primeNse()
        return request(url, validators, referer, bearer, body)
    }

    private suspend fun request(
        url: String,
        validators: CacheValidators?,
        referer: String?,
        bearer: String? = null,
        body: String? = null,
    ): FetchResult =
        suspendCancellableCoroutine { continuation ->
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT)
            // NSE checks both; a request without them reads as automated and is blocked.
            // Only sent when configured, so the default public server is polled
            // anonymously as it expects.
            if (!bearer.isNullOrBlank()) builder.header("Authorization", "Bearer $bearer")
            if (referer != null) {
                builder.header("Referer", referer)
                builder.header("Accept-Language", "en-US,en;q=0.9")
                builder.header("X-Requested-With", "XMLHttpRequest")
            }
            for ((name, value) in HttpCache.conditionalHeaders(validators)) {
                builder.header(name, value)
            }

            if (body != null) builder.post(body.toRequestBody(TEXT_PLAIN))

            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Keyed on the continuation rather than on `call.isCanceled()`: a call
                    // cancelled by anything other than our own `invokeOnCancellation` would
                    // otherwise return here without resuming, and nothing else would ever
                    // complete this coroutine. Resuming a cancelled one is a no-op.
                    if (!continuation.isActive) return
                    continuation.resume(
                        FetchResult.Failure(-1, e.message ?: e.javaClass.simpleName)
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    // Anything thrown here would land on an OkHttp dispatcher thread with
                    // the continuation still suspended — the sync would hang until the
                    // scope was cancelled, with no timeout to save it. A failed read is a
                    // failed poll, which the caller already knows how to record.
                    val result = try {
                        readResponse(response, validators)
                    } catch (e: Exception) {
                        // The body is already closed: readResponse wraps everything in
                        // `response.use`, whose finally runs on the way out.
                        FetchResult.Failure(-1, e.message ?: e.javaClass.simpleName)
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }

    private fun readResponse(response: Response, previous: CacheValidators?): FetchResult =
        response.use {
            val fresh = HttpCache.validatorsFrom({ name -> response.header(name) }, previous)

            if (response.code == HTTP_NOT_MODIFIED) {
                return FetchResult.NotModified(fresh)
            }
            if (!response.isSuccessful) {
                return FetchResult.Failure(response.code, response.message.ifBlank { "HTTP error" })
            }

            val body = response.body ?: return FetchResult.Failure(response.code, "Empty body")
            // Declared length first, so an honest server saves us the download entirely.
            if (HttpCache.isTooLarge(body.contentLength().takeIf { it >= 0 })) {
                return FetchResult.Failure(response.code, "Body larger than the feed limit")
            }
            val bytes = try {
                // Then the real limit, because a chunked response declares nothing.
                HttpCache.readBounded(body.byteStream())
                    ?: return FetchResult.Failure(response.code, "Body larger than the feed limit")
            } catch (e: IOException) {
                return FetchResult.Failure(response.code, e.message ?: "Read failed")
            }
            if (bytes.isEmpty()) {
                return FetchResult.Failure(response.code, "Empty body")
            }
            FetchResult.Success(bytes, fresh)
        }

    companion object {
        private const val HTTP_NOT_MODIFIED = 304

        /**
         * An honest, identifiable agent. Feeds are a published interface and several
         * publishers reject the default OkHttp string outright.
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36 NewsForge/0.1"

        private const val ACCEPT =
            "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5"

        const val NSE_HOME = "https://www.nseindia.com/"

        private val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()

        /**
         * In-memory cookie jar.
         *
         * Deliberately not persisted: NSE's session cookies are short-lived, and a stale
         * one carried across a restart fails in the same silent way as having none. Each
         * process primes once and re-primes when a call is rejected.
         */
        private class SessionCookies : CookieJar {
            /**
             * Concurrent because it has to be: OkHttp calls a [CookieJar] from whichever
             * dispatcher thread runs the call, and a refresh has several in flight.
             */
            private val store = ConcurrentHashMap<String, Map<String, Cookie>>()

            /**
             * Merged by name rather than replaced wholesale.
             *
             * NSE hands out its session across more than one response, so overwriting the
             * host's list each time meant a later response carrying a single cookie
             * silently dropped the ones priming had just collected — which shows up as a
             * 401, or as an HTML block page served with a 200.
             */
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (cookies.isEmpty()) return
                store.compute(keyFor(url)) { _, held ->
                    val merged = LinkedHashMap(held.orEmpty())
                    for (cookie in cookies) merged[cookie.name] = cookie
                    merged
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val key = keyFor(url)
                val held = store[key] ?: return emptyList()
                val now = System.currentTimeMillis()
                // An expired cookie is worse than none: it is accepted and then rejected
                // downstream, which reads as a block rather than as a stale session.
                val live = held.filterValues { it.expiresAt > now }
                if (live.size != held.size) store[key] = live
                // Each cookie still decides for itself whether it belongs on this
                // request. The key groups a site's cookies; `matches` is what stops one
                // scoped to a single host leaking across the rest of the domain.
                return live.values.filter { it.matches(url) }
            }

            /**
             * Keyed on the site, not the host.
             *
             * A session primed at www.nseindia.com has to be sent to
             * nsearchives.nseindia.com, which serves the company list and gates on it
             * exactly the same way. Stripping "www." only made those two different keys,
             * so the archives request went out with no cookies at all and came back
             * looking like a block.
             *
             * Last two labels is the registrable domain for every host this app talks to.
             * It would over-group under a multi-part suffix like .co.in — and `matches`
             * above is what keeps that from mattering, since a cookie scoped to one host
             * is still refused on the others.
             */
            private fun keyFor(url: HttpUrl): String =
                url.host.split('.').takeLast(2).joinToString(".")
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(SessionCookies())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }
}
