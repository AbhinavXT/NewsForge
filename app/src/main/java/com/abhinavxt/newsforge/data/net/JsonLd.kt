package com.abhinavxt.newsforge.data.net

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Finds an article's `articleBody` in a page's JSON-LD.
 *
 * Kept to the walk, for the same reason as [NseJson]: deciding whether to use the body
 * is `ArticleExtractor`'s job and is tested there, while this half needs Android's
 * `org.json` and cannot be.
 */
object JsonLd {

    /** The longest articleBody across [scripts], or null when none has one. */
    fun articleBody(scripts: List<String>): String? =
        scripts.mapNotNull { script ->
            try {
                bodyIn(JSONTokener(script.trim()).nextValue())
            } catch (e: JSONException) {
                // One malformed block — they are hand-assembled on more sites than not —
                // should not hide a good one elsewhere on the page.
                null
            }
        }.maxByOrNull { it.length }

    /** Depth-first, so a body nested under `@graph` or `mainEntity` is still found. */
    private fun bodyIn(node: Any?): String? = when (node) {
        is JSONObject -> node.optString("articleBody").takeIf { it.isNotBlank() }
            ?: node.keys().asSequence().firstNotNullOfOrNull { bodyIn(node.opt(it)) }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { bodyIn(node.opt(it)) }
        else -> null
    }
}
