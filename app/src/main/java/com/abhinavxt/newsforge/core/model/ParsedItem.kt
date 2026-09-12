package com.abhinavxt.newsforge.core.model

/**
 * One entry as it came off the wire, before canonicalisation, tagging or ranking.
 *
 * [publishedAtMillis] is null when the feed omits a date or emits one we cannot parse.
 * Callers substitute fetch time — but the null is preserved here so the repository can
 * tell "publisher said 09:14" from "we first saw it at 09:14", which matters when
 * ordering a pre-market brief.
 *
 * [sourceName] is the publisher named *inside* the entry (Google News puts the real
 * outlet in a `<source>` element), not the feed we polled.
 */
data class ParsedItem(
    val title: String,
    val link: String,
    val summary: String? = null,
    val publishedAtMillis: Long? = null,
    val guid: String? = null,
    val sourceName: String? = null,
)
