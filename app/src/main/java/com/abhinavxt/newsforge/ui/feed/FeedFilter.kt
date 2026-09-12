package com.abhinavxt.newsforge.ui.feed

import com.abhinavxt.newsforge.core.model.CategoryGroup
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.core.mute.MuteRules
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.core.rank.MarketPhase
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.data.model.ScoredArticle
import java.util.Locale

/**
 * How the feed is being read right now.
 *
 * The two modes answer different questions. [BRIEF] is "what happened while I was away,
 * and what am I buying today" — bounded by the previous session close, ranked, read once
 * before the open. [LIVE] is "what just happened" — a running stream during the session.
 * The clock picks the default, but the reader can override it, because wanting last
 * night's news at 11am is entirely reasonable.
 */
enum class FeedMode {
    BRIEF,
    LIVE;

    companion object {
        fun defaultFor(nowMillis: Long): FeedMode =
            when (MarketClock.phase(nowMillis)) {
                MarketPhase.PRE_OPEN -> BRIEF
                MarketPhase.WEEKEND -> BRIEF
                MarketPhase.OPEN -> LIVE
                MarketPhase.POST_CLOSE -> LIVE
            }
    }
}

/**
 * Everything narrowing the feed at once.
 *
 * A null [group] means "All"; a null [symbol] means "every company". [query] is free text.
 * All of them compose, so "unread order wins mentioning TATASTEEL" is expressible without
 * a separate screen for each combination.
 */
data class FeedFilter(
    val group: CategoryGroup? = null,
    val watchlistOnly: Boolean = false,
    val unreadOnly: Boolean = false,
    val savedOnly: Boolean = false,
    val query: String = "",
    val symbol: String? = null,
    val sector: com.abhinavxt.newsforge.core.tag.Sector? = null,
) {
    val isNarrowed: Boolean
        get() = group != null || watchlistOnly || unreadOnly || savedOnly ||
            query.isNotBlank() || symbol != null || sector != null
}

object FeedFiltering {

    /**
     * Applied after ranking, not before, so that the chips reorder nothing — a story's
     * position within "Movers" is the same as its relative position in "All". A filter
     * that also reshuffled would make the chips feel like different feeds rather than
     * views of one.
     */
    fun apply(
        items: List<ScoredArticle>,
        filter: FeedFilter,
        watchlist: Set<String> = emptySet(),
        mutes: List<MuteRule> = emptyList(),
    ): List<ScoredArticle> =
        items.filter { scored ->
            val article = scored.article
            when {
                // Checked first and unconditionally: a mute is not a view, it is a
                // standing instruction, and it should hold whatever chip is active.
                mutes.isNotEmpty() && MuteRules.isMuted(
                    article.sourceName,
                    article.title + " " + article.summary.orEmpty(),
                    article.symbols,
                    mutes,
                ) -> false
                filter.group != null && article.category.group != filter.group -> false
                filter.symbol != null && filter.symbol !in article.symbols -> false
                filter.sector != null && filter.sector.name !in article.sectors -> false
                filter.watchlistOnly && !matchesWatchlist(article.symbols, watchlist) -> false
                filter.unreadOnly && article.read -> false
                filter.savedOnly && !article.saved -> false
                filter.query.isNotBlank() && !matchesQuery(article, filter.query) -> false
                else -> true
            }
        }

    /**
     * With an empty watchlist the chip falls back to "any recognised company".
     *
     * Otherwise the chip would show nothing at all until the user had added a symbol,
     * which reads as a broken filter rather than an empty watchlist.
     */
    internal fun matchesWatchlist(symbols: List<String>, watchlist: Set<String>): Boolean =
        if (watchlist.isEmpty()) symbols.isNotEmpty() else symbols.any { it in watchlist }

    /**
     * Case-insensitive substring match across headline, outlet and tickers.
     *
     * Substring rather than token match because half of what you search for here is a
     * partial company name or a number — "5,000 crore", "Vedan" — and a token matcher
     * would find neither. Every term must appear somewhere, so adding words narrows.
     */
    internal fun matchesQuery(article: ArticleSummary, query: String): Boolean {
        val haystack = buildString {
            append(article.title)
            append(' ')
            append(article.sourceName)
            append(' ')
            append(article.symbols.joinToString(" "))
        }.lowercase(Locale.ROOT)

        return query.trim().lowercase(Locale.ROOT).split(' ')
            .filter { it.isNotEmpty() }
            .all { term -> haystack.contains(term) }
    }

    /**
     * Chips to show, with counts, in a fixed order.
     *
     * Counts come from the unfiltered list so a chip never reads zero because a different
     * chip is active — the count has to mean "stories available here", not "stories
     * surviving the current filter".
     */
    fun chipCounts(items: List<ScoredArticle>): Map<CategoryGroup, Int> =
        CategoryGroup.entries.associateWith { group ->
            items.count { it.article.category.group == group }
        }
}
