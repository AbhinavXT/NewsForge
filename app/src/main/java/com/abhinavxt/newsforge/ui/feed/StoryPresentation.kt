package com.abhinavxt.newsforge.ui.feed

import com.abhinavxt.newsforge.core.dedupe.Headline
import com.abhinavxt.newsforge.data.model.ArticleSummary
import java.util.Locale
import kotlin.math.abs

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

    /**
     * A price move, beside a ticker.
     *
     * Always signed and always to one decimal, so a column of them lines up under the
     * monospace face the tickers use — "+1.4%" and "-12.0%" occupy predictable widths and
     * the eye can run down them. Dropping the decimal above ten would save a character
     * and cost that.
     *
     * The sign is printed rather than left to colour. Green against red is what every
     * broker app in the country uses and anything else here would be perverse, but it is
     * also the one pair the rest of this palette deliberately avoids relying on, so the
     * direction has to survive being read by someone who cannot separate them.
     */
    fun changeLabel(changePercent: Double): String {
        // The magnitude is formatted first and the sign decided from the result, so a
        // move of -0.04 prints as "0.0%" rather than "-0.0%" — which reads as a fall that
        // did not happen. Rounding after choosing the sign would also make the two
        // decisions disagree at exactly the values where it matters.
        val magnitude = String.format(Locale.US, "%.1f", abs(changePercent))
        val sign = when {
            magnitude == "0.0" -> ""
            changePercent < 0 -> "-"
            else -> "+"
        }
        return "$sign$magnitude%"
    }
}
