package com.abhinavxt.newsforge.data.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
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
    ): FetchResult {
        if (prime) primeNse()
        return request(url, validators, referer, bearer)
    }

    private suspend fun request(
        url: String,
        validators: CacheValidators?,
        referer: String?,
        bearer: String? = null,
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

            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled()) return
                    continuation.resume(
                        FetchResult.Failure(-1, e.message ?: e.javaClass.simpleName)
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(readResponse(response, validators))
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
            if (HttpCache.isTooLarge(body.contentLength().takeIf { it >= 0 })) {
                return FetchResult.Failure(response.code, "Body larger than the feed limit")
            }
            val bytes = try {
                body.bytes()
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

        /**
         * In-memory cookie jar.
         *
         * Deliberately not persisted: NSE's session cookies are short-lived, and a stale
         * one carried across a restart fails in the same silent way as having none. Each
         * process primes once and re-primes when a call is rejected.
         */
        private class SessionCookies : CookieJar {
            private val store = HashMap<String, List<Cookie>>()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (cookies.isNotEmpty()) store[url.host.substringAfter("www.")] = cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                store[url.host.substringAfter("www.")].orEmpty()
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
