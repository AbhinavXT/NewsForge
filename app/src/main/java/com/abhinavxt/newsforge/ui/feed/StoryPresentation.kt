package com.abhinavxt.newsforge.ui.feed

import com.abhinavxt.newsforge.core.dedupe.Headline
import com.abhinavxt.newsforge.data.model.ArticleSummary

/**
 * Shapes a stored article for the detail sheet.
 *
 * Pure so the awkward parts — a summary that just repeats the headline, an outlet listed
 * twice under two spellings — are pinned by tests rather than discovered on screen.
 */
object StoryPresentation {

    /** Longer than this and the sheet stops being glanceable. */
    private const val SUMMARY_LIMIT = 320

    /**
     * Every outlet carrying the story, the one that broke it first.
     *
     * Deduplicated case-insensitively because the same publisher arrives under different
     * spellings depending on which feed found it — "Mint" from its own RSS, "Livemint"
     * from a Google News entry.
     */
    fun outlets(article: ArticleSummary): List<String> {
        val seen = LinkedHashMap<String, String>()
        for (name in listOf(article.sourceName) + article.otherSources) {
            val key = name.trim().lowercase()
            if (key.isEmpty()) continue
            seen.putIfAbsent(key, name.trim())
        }
        return seen.values.toList()
    }

    /**
     * The summary, or null when it adds nothing.
     *
     * Feeds very often set `description` to the headline verbatim, or to the headline
     * plus a trailing outlet name. Rendering that under the title gives the reader the
     * same sentence twice and makes the sheet look broken, so a summary that is
     * substantially the title is dropped rather than shown.
     */
    fun summaryOrNull(article: ArticleSummary): String? {
        val summary = article.summary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isEssentiallyTheTitle(summary, article.title)) return null
        return if (summary.length > SUMMARY_LIMIT) {
            summary.take(SUMMARY_LIMIT).substringBeforeLast(' ').trimEnd(',', ';', '-') + "…"
        } else {
            summary
        }
    }

    /**
     * Compared on content tokens rather than characters, so trailing punctuation, an
     * appended outlet name or a smart apostrophe do not defeat the check.
     */
    internal fun isEssentiallyTheTitle(summary: String, title: String): Boolean {
        val titleTokens = Headline.tokenSet(title)
        if (titleTokens.isEmpty()) return false
        val summaryTokens = Headline.tokenSet(summary)
        if (summaryTokens.isEmpty()) return true
        // The summary says nothing new if the title accounts for nearly all of it.
        val extra = summaryTokens - titleTokens
        return extra.size <= 2 && titleTokens.count { it in summaryTokens } >= titleTokens.size - 1
    }

    /** Plain text for a share intent: headline, outlet, link. */
    fun shareText(article: ArticleSummary): String =
        "${article.title}\n${article.sourceName}\n${article.link}"
}
