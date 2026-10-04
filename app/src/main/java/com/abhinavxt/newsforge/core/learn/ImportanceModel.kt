package com.abhinavxt.newsforge.core.learn

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** One thing that happened: a story's features, and whether it moved the stock. */
data class TrainingExample(
    val features: DoubleArray,
    /** 1.0 when the abnormal move cleared the bar, 0.0 when it did not. */
    val label: Double,
    /** Publication time, used only to advance the training watermark. */
    val publishedAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is TrainingExample && features.contentEquals(other.features) &&
            label == other.label && publishedAt == other.publishedAt)

    override fun hashCode(): Int =
        31 * (31 * features.contentHashCode() + label.hashCode()) + publishedAt.hashCode()
}

/**
 * A logistic regression, trained on the phone, from the phone's own observations.
 *
 * Linear and small on purpose. The supply of labels is bounded by how long price samples
 * are kept, so this will never see more than a few hundred examples at a time — a regime
 * where anything with capacity memorises and a linear model with thirty features does
 * not. It also stays inspectable: a weight vector whose entries are named is something
 * you can read and disagree with, which matters for a model that is about to decide what
 * a person sees first.
 *
 * Nothing is downloaded and no model file ships. An install that has never run starts at
 * zero weights, where [predict] returns 0.5 for everything and the model has no opinion —
 * which is the correct behaviour for something that has not seen any evidence yet.
 */
class ImportanceModel(
    val weights: DoubleArray = DoubleArray(ImportanceFeatures.SIZE),
    /** How many examples have been learned from, ever. Drives the cold-start guard. */
    val examplesSeen: Int = 0,
    /** Newest publication time already trained on; nothing at or before it trains again. */
    val watermark: Long = 0L,
) {

    init {
        require(weights.size == ImportanceFeatures.SIZE) {
            "expected ${ImportanceFeatures.SIZE} weights, got ${weights.size}"
        }
    }

    /** @return probability this story moves its stock beyond the market, in 0..1. */
    fun predict(features: DoubleArray): Double {
        var sum = 0.0
        for (i in weights.indices) sum += weights[i] * features[i]
        return 1.0 / (1.0 + exp(-sum))
    }

    /**
     * True once there is enough evidence to act on.
     *
     * Below this the model is mostly noise with a confident interface, and letting it
     * reorder a feed would mean the app's behaviour changing for reasons nobody could
     * explain on day three of using it.
     */
    val isTrained: Boolean get() = examplesSeen >= MIN_EXAMPLES

    /**
     * One pass of gradient descent over [examples], warm-started from the current weights.
     *
     * Batch rather than per-example: on a few hundred rows the cost is nothing, and a
     * batch step cannot be thrown around by whichever example happened to arrive last —
     * which on this data would be whichever stock happened to gap at the open.
     *
     * @param epochs passes over the batch. More than a handful on a set this small is
     *   fitting it rather than learning from it.
     */
    fun trained(
        examples: List<TrainingExample>,
        learningRate: Double = LEARNING_RATE,
        l2: Double = L2,
        epochs: Int = EPOCHS,
    ): ImportanceModel {
        if (examples.isEmpty()) return this

        val next = weights.copyOf()
        val gradient = DoubleArray(next.size)

        repeat(epochs) {
            java.util.Arrays.fill(gradient, 0.0)
            for (example in examples) {
                var sum = 0.0
                for (i in next.indices) sum += next[i] * example.features[i]
                val error = 1.0 / (1.0 + exp(-sum)) - example.label
                for (i in next.indices) gradient[i] += error * example.features[i]
            }
            val scale = learningRate / examples.size
            for (i in next.indices) {
                // The bias is left out of the penalty. Regularising it would pull the
                // model's baseline rate toward "half of everything moves", which is a
                // statement about the world rather than a guard against overfitting.
                val penalty = if (i == 0) 0.0 else l2 * next[i]
                next[i] -= scale * gradient[i] + learningRate * penalty
            }
        }

        return ImportanceModel(
            weights = next,
            examplesSeen = examplesSeen + examples.size,
            watermark = max(watermark, examples.maxOf { it.publishedAt }),
        )
    }

    /**
     * Moves the watermark forward without learning anything.
     *
     * For a sweep that examined stories and could label none of them. The watermark
     * records what has been dealt with, not what has been learned from, and a story that
     * could not be measured today will not become measurable tomorrow.
     */
    fun withWatermark(time: Long): ImportanceModel =
        if (time <= watermark) this else ImportanceModel(weights, examplesSeen, time)

    /**
     * The features pushing this prediction hardest, most influential first.
     *
     * Contribution rather than weight: a large weight on a feature that is zero for this
     * story explains nothing about this story. The bias is left out for the same reason —
     * it is identical for every story, so it cannot be why this one ranked where it did.
     */
    fun contributions(features: DoubleArray, top: Int = 3): List<Pair<String, Double>> =
        (1 until weights.size)
            .map { ImportanceFeatures.NAMES[it] to weights[it] * features[it] }
            .filter { abs(it.second) > 1e-6 }
            .sortedByDescending { abs(it.second) }
            .take(top)

    companion object {
        /**
         * Examples before the model is allowed an opinion.
         *
         * Two days of samples across a watchlist-sized set of names produces on the order
         * of a hundred measurable stories, so this is a few days of ordinary use rather
         * than a milestone nobody reaches.
         */
        const val MIN_EXAMPLES = 120

        const val LEARNING_RATE = 0.5
        const val L2 = 0.01
        const val EPOCHS = 8

        /**
         * Abnormal move, in percentage points, that counts as "this mattered".
         *
         * Above the noise floor [com.abhinavxt.newsforge.core.quote.PriceReaction] already
         * uses for a raw move, because an abnormal move has the market's own wobble
         * subtracted out of it and so needs less headroom to mean something.
         */
        const val MOVE_THRESHOLD_POINTS = 1.0

        /** @return 1.0 when the measured aftermath cleared [MOVE_THRESHOLD_POINTS]. */
        fun labelFor(abnormalPercent: Double): Double =
            if (abs(abnormalPercent) >= MOVE_THRESHOLD_POINTS) 1.0 else 0.0

        /**
         * Serialised as text, so the weight vector can live in preferences beside the
         * rest of the app's small state rather than earning a table and a migration.
         */
        fun encode(model: ImportanceModel): String = buildString {
            append(model.examplesSeen)
            append('|')
            append(model.watermark)
            append('|')
            append(model.weights.joinToString(","))
        }

        /**
         * @return the stored model, or a fresh one when the text is missing, corrupt, or
         *   the wrong length — which is what a build that changed the feature list leaves
         *   behind. Starting over costs a few days of learning; loading a vector whose
         *   entries no longer line up with the features would be silently wrong forever.
         */
        fun decode(text: String?): ImportanceModel {
            val parts = text?.split('|') ?: return ImportanceModel()
            if (parts.size != 3) return ImportanceModel()
            val seen = parts[0].toIntOrNull() ?: return ImportanceModel()
            val watermark = parts[1].toLongOrNull() ?: return ImportanceModel()
            val weights = parts[2].split(',').mapNotNull { it.toDoubleOrNull() }
            if (weights.size != ImportanceFeatures.SIZE) return ImportanceModel()
            if (weights.any { !it.isFinite() }) return ImportanceModel()
            return ImportanceModel(weights.toDoubleArray(), seen, watermark)
        }
    }
}
