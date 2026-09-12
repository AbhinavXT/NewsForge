package com.abhinavxt.newsforge.core.dedupe

import java.net.URI
import java.util.Locale

/**
 * Reduces a link to a stable identity.
 *
 * The same article arrives from several feeds with different tracking parameters, AMP
 * variants and `www.` prefixes. Without this, the primary key changes per feed and the
 * article is stored — and shown — several times over.
 */
object UrlCanonicalizer {

    /**
     * Query parameters that never change which article you land on.
     *
     * Note `utm_*`, `at_*` and `mc_*` are matched by prefix below, not listed here.
     */
    private val JUNK_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "msclkid", "igshid", "twclid", "yclid",
        "ref", "ref_src", "referrer", "source", "src", "cmpid", "campaign_id",
        "ncid", "spm", "from", "sh_kit", "smid", "partner", "guccounter",
        "feature", "app", "platform", "amp", "outputType", "output",
        "oc", "hl", "gl", "ceid", "usqp",
    )

    private val JUNK_PREFIXES = listOf("utm_", "at_", "mc_", "wt_", "pk_", "piwik_", "_hs")

    private val JUNK_LOWER = JUNK_PARAMS.map { it.lowercase(Locale.US) }.toSet()

    /**
     * @return a canonical absolute URL, or the trimmed input when it cannot be parsed.
     *   Never returns null: an unparseable link is still a usable identity, just a worse
     *   one, and dropping the article would be a bigger loss.
     */
    fun canonicalize(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return input

        val uri = runCatching { URI(input) }.getOrNull() ?: return input
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return input
        if (scheme != "http" && scheme != "https") return input
        val rawHost = uri.host ?: return input

        val host = rawHost.lowercase(Locale.US)
            .removePrefix("www.")
            .removePrefix("m.")
            .removePrefix("amp.")

        val path = normalisePath(uri.path.orEmpty())
        val query = cleanQuery(uri.rawQuery)

        // https is the canonical scheme even when the feed served an http link; the two
        // are the same document everywhere that matters, and mixing them would double up.
        val builder = StringBuilder("https://").append(host).append(path)
        if (query.isNotEmpty()) builder.append('?').append(query)
        return builder.toString()
    }

    private fun normalisePath(path: String): String {
        var result = path
        // AMP variants: /article/amp, /article.amp, /amp/article
        result = result.removeSuffix("/")
        if (result.endsWith("/amp", ignoreCase = true)) result = result.dropLast(4)
        if (result.endsWith(".amp", ignoreCase = true)) result = result.dropLast(4)
        if (result.startsWith("/amp/", ignoreCase = true)) result = result.substring(4)
        result = result.removeSuffix("/")
        if (result.isEmpty()) return "/"
        return result
    }

    /**
     * Drops tracking parameters and sorts what remains, so that two orderings of the same
     * meaningful query produce one key.
     */
    private fun cleanQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrEmpty()) return ""
        val kept = rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .filterNot { pair ->
                val key = pair.substringBefore('=').lowercase(Locale.US)
                key.isEmpty() ||
                    key in JUNK_LOWER ||
                    JUNK_PREFIXES.any { key.startsWith(it) }
            }
            .sorted()
        return kept.joinToString("&")
    }
}
