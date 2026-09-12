package com.abhinavxt.newsforge.data

import android.util.Log
import com.abhinavxt.newsforge.core.dedupe.ClusterInput
import com.abhinavxt.newsforge.core.dedupe.ClusterMember
import com.abhinavxt.newsforge.core.dedupe.Clusterer
import com.abhinavxt.newsforge.core.feed.FeedParseException
import com.abhinavxt.newsforge.core.feed.FeedParser
import com.abhinavxt.newsforge.core.feed.NseFilings
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.FeedSource
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.core.notify.AlertCandidate
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.core.tag.SeedSymbols
import com.abhinavxt.newsforge.core.tag.SymbolLexicon
import com.abhinavxt.newsforge.data.db.ArticleDao
import com.abhinavxt.newsforge.data.db.ArticleEntity
import com.abhinavxt.newsforge.data.db.ArticleSymbolEntity
import com.abhinavxt.newsforge.core.feed.DefaultFeeds
import com.abhinavxt.newsforge.core.calendar.UpcomingEvent
import com.abhinavxt.newsforge.core.feed.FeedKind
import com.abhinavxt.newsforge.core.feed.FeedSeeding
import com.abhinavxt.newsforge.core.mute.MuteKind
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.core.feed.FeedValidation
import com.abhinavxt.newsforge.data.db.CalendarDao
import com.abhinavxt.newsforge.data.db.CalendarEventEntity
import com.abhinavxt.newsforge.data.db.FeedDao
import com.abhinavxt.newsforge.data.db.MuteDao
import com.abhinavxt.newsforge.data.db.MuteRuleEntity
import com.abhinavxt.newsforge.data.db.FeedEntity
import com.abhinavxt.newsforge.data.db.FeedStateDao
import com.abhinavxt.newsforge.data.db.FeedStateEntity
import com.abhinavxt.newsforge.data.db.NotifiedDao
import com.abhinavxt.newsforge.data.db.NotifiedEntity
import com.abhinavxt.newsforge.data.db.StoryRow
import com.abhinavxt.newsforge.data.db.WatchlistDao
import com.abhinavxt.newsforge.data.db.WatchlistEntity
import com.abhinavxt.newsforge.data.ingest.FeedRanking
import com.abhinavxt.newsforge.data.ingest.Ingest
import com.abhinavxt.newsforge.data.ingest.Retention
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.data.model.FeedOutcome
import com.abhinavxt.newsforge.data.model.NewArticle
import com.abhinavxt.newsforge.data.model.ScoredArticle
import com.abhinavxt.newsforge.data.model.SyncReport
import com.abhinavxt.newsforge.data.net.CacheValidators
import com.abhinavxt.newsforge.data.net.FeedFetcher
import com.abhinavxt.newsforge.data.net.FetchResult
import com.abhinavxt.newsforge.data.net.NseJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single source of truth for news.
 *
 * The read side returns ranked stories; the write side is [refresh], which polls every
 * enabled feed and folds new items into the store. Both are driven by the same clock so
 * that tests can pin time.
 */
