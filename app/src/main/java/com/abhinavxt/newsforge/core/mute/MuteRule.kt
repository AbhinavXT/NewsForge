package com.abhinavxt.newsforge.core.mute

import java.util.Locale

/** What a rule matches on. */
enum class MuteKind(val label: String) {
    /** An outlet, matched on the name shown in the byline. */
    SOURCE("Source"),

    /** A company. Mutes the ticker everywhere, including alerts. */
    SYMBOL("Ticker"),

    /** A phrase in the headline or summary. */
    KEYWORD("Keyword"),
}

data class MuteRule(
    val kind: MuteKind,
    val value: String,
) {
    /** Stable identity, so the same rule cannot be added twice under different casing. */
    val key: String get() = "${kind.name}:${value.trim().lowercase(Locale.US)}"
}

/**
 * Suppresses things you have said you do not want to see.
 *
 * Applied on read rather than at ingest, deliberately. Muting is a preference and
 * preferences change: dropping the articles at fetch time would make un-muting silently
 * useless, because the stories it should bring back would never have been stored.
 *
 * The same rules gate notifications, which is where most of their value is — a source you
 * have muted in the feed should not be allowed to wake you at 09:20.
 */
object MuteRules {

    /**
     * @param sourceName the outlet as shown in the byline.
     * @param text headline plus summary; keyword rules read both, because the word you
     *   want gone is as often in the first line of the body as in the title.
     */
    fun isMuted(
        sourceName: String,
        text: String,
        symbols: List<String>,
        rules: Collection<MuteRule>,
    ): Boolean = rules.any { matches(it, sourceName, text, symbols) }

    fun matches(
        rule: MuteRule,
        sourceName: String,
        text: String,
        symbols: List<String>,
    ): Boolean {
        val value = rule.value.trim()
        if (value.isEmpty()) return false
        return when (rule.kind) {
            MuteKind.SOURCE -> sourceName.trim().equals(value, ignoreCase = true)
            // Exact, not substring: muting "BEL" must not silence "BELRISE".
            MuteKind.SYMBOL -> symbols.any { it.equals(value, ignoreCase = true) }
            MuteKind.KEYWORD -> text.contains(value, ignoreCase = true)
        }
    }

    /** Deduplicates by [MuteRule.key], keeping the first spelling entered. */
    fun normalise(rules: Collection<MuteRule>): List<MuteRule> {
        val seen = LinkedHashMap<String, MuteRule>()
        for (rule in rules) {
            if (rule.value.isBlank()) continue
            seen.putIfAbsent(rule.key, rule.copy(value = rule.value.trim()))
        }
        return seen.values.toList()
    }
}

/**
 * How much the alerter is allowed to say.
 *
 * Named levels rather than a number, because the underlying score is meaningless to
 * anyone who has not read the ranker. "Only the big ones" is a decision someone can
 * actually make; "2.9" is not.
 *
 * The thresholds map onto real category boundaries — at full recency a wire story about a
 * tagged company scores category weight × 1.2 × 1.5 — so each level corresponds to a
 * describable set of stories rather than a point on a slider.
 */
enum class AlertSensitivity(
    val label: String,
    val description: String,
    val minScore: Double,
    val maxPerSync: Int,
) {
    QUIET(
        "Only the big ones",
        "Regulatory action and large deals",
        minScore = 3.6,
        maxPerSync = 2,
    ),
    BALANCED(
        "Balanced",
        "Deals, orders and regulatory action",
        minScore = 2.9,
        maxPerSync = 4,
    ),
    LOUD(
        "Everything material",
        "Adds results and rating changes",
        minScore = 2.2,
        maxPerSync = 6,
    ),
    ;

    companion object {
        val DEFAULT: AlertSensitivity = BALANCED

        fun parse(name: String?): AlertSensitivity =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
