package com.abhinavxt.newsforge.core.learn

import java.util.Locale

/**
 * Pulls the size of a thing out of the sentence that announces it.
 *
 * The existing categoriser knows that "Award of Order(s)" is an order win and has no way
 * to tell a twelve-crore order from a twelve-hundred-crore one. For a company of any
 * size those are different events, and the difference is written plainly in the headline
 * every time — it is simply never read.
 *
 * Normalised to crore throughout, because that is the unit Indian market copy actually
 * uses and converting everything to rupees would give a feature that spans ten orders of
 * magnitude for no benefit.
 */
object HeadlineNumbers {

    /** Multipliers onto crore for the magnitude words that show up in market copy. */
    private val UNITS: List<Pair<String, Double>> = listOf(
        // Longest first: "crore" must win before "cr" matches its prefix.
        "crore" to 1.0,
        "cr" to 1.0,
        "lakh crore" to 100_000.0,
        "lakh" to 0.01,
        "billion" to 100.0,
        "bn" to 100.0,
        "million" to 0.1,
        "mn" to 0.1,
        "trillion" to 100_000.0,
    ).sortedByDescending { it.first.length }

    private val AMOUNT = Regex(
        """(\d[\d,]*(?:\.\d+)?)\s*(lakh\s+crore|crore|cr|lakh|billion|bn|million|mn|trillion)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val PERCENT = Regex("""(\d[\d,]*(?:\.\d+)?)\s*(?:%|per\s?cent)""", RegexOption.IGNORE_CASE)

    /**
     * Largest money amount in the text, in crore.
     *
     * Largest rather than first: a headline often carries both the deal and a comparison
     * ("bags Rs 1,200 crore order, up from Rs 300 crore last year"), and the deal is the
     * bigger of the two far more often than not.
     *
     * @return null when the text names no amount, which is the common case.
     */
    fun croreIn(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        var largest: Double? = null
        for (match in AMOUNT.findAll(text)) {
            val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            val unit = match.groupValues[2].lowercase(Locale.US).replace(Regex("\\s+"), " ")
            val multiplier = UNITS.firstOrNull { it.first == unit }?.second ?: continue
            val crore = value * multiplier
            if (largest == null || crore > largest) largest = crore
        }
        return largest
    }

    /** Largest percentage in the text, for the same reason. */
    fun percentIn(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        var largest: Double? = null
        for (match in PERCENT.findAll(text)) {
            val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            if (largest == null || value > largest) largest = value
        }
        return largest
    }
}
