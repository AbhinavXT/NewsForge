package com.abhinavxt.newsforge.data.ingest

import com.abhinavxt.newsforge.core.learn.FeatureInput
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.data.model.ArticleSummary

/**
 * The model's view of a story.
 *
 * One function, used both to train and to score, so the two can never drift apart. A
 * feature computed one way at training time and another at scoring time is the classic
 * silent failure of a deployed model, and the only reliable defence is there being a
 * single place it is computed.
 *
 * Phase is taken at *publication*, not now: the model is learning what a results filing
 * at 10:40 does, and scoring that same filing at 21:00 as though it had been published
 * after the close would be answering a different question.
 */
internal fun featuresOf(article: ArticleSummary): FeatureInput = FeatureInput(
    category = article.category,
    tier = article.tier,
    isFiling = article.feedKind.isNse,
    outletCount = article.outletCount,
    spanMinutes = (article.lastPublishedAt - article.publishedAt) / 60_000L,
    symbolCount = article.symbols.size,
    title = article.title,
    summary = article.summary,
    phase = MarketClock.phase(article.publishedAt),
)
