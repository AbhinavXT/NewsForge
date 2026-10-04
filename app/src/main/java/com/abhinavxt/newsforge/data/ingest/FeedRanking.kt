package com.abhinavxt.newsforge.data.ingest

import com.abhinavxt.newsforge.core.learn.ImportanceFeatures
import com.abhinavxt.newsforge.core.learn.ImportanceModel
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.core.rank.RankInput
import com.abhinavxt.newsforge.core.rank.Ranker
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.data.model.ScoredArticle

/**
 * Orders the feed.
 *
 * Scores are computed here on the way to the UI and never stored. A score depends on
 * `now` and decays continuously, so a persisted `score` column would be stale the instant
 * it was written and would have to be rewritten across the whole table on every refresh.
 * The row stores the inputs instead; scoring a few hundred rows is a multiply each.
 */
object FeedRanking {

    /**
     * @param model when trained, decides the order outright; otherwise ignored, and the
     *   hand-tuned ranker decides as it always has.
     */
    fun rank(
        items: List<ArticleSummary>,
        nowMillis: Long,
        model: ImportanceModel? = null,
    ): List<ScoredArticle> {
        val phase = MarketClock.phase(nowMillis)
        if (model != null && model.isTrained) return learned(items, nowMillis, phase, model)
        return items
            .map { article ->
                ScoredArticle(
                    article = article,
                    score = Ranker.score(
                        RankInput(
                            category = article.category,
                            tier = article.tier,
                            publishedAtMillis = article.publishedAt,
                            symbolCount = article.symbols.size,
                            outletCount = article.outletCount,
                            spanMinutes =
                                (article.lastPublishedAt - article.publishedAt) / 60_000L,
                        ),
                        nowMillis = nowMillis,
                        phase = phase,
                    ),
                )
            }
            .sortedWith(ORDER)
    }

    /**
     * The feed ordered by the model: its probability that the story moves the stock,
     * aged by the same half-life the hand-tuned ranker uses.
     *
     * The model replaces every importance term — category, source, coverage, pickup,
     * companies — and keeps only age, because age is not importance and the model was
     * never asked about it. It was trained on whether a story moved a price, which is a
     * fact about the story that does not decay; whether you want to read it at 16:00 is
     * a different question, and without the decay a three-day-old results filing would
     * outrank this morning's news indefinitely.
     *
     * Stories that name no company are the model's weak spot, and it is worth being plain
     * about. Their aftermath cannot be measured — there is no stock — so the model has
     * never been trained on one and scores them from the features they share with the
     * stories it has seen. That is a reasonable extrapolation for category, source and
     * coverage, and it is still an extrapolation.
     */
    private fun learned(
        items: List<ArticleSummary>,
        nowMillis: Long,
        phase: com.abhinavxt.newsforge.core.rank.MarketPhase,
        model: ImportanceModel,
    ): List<ScoredArticle> =
        items
            .map { article ->
                val features = ImportanceFeatures.of(featuresOf(article))
                val probability = model.predict(features)
                ScoredArticle(
                    article = article,
                    score = probability * Ranker.recency(article.publishedAt, nowMillis, phase),
                    moveProbability = probability,
                    drivers = model.contributions(features)
                        .filter { it.second > 0.0 }
                        .map { it.first },
                )
            }
            .sortedWith(ORDER)

    /**
     * Scores [items] exactly as [rank] would, without reordering them.
     *
     * For search, where the query has already put the rows in the order that answers the
     * question. Scoring still matters there — it is what the story sheet explains — but
     * sorting by it would undo the one ordering a history search is for.
     */
    fun scoreInOrder(
        items: List<ArticleSummary>,
        nowMillis: Long,
        model: ImportanceModel? = null,
    ): List<ScoredArticle> {
        val byId = rank(items, nowMillis, model).associateBy { it.article.id }
        return items.mapNotNull { byId[it.id] }
    }

