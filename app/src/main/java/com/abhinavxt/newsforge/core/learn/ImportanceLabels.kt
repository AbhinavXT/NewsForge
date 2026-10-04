package com.abhinavxt.newsforge.core.learn

import com.abhinavxt.newsforge.core.quote.EventHorizon
import com.abhinavxt.newsforge.core.quote.EventStudy
import com.abhinavxt.newsforge.core.quote.PricePoint

/** A story that may become a training example once its window has closed. */
data class LabelCandidate(
    /** The company the story is measured against — its leading tag. */
    val symbol: String,
    val publishedAt: Long,
    val features: FeatureInput,
)

/** What one labelling sweep produced. */
data class LabelBatch(
    val examples: List<TrainingExample>,
    /**
     * Newest publication time this sweep has finished with, trained on or not.
     *
     * Not the newest example. A story that could not be measured — no price near its
     * publication, too few peers — will never become measurable later, because samples
     * are only ever written at the moment they are observed. Leaving the watermark below
     * it would re-examine it on every sync for two days and learn nothing each time.
     */
    val watermark: Long,
)

/**
 * Decides which stories have taught us something, and what.
 *
 * The label is the event study's abnormal move over one hour. One hour because it is the
 * longest window that closes on the same trading day for almost every story, so the
 * model learns from today's evidence today; and abnormal because the raw move on a day
 * the market is up two per cent would teach it that every headline is important.
 */
object ImportanceLabels {

    val HORIZON: EventHorizon = EventHorizon.ONE_HOUR

    /**
     * @param candidates stories with a tagged company, any order.
     * @param series every sampled symbol's prices, which doubles as the benchmark.
     * @param watermark newest publication time already dealt with.
     */
    fun sweep(
        candidates: List<LabelCandidate>,
        series: Map<String, List<PricePoint>>,
        watermark: Long,
        nowMillis: Long,
    ): LabelBatch {
        // Only stories whose window has closed. Anything younger is left for a later
        // sweep, and the watermark stops short of it so that it is not skipped.
        val settledBefore = nowMillis - HORIZON.minutes * 60_000
        val due = candidates.filter { it.publishedAt in (watermark + 1)..settledBefore }
        if (due.isEmpty()) return LabelBatch(emptyList(), watermark)

        val examples = due.mapNotNull { candidate ->
            val own = series[candidate.symbol] ?: return@mapNotNull null
            val study = EventStudy.of(
                symbol = candidate.symbol,
                series = own,
                publishedAt = candidate.publishedAt,
                benchmark = series,
                nowMillis = nowMillis,
            )
            val outcome = study.outcomes.firstOrNull { it.horizon == HORIZON && it.complete }
                ?: return@mapNotNull null
            // No benchmark, no label. A raw move is not evidence of a reaction, and
            // training on one would be teaching the model the market's direction.
            val abnormal = outcome.abnormalPercent ?: return@mapNotNull null
            TrainingExample(
                features = ImportanceFeatures.of(candidate.features),
                label = ImportanceModel.labelFor(abnormal),
                publishedAt = candidate.publishedAt,
            )
        }
        return LabelBatch(examples, due.maxOf { it.publishedAt })
    }
}
