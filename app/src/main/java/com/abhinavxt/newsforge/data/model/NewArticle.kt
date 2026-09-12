package com.abhinavxt.newsforge.data.model

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier

/**
 * An article after ingest, before it becomes a Room row.
 *
 * Deliberately independent of Room: keeping the domain model free of `@Entity` is what
 * lets the whole ingest path be covered by local unit tests, and it means a schema change
 * does not ripple into the pipeline.
 */
data class NewArticle(
    val id: String,
    val feedId: String,
    val title: String,
    val summary: String?,
    val link: String,
    val canonicalUrl: String,
    val sourceName: String,
    val category: Category,
    val tier: SourceTier,
    val publishedAt: Long,
    val fetchedAt: Long,
    val hadPublishedDate: Boolean,
    val tokens: Set<String>,
    val symbols: List<String>,
    val sectors: List<String>,
)

/** One story as the UI reads it: the cluster anchor plus who else carried it. */
data class ArticleSummary(
    val id: String,
    val clusterId: String,
    val title: String,
    val summary: String?,
    val link: String,
    val sourceName: String,
    val category: Category,
    val tier: SourceTier,
    val publishedAt: Long,
    val symbols: List<String>,
    val sectors: List<String> = emptyList(),
    val clusterSize: Int,
    val otherSources: List<String>,
    val read: Boolean,
    val saved: Boolean,
)

/** An [ArticleSummary] with its computed rank. Score is never persisted — see FeedRanking. */
data class ScoredArticle(
    val article: ArticleSummary,
    val score: Double,
)

/** What one feed poll did, for the health screen. */
data class FeedOutcome(
    val feedId: String,
    val feedName: String,
    val status: Status,
    val itemsSeen: Int,
    val itemsNew: Int,
    val message: String?,
) {
    enum class Status { OK, NOT_MODIFIED, FAILED }
}

/** The result of one full refresh across every enabled feed. */
data class SyncReport(
    val startedAt: Long,
    val finishedAt: Long,
    val outcomes: List<FeedOutcome>,
    val prunedArticles: Int,
    /** Rows genuinely inserted this sync, in the shape the alert policy needs. */
    val candidates: List<com.abhinavxt.newsforge.core.notify.AlertCandidate> = emptyList(),
    /** True when the store was empty before this sync — suppresses the initial barrage. */
    val firstRun: Boolean = false,
) {
    val newArticles: Int get() = outcomes.sumOf { it.itemsNew }
    val failedFeeds: List<FeedOutcome> get() = outcomes.filter { it.status == FeedOutcome.Status.FAILED }
    /** True when every feed failed, which usually means no network rather than dead feeds. */
    val allFailed: Boolean get() = outcomes.isNotEmpty() && failedFeeds.size == outcomes.size
}
