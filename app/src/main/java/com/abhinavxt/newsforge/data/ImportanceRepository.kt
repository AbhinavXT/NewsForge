package com.abhinavxt.newsforge.data

import android.util.Log
import com.abhinavxt.newsforge.core.learn.ImportanceLabels
import com.abhinavxt.newsforge.core.learn.ImportanceModel
import com.abhinavxt.newsforge.core.learn.LabelCandidate
import com.abhinavxt.newsforge.core.quote.EventStudy
import com.abhinavxt.newsforge.data.ingest.featuresOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Keeps the importance model, and teaches it from what the market did.
 *
 * Nothing new is stored to make this work. The stories are already in the article table
 * and the prices are already in the sample store, so the training set is re-derived on
 * every sweep and only the learned weights are kept — thirty-odd numbers in preferences
 * rather than a table, a migration and a second retention policy to get wrong.
 *
 * The cost of that choice is that a story can only teach the model while its prices are
 * still in the sample store. The watermark makes that a non-issue in practice: a story is
 * labelled on the first sweep after its hour closes, which is minutes later, not days.
 */
class ImportanceRepository(
    private val store: ImportanceStore,
    private val news: NewsRepository,
    private val prices: PriceHistoryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /** The current model. Opinionless until [ImportanceModel.isTrained]. */
    fun model(): StateFlow<ImportanceModel> = store.model()

    /**
     * Labels every story whose hour has closed since the last sweep, and learns from them.
     *
     * @return how many examples were learned from, for the sync log.
     */
    suspend fun train(): Int = withContext(dispatcher) {
        val stories = news.stories().first()
        val samples = prices.recent().first()
        if (samples.isEmpty()) return@withContext 0

        val series = samples.mapValues { (_, points) -> EventStudy.fromSamples(points) }
        val candidates = stories.mapNotNull { scored ->
            // The leading tag, and only that one. A story naming three companies is one
            // piece of evidence, and counting it three times would let round-ups
            // outvote the filings they summarise.
            val symbol = scored.article.symbols.firstOrNull() ?: return@mapNotNull null
            LabelCandidate(symbol, scored.article.publishedAt, featuresOf(scored.article))
        }

        val current = store.current
        val batch = ImportanceLabels.sweep(candidates, series, current.watermark, clock())
        val next = current.trained(batch.examples).withWatermark(batch.watermark)

        if (next !== current) store.save(next)
        if (batch.examples.isNotEmpty()) {
            Log.i(
                TAG,
                "learned from ${batch.examples.size} stories " +
                    "(${batch.examples.count { it.label > 0.5 }} moved), " +
                    "${next.examplesSeen} total",
            )
        }
        batch.examples.size
    }

    /**
     * Forgets everything learned.
     *
     * For when the model has learned something you disagree with. It retrains from the
     * next sweep, and the feed falls back to the hand-tuned ranking until it has seen
     * enough to be trusted again.
     */
    fun reset() = store.reset()

    private companion object {
        const val TAG = "Importance"
    }
}
