package com.abhinavxt.newsforge.core.reader

import java.net.URLEncoder

/**
 * Turns a Google News article link into the publisher's own URL.
 *
 * Google News feeds no longer link to articles. Each item points at a news.google.com
 * page that finds the real address with script, so fetching it yields Google's page, not
 * the story. The page does carry what its script sends to work the address out, and the
 * same request made from here gets the same answer back.
 *
 * It is an undocumented endpoint, and it will change one day. Everything that depends on
 * its shape is in this file, and every step fails to null, so when Google changes it the
 * reader falls back to the browser rather than breaking.
 */
object GoogleNews {

    /** What the article page hands its own script. */
    data class Tokens(val id: String, val timestamp: String, val signature: String)

    const val DECODE_URL = "https://news.google.com/_/DotsSplashUi/data/batchexecute"
    const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded;charset=UTF-8"

    fun isArticleLink(url: String): Boolean {
        val host = HOST.find(url)?.groupValues?.get(1)?.lowercase() ?: return false
        return host == "news.google.com" && "/articles/" in url
    }

    fun tokensIn(html: String): Tokens? {
        val id = attribute(html, "data-n-a-id") ?: return null
        val timestamp = attribute(html, "data-n-a-ts")?.takeIf { ts -> ts.all { it.isDigit() } }
            ?: return null
        val signature = attribute(html, "data-n-a-sg") ?: return null
        return Tokens(id, timestamp, signature)
    }

    /** The form body for [DECODE_URL]. */
    fun requestBody(tokens: Tokens): String {
        val request = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\"," +
            "null,1,null,null,null,null,null,0,1],\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0]," +
            "\"${tokens.id}\",${tokens.timestamp},\"${tokens.signature}\"]"
        val envelope = "[[[\"Fbv4je\",${jsonString(request)},null,\"generic\"]]]"
        return "f.req=" + URLEncoder.encode(envelope, "UTF-8")
    }

    /** The publisher URL in the endpoint's reply, or null when the reply is not one. */
    fun urlIn(response: String): String? =
        RESOLVED.find(response)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun attribute(html: String, name: String): String? =
        Regex("$name=\"([^\"]+)\"").find(html)?.groupValues?.get(1)

    private fun jsonString(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val HOST = Regex("^https?://([^/:?#]+)", RegexOption.IGNORE_CASE)

    /** The reply nests JSON in a string, so the quotes around the URL arrive escaped. */
    private val RESOLVED = Regex("garturlres\\\\\",\\\\\"(https?://[^\"\\\\]+)")
}
