package com.abhinavxt.newsforge.core.feed

import java.net.URI
import java.util.Locale

/** Why a feed the user typed cannot be saved. */
enum class FeedProblem(val message: String) {
    NAME_EMPTY("Give the feed a name"),
    URL_EMPTY("Paste the feed URL"),
    URL_MALFORMED("That does not look like a URL"),
    URL_NOT_HTTP("Feed URLs must start with http:// or https://"),
    URL_NO_HOST("That URL has no site in it"),
    DUPLICATE_URL("You already have a feed with that URL"),
}

/**
 * Validates and normalises feeds before they reach the database.
 *
 * Separate from the UI because the same rules apply to a seeded feed and a pasted one,
 * and because getting them wrong is silent: a feed saved with a bad URL just never returns
 * anything, which looks identical to a publisher going quiet.
 */
object FeedValidation {

    fun validate(name: String, url: String, existingUrls: Set<String> = emptySet()): FeedProblem? {
        if (name.isBlank()) return FeedProblem.NAME_EMPTY
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return FeedProblem.URL_EMPTY

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return FeedProblem.URL_MALFORMED
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return FeedProblem.URL_NOT_HTTP
        if (scheme != "http" && scheme != "https") return FeedProblem.URL_NOT_HTTP
        if (uri.host.isNullOrBlank()) return FeedProblem.URL_NO_HOST

        if (normalizeUrl(trimmed) in existingUrls.map { normalizeUrl(it) }) {
            return FeedProblem.DUPLICATE_URL
        }
        return null
    }

    /**
     * Light normalisation only: scheme and host lowercased, trailing slash dropped.
     *
     * Deliberately *not* the article canonicaliser. That one strips query parameters,
     * which for a Google News search feed would discard the feed itself, and it strips
     * `www.`, which is safe for a link we only compare but not for a URL we have to
     * fetch — some hosts answer on one and 404 on the other. Two spellings of the same
     * feed are therefore allowed through as separate entries; a duplicate costs one
     * wasted poll, a rewritten URL that no longer resolves costs the feed.
     */
    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return trimmed
        val host = uri.host?.lowercase(Locale.US) ?: return trimmed
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port$path$query"
    }

    /**
     * Stable id for a user-added feed.
     *
     * Host-derived so it is legible in logs and the health screen, with a hash suffix so
     * two feeds from the same site do not collide.
     */
    fun idFor(url: String): String {
        val normalized = normalizeUrl(url)
        val host = runCatching { URI(normalized).host }.getOrNull()
            ?.lowercase(Locale.US)
            ?.removePrefix("www.")
            ?.replace(Regex("[^a-z0-9]+"), "-")
            ?.trim('-')
            ?.take(24)
            ?.ifEmpty { null }
            ?: "feed"
        val suffix = Integer.toHexString(normalized.hashCode()).takeLast(6)
        return "$host-$suffix"
    }
}