    /** Ties broken by recency so the order is stable and never arbitrary. */
    private val ORDER = compareByDescending<ScoredArticle> { it.score }
        .thenByDescending { it.article.publishedAt }
        .thenBy { it.article.id }

    /**
     * The overnight brief: everything published since the last session close, ranked.
     *
     * This is the "what do I buy for the day ahead" view, so it is bounded by the market
     * clock rather than by a rolling number of hours — on a Monday morning that means
     * Friday's close, not 24 hours.
     */
    fun overnight(
        items: List<ArticleSummary>,
        nowMillis: Long,
        model: ImportanceModel? = null,
    ): List<ScoredArticle> {
        val since = MarketClock.lastCloseMillis(nowMillis)
        return rank(items.filter { it.publishedAt >= since }, nowMillis, model)
    }

    /**
     * The same brief, taken from a list that has already been ranked.
     *
     * Equivalent to [overnight]: a score depends only on the article, never on the set it
     * sits in, so filtering after ranking yields the same rows in the same order. This
     * form exists so the feed can hold one database subscription instead of two — the
     * brief is a view of the live list, not a second query for the same rows.
     */
    fun overnightOf(ranked: List<ScoredArticle>, nowMillis: Long): List<ScoredArticle> {
        val since = MarketClock.lastCloseMillis(nowMillis)
        return ranked.filter { it.article.publishedAt >= since }
    }
}

/** How long articles live locally. */
object Retention {

    /**
     * The feed's window, and the age at which an unsaved story nobody follows is
     * compacted to its headline.
     *
     * This used to be when such stories were deleted, which made search a search of the
     * last week and nothing else: "everything on this company since June" worked only
     * if the company had been on the watchlist since June.
     */
    const val KEEP_DAYS: Int = 7

    /**
     * How long any story is kept at all, saved ones aside.
     *
     * Everything reaches this age now, not only followed companies: past [KEEP_DAYS] an
     * unfollowed story is compacted to its headline rather than deleted, so it stays
     * searchable and its company's timeline has depth even for a name nobody follows yet
     * — which is exactly when someone first goes looking.
     *
     * The cost is the one knob here. A compacted row is a headline, a link, an outlet and
     * a few tags, well under a kilobyte with its share of the indexes, so a busy feed list
     * producing six hundred new stories a day holds around fifty megabytes at this
     * setting. Halve it to halve that.
     */
    const val HISTORY_KEEP_DAYS: Int = 90

    /**
     * Alert bookkeeping outlives the articles it refers to.
     *
     * These rows are an id and a timestamp, so a month of them costs nothing, and the
     * event reminders dedupe over thirty days. Pruning them on [KEEP_DAYS] with the
     * articles quietly cut that window to seven — long enough that the three-day lead
     * still worked, short enough that the intent behind it did not.
     */
    const val NOTIFIED_KEEP_DAYS: Int = 31

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Articles published before this are prunable. Saved and followed ones are exempt. */
    fun cutoffMillis(nowMillis: Long): Long = nowMillis - KEEP_DAYS * DAY_MS

    /** The hard floor: past this, every unsaved story goes, followed or not. */
    fun historyCutoffMillis(nowMillis: Long): Long = nowMillis - HISTORY_KEEP_DAYS * DAY_MS

    /** Alerts marked as sent before this are prunable. */
    fun notifiedCutoffMillis(nowMillis: Long): Long = nowMillis - NOTIFIED_KEEP_DAYS * DAY_MS

    /**
     * Window the clusterer compares against on insert.
     *
     * Wider than [com.abhinavxt.newsforge.core.dedupe.Clusterer.DEFAULT_WINDOW_MS] on
     * purpose: the query has to return every row that *could* be within six hours of any
     * item in the incoming batch, and a batch may contain items older than now.
     */
    fun clusterWindowStart(nowMillis: Long): Long = nowMillis - 2 * DAY_MS
}
