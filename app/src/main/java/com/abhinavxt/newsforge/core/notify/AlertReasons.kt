package com.abhinavxt.newsforge.core.notify

import com.abhinavxt.newsforge.core.model.Category
import java.util.Locale

/**
 * Why an alert rang, in words.
 *
 * Every alert is the end of a chain of thresholds — tier bars, sensitivity, per-company
 * levels — and none of it is visible from the shade. Without a reason, an alert that
 * should not have come is indistinguishable from a bug, and there is no telling which
 * setting to reach for. With one, the fix is usually in the sentence.
 */
object AlertReasons {

    /** A few words, for the notification header. */
    fun short(alert: Alert): String {
        val subject = alert.subject
        return when {
            alert.everyStory && subject != null -> "Every story on $subject"
            alert.tier != null && subject != null -> "${alert.tier.label}: $subject"
            alert.candidate.category == Category.REGULATORY -> "Regulatory action"
            else -> "Market-wide ${alert.candidate.category.label.lowercase(Locale.US)}"
        }
    }

    /** A sentence, for the expanded notification. */
    fun long(alert: Alert): String {
        val subject = alert.subject
        val category = alert.candidate.category.label.lowercase(Locale.US)
        return when {
            alert.everyStory && subject != null ->
                "You set $subject to alert on every story."
            alert.tier != null && subject != null -> when (alert.tier) {
                WatchTier.LEVERAGED ->
                    "You hold $subject on margin, so any material $category story rings."
                WatchTier.HOLDING ->
                    "You hold $subject, and this $category story cleared the holding bar."
                WatchTier.WATCHING ->
                    "You're watching $subject, and this $category story cleared the watching bar."
            }
            alert.candidate.category == Category.REGULATORY ->
                "Regulator or exchange action is always sent, followed or not."
            else ->
                "A high-impact $category story from " +
                    "${article(alert.candidate.tier.label)} " +
                    "${alert.candidate.tier.label.lowercase(Locale.US)} source, above your " +
                    "market-wide alert level."
        }
    }

    private fun article(word: String): String =
        if (word.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
}