class NewsRepository(
    private val articleDao: ArticleDao,
    private val feedStateDao: FeedStateDao,
    private val fetcher: FeedFetcher,
    private val feedDao: FeedDao,
    private val watchlistDao: WatchlistDao,
    private val notifiedDao: NotifiedDao,
    private val calendarDao: CalendarDao,
    private val muteDao: MuteDao,
    private val feedPreferences: FeedPreferences,
    private val lexicon: SymbolLexicon = SeedSymbols.LEXICON,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Serialises refreshes; a manual pull during a scheduled sync must not double-insert. */
    private val refreshLock = Mutex()

    /** Reset each refresh; NSE cookies are primed once per cycle, not per feed. */
    private var nsePrimed = false

    // ------------------------------------------------------------------ read

    /**
     * Ranked stories from the last [Retention.KEEP_DAYS] days.
     *
     * Scores are recomputed on every emission rather than stored, so the ordering is
     * correct for the moment it is rendered. The flow re-emits on database change, not on
     * the passage of time — the UI refreshes on resume to pick up decay.
     */
    fun stories(symbols: List<String> = emptyList()): Flow<List<ScoredArticle>> {
        val since = Retention.cutoffMillis(clock())
        val rows = if (symbols.isEmpty()) {
            articleDao.stories(since)
        } else {
            articleDao.storiesForSymbols(since, symbols)
        }
        return rows.map { list -> FeedRanking.rank(list.map { it.toSummary() }, clock()) }
    }

    /** Everything published since the last session close, ranked. */
    fun overnightBrief(): Flow<List<ScoredArticle>> =
        articleDao.stories(Retention.cutoffMillis(clock()))
            .map { list -> FeedRanking.overnight(list.map { it.toSummary() }, clock()) }

    fun knownSymbols(): Flow<List<String>> = articleDao.knownSymbols()

    fun feedHealth(): Flow<List<FeedStateEntity>> = feedStateDao.observeAll()

    fun configuredFeeds(): Flow<List<FeedEntity>> = feedDao.observeAll()

    fun muteRules(): Flow<List<MuteRule>> =
        muteDao.observeAll().map { rows -> rows.map { MuteRule(parseKind(it.kind), it.value) } }

    suspend fun muteRulesNow(): List<MuteRule> = withContext(ioDispatcher) {
        muteDao.all().map { MuteRule(parseKind(it.kind), it.value) }
    }

    suspend fun addMute(rule: MuteRule) = withContext(ioDispatcher) {
        if (rule.value.isBlank()) return@withContext
        muteDao.add(
            MuteRuleEntity(rule.key, rule.kind.name, rule.value.trim(), clock())
        )
    }

    suspend fun removeMute(rule: MuteRule) = withContext(ioDispatcher) { muteDao.remove(rule.key) }

    private fun parseKind(name: String): MuteKind =
        MuteKind.entries.firstOrNull { it.name == name } ?: MuteKind.KEYWORD

    /**
     * Upcoming events, from the start of today.
     *
     * Bounded at the day boundary rather than at `now` so an ex-date this morning does not
     * vanish from the calendar at 09:16.
     */
    fun upcomingEvents(): Flow<List<UpcomingEvent>> =
        calendarDao.observeFrom(startOfToday()).map { rows -> rows.map { it.toDomain() } }

    /**
     * Every stored story mentioning a symbol, unbounded by the feed's retention window.
     *
     * Deliberately not filtered by [Retention.cutoffMillis] the way the main feed is —
     * followed companies keep ninety days, and a timeline that hid eighty-three of them
     * would defeat its own purpose.
     */
    fun storiesForSymbol(symbol: String): Flow<List<ScoredArticle>> =
        articleDao.storiesForSymbol(symbol.uppercase())
            .map { list -> FeedRanking.rank(list.map { it.toSummary() }, clock()) }

    fun eventsForSymbol(symbol: String): Flow<List<UpcomingEvent>> =
        calendarDao.observeForSymbol(symbol.uppercase()).map { rows -> rows.map { it.toDomain() } }

    private suspend fun storeCalendar(events: List<UpcomingEvent>) {
        if (events.isEmpty()) return
        val at = clock()
        calendarDao.upsertAll(
            events.map {
                CalendarEventEntity(
                    id = it.id,
                    symbol = it.symbol,
                    type = it.type.name,
                    title = it.title,
                    dateMillis = it.dateMillis,
                    sourceFeedId = it.sourceFeedId,
                    seenAt = at,
                )
            }
        )
    }

    private fun startOfToday(): Long = java.time.ZonedDateTime
        .ofInstant(java.time.Instant.ofEpochMilli(clock()), MarketClock.ZONE)
        .toLocalDate()
        .atStartOfDay(MarketClock.ZONE)
        .toInstant()
        .toEpochMilli()

    /**
     * Adds any built-in feed this install has not been offered yet.
     *
     * Not "seed when empty" — that stranded every existing install when the NSE feeds
     * shipped, because their table was already populated and this never ran again. Not a
     * merge on every launch either, which would resurrect feeds the user deleted on
     * purpose. The offered-set is what separates the two cases.
     */
    suspend fun ensureFeedsSeeded() = withContext(ioDispatcher) {
        val existing = feedDao.all().mapTo(HashSet()) { it.id }
        val everSeeded = feedPreferences.everSeeded
        val shipped = DefaultFeeds.ALL.map { it.id }
        val missing = FeedSeeding.missingIds(shipped, existing, everSeeded).toSet()

        if (missing.isNotEmpty()) {
            feedDao.upsertAll(
                DefaultFeeds.ALL
                    .withIndex()
                    .filter { it.value.id in missing }
                    .map { (index, feed) ->
                        FeedEntity(
                            id = feed.id,
                            name = feed.name,
                            url = feed.url,
                            tier = feed.tier.name,
                            kind = feed.kind.name,
                            categoryHint = feed.categoryHint?.name,
                            // Honoured, not forced: the NSE feeds ship off until tested.
                            enabled = feed.enabled,
                            builtIn = true,
                            position = index,
                        )
                    }
            )
        }
        // Recorded even when nothing was inserted, so an install that predates this
        // bookkeeping does not re-offer feeds it has already seen and removed.
        feedPreferences.everSeeded = FeedSeeding.seededAfter(shipped, everSeeded)
    }

    suspend fun saveFeed(feed: FeedEntity) = withContext(ioDispatcher) { feedDao.upsert(feed) }

    /**
     * Restores a built-in feed to its shipped definition.
     *
     * @return false when the id is not a built-in. Leaves [FeedEntity.enabled] alone: a
     *   feed you deliberately switched off should not switch itself back on because you
     *   fixed a typo in its name.
     */
    suspend fun resetFeed(id: String): Boolean = withContext(ioDispatcher) {
        val shipped = DefaultFeeds.byId(id) ?: return@withContext false
        val current = feedDao.all().firstOrNull { it.id == id }
        feedDao.upsert(
            FeedEntity(
                id = shipped.id,
                name = shipped.name,
                url = shipped.url,
                tier = shipped.tier.name,
                kind = shipped.kind.name,
                categoryHint = shipped.categoryHint?.name,
                enabled = current?.enabled ?: true,
                builtIn = true,
                position = current?.position ?: 0,
            )
        )
        true
    }

    suspend fun setFeedEnabled(id: String, enabled: Boolean) =
        withContext(ioDispatcher) { feedDao.setEnabled(id, enabled) }

    /** @return false when the id refers to a built-in, which cannot be deleted. */
    suspend fun deleteFeed(id: String): Boolean =
        withContext(ioDispatcher) { feedDao.deleteCustom(id) > 0 }

    /**
     * Fetches a URL once and reports what came back, without storing anything.
     *
     * The point is to make a bad URL fail loudly at the moment it is typed rather than
     * silently for a week — which is exactly how the seeded publisher feeds could have
     * gone wrong unnoticed.
     */
    suspend fun testFeed(url: String, kind: FeedKind = FeedKind.RSS): String =
      withContext(ioDispatcher) {
        when (
            val result = fetcher.fetch(
                url = FeedValidation.normalizeUrl(url),
                validators = null,
                referer = if (kind.isNse) FeedFetcher.NSE_HOME else null,
                // Always primes: a test is a one-off and must not depend on whether
                // a refresh happened to run first.
                prime = kind.isNse,
            )
        ) {
            is FetchResult.Failure -> {
                if (result.status > 0) "HTTP ${result.status}: ${result.message}" else result.message
            }
            is FetchResult.NotModified -> "Server says unchanged"
            is FetchResult.Success -> try {
                val count = if (kind.isNse) {
                    NseJson.parse(kind, result.bytes).size
                } else {
                    FeedParser.parse(result.bytes).items.size
                }
                when {
                    count > 0 -> "OK — $count items"
                    // For an NSE feed this is the expected shape of a blocked request:
                    // valid JSON arrives, but every row is filtered or the array is empty.
                    kind.isNse -> "Fetched, but no usable filings — check the URL"
                    else -> "Parsed, but no items in it"
                }
            } catch (e: FeedParseException) {
                "Not a feed: ${e.message}"
            }
        }
    }

    private fun needsPriming(kind: FeedKind): Boolean {
        if (!kind.isNse || nsePrimed) return false
        nsePrimed = true
        return true
    }

    private suspend fun activeFeeds(): List<FeedSource> {
        ensureFeedsSeeded()
        return feedDao.all()
            .filter { it.enabled }
            .map { entity ->
                FeedSource(
                    id = entity.id,
                    name = entity.name,
                    url = entity.url,
                    tier = enumOrDefault(entity.tier, SourceTier.AGGREGATOR),
                    kind = enumOrDefault(entity.kind, FeedKind.RSS),
                    categoryHint = entity.categoryHint?.let { hint ->
                        Category.entries.firstOrNull { it.name == hint }
                    },
                    enabled = true,
                )
            }
    }

    fun watchlist(): Flow<List<String>> = watchlistDao.observeSymbols()

    fun watchlistTiers(): Flow<Map<String, WatchTier>> = watchlistDao.observeAll()
        .map { rows -> rows.associate { it.symbol to WatchTier.parse(it.tier) } }

    /** Sets a symbol's tier, adding it to the watchlist if it is not there yet. */
    suspend fun setWatchTier(symbol: String, tier: WatchTier) = withContext(ioDispatcher) {
        val normalised = symbol.trim().uppercase()
        if (watchlistDao.entries().any { it.symbol == normalised }) {
            watchlistDao.setTier(normalised, tier.name)
        } else {
            watchlistDao.add(WatchlistEntity(normalised, clock(), tier.name))
        }
    }

    suspend fun watchlistSymbols(): Map<String, WatchTier> = withContext(ioDispatcher) {
        watchlistDao.entries().associate { it.symbol to WatchTier.parse(it.tier) }
    }

    suspend fun alreadyNotified(since: Long): Set<String> =
        withContext(ioDispatcher) { notifiedDao.recent(since).toSet() }

    suspend fun markNotified(clusterIds: List<String>) = withContext(ioDispatcher) {
        val at = clock()
        notifiedDao.mark(clusterIds.map { NotifiedEntity(it, at) })
    }

    /** @return true if the symbol is now on the watchlist. */
    suspend fun toggleWatchlist(symbol: String): Boolean = withContext(ioDispatcher) {
        val normalised = symbol.trim().uppercase()
        if (normalised in watchlistDao.symbols()) {
            watchlistDao.remove(normalised)
            false
        } else {
            watchlistDao.add(WatchlistEntity(normalised, clock(), WatchTier.DEFAULT.name))
            true
        }
    }

    suspend fun markRead(clusterId: String, read: Boolean = true) =
        withContext(ioDispatcher) { articleDao.setRead(clusterId, read) }

    suspend fun setSaved(articleId: String, saved: Boolean) =
        withContext(ioDispatcher) { articleDao.setSaved(articleId, saved) }

    // ----------------------------------------------------------------- write

    suspend fun refresh(): SyncReport = refreshLock.withLock {
        withContext(ioDispatcher) {
            val startedAt = clock()
            val outcomes = ArrayList<FeedOutcome>()
            val candidates = ArrayList<AlertCandidate>()
            // Checked before anything is inserted, so it means "the store was empty",
            // not "the store is small".
            val firstRun = articleDao.count() == 0
            nsePrimed = false

            // Loaded once for the whole refresh and appended in memory as rows go in, so
            // items arriving in the same sync can cluster with each other and we do not
            // re-query per article.
            val window = articleDao.clusterWindow(Retention.clusterWindowStart(startedAt))
                .mapTo(ArrayList()) {
                    ClusterMember(
                        clusterId = it.clusterId,
                        canonicalUrl = it.canonicalUrl,
                        tokens = splitTokens(it.tokens),
                        symbols = splitTokens(it.symbols),
                        publishedAtMillis = it.publishedAt,
                    )
                }

            val feeds = activeFeeds()
            for (feed in feeds) {
                outcomes += pollFeed(feed, window, candidates)
            }

            val cutoff = Retention.cutoffMillis(clock())
            val pruned = articleDao.pruneOlderThan(cutoff) +
                articleDao.pruneFollowedOlderThan(Retention.followedCutoffMillis(clock()))
            notifiedDao.pruneOlderThan(cutoff)
            // Calendar entries are kept far longer than articles: a results date that
            // has passed is still the anchor for the next one.
            calendarDao.pruneOlderThan(clock() - CALENDAR_KEEP_MS)
            SyncReport(startedAt, clock(), outcomes, pruned, candidates, firstRun)
        }
    }

    private suspend fun pollFeed(
        feed: FeedSource,
        window: MutableList<ClusterMember>,
        candidates: MutableList<AlertCandidate>,
    ): FeedOutcome {
        val attemptedAt = clock()
        val previous = feedStateDao.get(feed.id)
        val validators = CacheValidators(previous?.etag, previous?.lastModified)

        val result = try {
            fetcher.fetch(
                url = feed.url,
                validators = validators,
                referer = if (feed.kind.isNse) FeedFetcher.NSE_HOME else null,
                // One extra request per cycle, not per feed: the session cookies
                // primed for the first NSE feed serve the rest.
                prime = needsPriming(feed.kind),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FetchResult.Failure(-1, e.message ?: e.javaClass.simpleName)
        }

        return when (result) {
            is FetchResult.NotModified -> {
                recordSuccess(feed, previous, result.validators, attemptedAt, itemCount = 0)
                FeedOutcome(feed.id, feed.name, FeedOutcome.Status.NOT_MODIFIED, 0, 0, null)
            }

            is FetchResult.Failure -> {
                recordFailure(feed, previous, attemptedAt, result.status, result.message)
                Log.w(TAG, "Feed ${feed.id} failed: ${result.status} ${result.message}")
                FeedOutcome(feed.id, feed.name, FeedOutcome.Status.FAILED, 0, 0, result.message)
            }

            is FetchResult.Success -> {
                val items = try {
                    if (feed.kind.isNse) {
                        // One decode feeds both consumers: the article parser and the
                        // calendar extractor read the same rows.
                        val rows = NseJson.rows(result.bytes)
                        storeCalendar(NseFilings.calendarEvents(feed.kind, rows, feed.id))
                        NseFilings.parse(feed.kind, rows)
                    } else {
                        FeedParser.parse(result.bytes).items
                    }
                } catch (e: FeedParseException) {
                    recordFailure(feed, previous, attemptedAt, result = -2, message = e.message)
                    Log.w(TAG, "Feed ${feed.id} did not parse: ${e.message}")
                    return FeedOutcome(
                        feed.id, feed.name, FeedOutcome.Status.FAILED, 0, 0, e.message
                    )
                }
                val inserted = store(
                    items.map { Ingest.toArticle(it, feed, attemptedAt, lexicon) },
                    window,
                )
                candidates += inserted
                recordSuccess(feed, previous, result.validators, attemptedAt, items.size)
                FeedOutcome(
                    feed.id, feed.name, FeedOutcome.Status.OK, items.size,
                    inserted.size, null,
                )
            }
        }
    }

    /**
     * Clusters and inserts, skipping anything already stored.
     *
     * @return the rows that were genuinely new, as alert candidates. Returning them
     *   rather than a count is what lets the worker decide on notifications without a
     *   second query for "what changed" — which would be racy against the next sync.
     */
    private suspend fun store(
        candidates: List<NewArticle>,
        window: MutableList<ClusterMember>,
    ): List<AlertCandidate> {
        if (candidates.isEmpty()) return emptyList()

        // A feed can carry the same link twice in one document; keep the first.
        val unique = candidates.distinctBy { it.id }
        val alreadyStored = articleDao.existingIds(unique.map { it.id }).toSet()
        val fresh = unique.filterNot { it.id in alreadyStored }
        if (fresh.isEmpty()) return emptyList()

        val entities = ArrayList<ArticleEntity>(fresh.size)
        val symbolRows = ArrayList<ArticleSymbolEntity>()
        val alerts = ArrayList<AlertCandidate>(fresh.size)

        // Oldest first, so the earliest report of a story becomes the cluster anchor.
        for (article in fresh.sortedBy { it.publishedAt }) {
            val input = ClusterInput(
                id = article.id,
                canonicalUrl = article.canonicalUrl,
                tokens = article.tokens,
                symbols = article.symbols.toSet(),
                publishedAtMillis = article.publishedAt,
            )
            val clusterId = Clusterer.assign(input, window) ?: article.id
            entities += article.toEntity(clusterId)
            symbolRows += article.symbols.map { ArticleSymbolEntity(article.id, it) }
            alerts += AlertCandidate(
                id = article.id,
                clusterId = clusterId,
                title = article.title,
                link = article.link,
                sourceName = article.sourceName,
                category = article.category,
                tier = article.tier,
                publishedAt = article.publishedAt,
                symbols = article.symbols,
            )
            window += ClusterMember(
                clusterId = clusterId,
                canonicalUrl = input.canonicalUrl,
                tokens = input.tokens,
                symbols = input.symbols,
                publishedAtMillis = input.publishedAtMillis,
            )
        }

        val rowIds = articleDao.insertArticles(entities)
        articleDao.insertSymbols(symbolRows)
        // A -1 row id means the insert was ignored because the row already existed.
        val accepted = HashSet<String>(entities.size)
        for ((index, id) in rowIds.withIndex()) {
            if (id != -1L) accepted += entities[index].id
        }
        return alerts.filter { it.id in accepted }
    }

    private suspend fun recordSuccess(
        feed: FeedSource,
        previous: FeedStateEntity?,
        validators: CacheValidators,
        attemptedAt: Long,
        itemCount: Int,
    ) {
        feedStateDao.upsert(
            FeedStateEntity(
                feedId = feed.id,
                etag = validators.etag,
                lastModified = validators.lastModified,
                lastAttemptAt = attemptedAt,
                lastSuccessAt = attemptedAt,
                lastStatus = 200,
                lastError = null,
                lastItemCount = if (itemCount > 0) itemCount else previous?.lastItemCount ?: 0,
                consecutiveFailures = 0,
            )
        )
    }

    private suspend fun recordFailure(
        feed: FeedSource,
        previous: FeedStateEntity?,
        attemptedAt: Long,
        result: Int,
        message: String?,
    ) {
        feedStateDao.upsert(
            FeedStateEntity(
                feedId = feed.id,
                // Validators are kept: a transient failure must not force a full download
                // on the next poll.
                etag = previous?.etag,
                lastModified = previous?.lastModified,
                lastAttemptAt = attemptedAt,
                lastSuccessAt = previous?.lastSuccessAt,
                lastStatus = result,
                lastError = message,
                lastItemCount = previous?.lastItemCount ?: 0,
                consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
            )
        )
    }

    companion object {
        private const val TAG = "NewsRepository"

        /** Calendar history is cheap and useful; 180 days of it costs almost nothing. */
        private const val CALENDAR_KEEP_MS = 180L * 24 * 60 * 60 * 1000
    }
}

// ---------------------------------------------------------------------- mapping

private fun NewArticle.toEntity(clusterId: String) = ArticleEntity(
    id = id,
    clusterId = clusterId,
    feedId = feedId,
    title = title,
    summary = summary,
    link = link,
    canonicalUrl = canonicalUrl,
    sourceName = sourceName,
    category = category.name,
    tier = tier.name,
    publishedAt = publishedAt,
    fetchedAt = fetchedAt,
    hadPublishedDate = hadPublishedDate,
    tokens = tokens.joinToString(" "),
    symbols = symbols.joinToString(" "),
    sectors = sectors.joinToString(" "),
)

private fun StoryRow.toSummary(): ArticleSummary {
    val sourceList = sources.orEmpty().split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != article.sourceName }
        .distinct()
    return ArticleSummary(
        id = article.id,
        clusterId = article.clusterId,
        title = article.title,
        summary = article.summary,
        link = article.link,
        sourceName = article.sourceName,
        // Enum names come from our own writes, but a downgrade could leave an unknown
        // value in the column; defaulting beats crashing the feed.
        category = enumOrDefault(article.category, Category.OTHER),
        tier = enumOrDefault(article.tier, SourceTier.AGGREGATOR),
        publishedAt = article.publishedAt,
        symbols = splitTokens(article.symbols).toList(),
        sectors = splitTokens(article.sectors).toList(),
        clusterSize = clusterSize,
        otherSources = sourceList,
        read = article.read,
        saved = article.saved,
    )
}

private fun splitTokens(joined: String): Set<String> =
    if (joined.isBlank()) emptySet() else joined.split(' ').filter { it.isNotEmpty() }.toSet()

private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

private fun CalendarEventEntity.toDomain() = UpcomingEvent(
    id = id,
    symbol = symbol,
    type = enumOrDefault(type, com.abhinavxt.newsforge.core.calendar.EventType.BOARD_MEETING),
    title = title,
    dateMillis = dateMillis,
    sourceFeedId = sourceFeedId,
)
