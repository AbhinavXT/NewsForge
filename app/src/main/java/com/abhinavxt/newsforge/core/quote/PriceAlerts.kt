package com.abhinavxt.newsforge.core.quote

import com.abhinavxt.newsforge.core.desk.DeskPayload
import java.util.Locale
import kotlin.math.abs

/** What a price alert watches for. */
enum class PriceAlertKind(val label: String) {
    ABOVE("Rises above"),
    BELOW("Falls below"),

    /** The day's move, either way, in per cent. */
    MOVE("Moves by"),
    ;

    companion object {
        fun parse(name: String): PriceAlertKind? = entries.firstOrNull { it.name == name }
    }
}

data class PriceAlertRule(
    val id: Long,
    val symbol: String,
    val kind: PriceAlertKind,
    val threshold: Double,
)

/** A rule that has fired, with the quote that fired it. */
data class PriceAlertHit(
    val rule: PriceAlertRule,
    val price: Double,
    val changePercent: Double?,
)

/**
 * Decides which price alerts a set of quotes sets off.
 *
 * Levels rather than crossings: an alert fires when the price is past its level, not only
 * on the poll where it went past. Polls are minutes apart and the app may have been
 * asleep for the crossing itself, and an alert that missed the move because nobody
 * watched the exact tick would be worse than useless — it would be trusted. Each rule
 * fires once and is then disarmed by the caller, which is what stops this repeating.
 */
object PriceAlerts {

    /**
     * Older than this and a quote describes a different moment. The exchange's figures
     * are delayed already; alerting off a lunchtime price at three would be misleading.
     */
    const val MAX_QUOTE_AGE_MS: Long = 30L * 60 * 1000

    fun check(
        rules: List<PriceAlertRule>,
        quotes: Map<String, DeskPayload>,
        nowMillis: Long,
    ): List<PriceAlertHit> = rules.mapNotNull { rule ->
        val quote = quotes[rule.symbol] ?: return@mapNotNull null
        val stamp = quote.timestampMillis
        if (stamp != null && nowMillis - stamp > MAX_QUOTE_AGE_MS) return@mapNotNull null
        val price = quote.ltp?.takeIf { it > 0.0 } ?: return@mapNotNull null
        val change = quote.changePercent
        val fired = when (rule.kind) {
            PriceAlertKind.ABOVE -> price >= rule.threshold
            PriceAlertKind.BELOW -> price <= rule.threshold
            PriceAlertKind.MOVE -> change != null && abs(change) >= rule.threshold
        }
        if (fired) PriceAlertHit(rule, price, change) else null
    }

    /** "Rises above ₹4,500" — what the rule is waiting for. */
    fun describe(kind: PriceAlertKind, threshold: Double): String = when (kind) {
        PriceAlertKind.ABOVE, PriceAlertKind.BELOW -> "${kind.label} ${rupees(threshold)}"
        PriceAlertKind.MOVE -> "${kind.label} ${percent(threshold)} in a day"
    }

    /** The notification's title: what happened, symbol first. */
    fun headline(hit: PriceAlertHit): String = when (hit.rule.kind) {
        PriceAlertKind.ABOVE -> "${hit.rule.symbol} is above ${rupees(hit.rule.threshold)}"
        PriceAlertKind.BELOW -> "${hit.rule.symbol} is below ${rupees(hit.rule.threshold)}"
        PriceAlertKind.MOVE -> {
            val direction = if ((hit.changePercent ?: 0.0) >= 0) "up" else "down"
            "${hit.rule.symbol} is $direction ${percent(abs(hit.changePercent ?: 0.0))} today"
        }
    }

    /** The line under it: the price now, and the day's move when known. */
    fun detail(hit: PriceAlertHit): String = buildString {
        append("Now ").append(rupees(hit.price))
        hit.changePercent?.let { change ->
            append(" · ")
            append(if (change >= 0) "+" else "−")
            append(percent(abs(change)))
            append(" today")
        }
        append(" · you asked for: ")
        append(describe(hit.rule.kind, hit.rule.threshold).lowercase(Locale.US))
    }

    fun rupees(value: Double): String =
        "₹" + if (value >= 1000) {
            String.format(Locale.US, "%,.0f", value)
        } else {
            String.format(Locale.US, "%,.2f", value)
        }

    private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value)
}
