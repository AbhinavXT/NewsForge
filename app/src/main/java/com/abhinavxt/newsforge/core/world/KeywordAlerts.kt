package com.abhinavxt.newsforge.core.world

import com.abhinavxt.newsforge.core.notify.AlertCandidate

/** One world story worth a notification, and the followed keyword that earned it. */
data class KeywordAlert(val candidate: AlertCandidate, val keyword: String)

/**
 * Which newly arrived world stories ring for a followed keyword.
 *
 * Deliberately plain next to the market alert policy. There is no score threshold and no
 * tiering: the reader named the subject, so a story about it is relevant by definition.
 * What it does need is restraint, because a followed word in a busy news cycle can match
 * a dozen outlets in one sync.
 */
object KeywordAlerts {

    /** Most notifications one sync may post; the rest are on the tab under Following. */
    const val MAX_PER_SYNC = 3

    /**
     * @param arrivals world stories inserted this sync.
     * @param alreadyNotified clusters that have rung recently, for any reason.
     * @param firstSync true on the first world sync after install or upgrade, when every
     *   stored story is an arrival and none of them is news to the reader.
     * @return newest first, one per story, at most [MAX_PER_SYNC].
     */
    fun select(
        arrivals: List<AlertCandidate>,
        keywords: List<String>,
        alreadyNotified: Set<String>,
        firstSync: Boolean,
    ): List<KeywordAlert> {
        if (firstSync || keywords.isEmpty()) return emptyList()
        return arrivals
            .asSequence()
            .filter { it.clusterId !in alreadyNotified }
            .sortedByDescending { it.publishedAt }
            // One per story: five outlets on one story is one thing that happened.
            .distinctBy { it.clusterId }
            .mapNotNull { candidate ->
                Keywords.firstMatch(keywords, candidate.title, candidate.summary)
                    ?.let { KeywordAlert(candidate, it) }
            }
            .take(MAX_PER_SYNC)
            .toList()
    }
}
