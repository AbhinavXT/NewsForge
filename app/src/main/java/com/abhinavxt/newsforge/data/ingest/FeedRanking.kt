package com.abhinavxt.newsforge.data.ingest

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

    fun rank(items: List<ArticleSummary>, nowMillis: Long): List<ScoredArticle> {
        val phase = MarketClock.phase(nowMillis)
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
                            clusterSize = article.clusterSize,
                        ),
                        nowMillis = nowMillis,
                        phase = phase,
                    ),
                )
            }
            // Ties broken by recency so the order is stable and never arbitrary.
            .sortedWith(
                compareByDescending<ScoredArticle> { it.score }
                    .thenByDescending { it.article.publishedAt }
                    .thenBy { it.article.id }
            )
    }

    /**
     * The overnight brief: everything published since the last session close, ranked.
     *
     * This is the "what do I buy for the day ahead" view, so it is bounded by the market
     * clock rather than by a rolling number of hours — on a Monday morning that means
     * Friday's close, not 24 hours.
     */
    fun overnight(items: List<ArticleSummary>, nowMillis: Long): List<ScoredArticle> {
        val since = MarketClock.lastCloseMillis(nowMillis)
        return rank(items.filter { it.publishedAt >= since }, nowMillis)
    }
}

/** How long articles live locally. */
object Retention {

    const val KEEP_DAYS: Int = 7

    /**
     * Coverage of a followed company is kept far longer than general news.
     *
     * The company timeline is only as useful as its depth, and at seven days it would show
     * a week of headlines you have already read. Ninety days of stories for the handful of
     * names on a watchlist is a few hundred rows — nothing, against a screen that can show
     * "four regulatory items this quarter".
     */
    const val FOLLOWED_KEEP_DAYS: Int = 90

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Articles published before this are prunable. Saved and followed ones are exempt. */
    fun cutoffMillis(nowMillis: Long): Long = nowMillis - KEEP_DAYS * DAY_MS

    /** The hard floor: past this, even a followed company's coverage goes. */
    fun followedCutoffMillis(nowMillis: Long): Long = nowMillis - FOLLOWED_KEEP_DAYS * DAY_MS

    /**
     * Window the clusterer compares against on insert.
     *
     * Wider than [com.abhinavxt.newsforge.core.dedupe.Clusterer.DEFAULT_WINDOW_MS] on
     * purpose: the query has to return every row that *could* be within six hours of any
     * item in the incoming batch, and a batch may contain items older than now.
     */
    fun clusterWindowStart(nowMillis: Long): Long = nowMillis - 2 * DAY_MS
}
