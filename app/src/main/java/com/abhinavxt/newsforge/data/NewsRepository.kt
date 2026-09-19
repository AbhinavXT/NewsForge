@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.abhinavxt.newsforge.data

import android.util.Log
import com.abhinavxt.newsforge.core.backup.FeedBackup
import com.abhinavxt.newsforge.core.backup.FeedMergeResult
import com.abhinavxt.newsforge.core.backup.FeedRecord
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
import com.abhinavxt.newsforge.core.desk.PositionSnapshot
import com.abhinavxt.newsforge.core.desk.ScreenResult
import com.abhinavxt.newsforge.core.notify.WatchSource
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
import com.abhinavxt.newsforge.data.db.ScreenResultDao
import com.abhinavxt.newsforge.data.db.ScreenResultEntity
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
import com.abhinavxt.newsforge.data.model.WatchlistView
import com.abhinavxt.newsforge.data.model.SyncReport
import com.abhinavxt.newsforge.data.net.BackupJson
import com.abhinavxt.newsforge.data.net.CacheValidators
import com.abhinavxt.newsforge.data.net.FeedFetcher
import com.abhinavxt.newsforge.data.net.FetchResult
import com.abhinavxt.newsforge.data.net.NseJson
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    private val screenDao: ScreenResultDao,
    private val muteDao: MuteDao,
    private val feedPreferences: FeedPreferences,
    private val lexicon: SymbolLexicon = SeedSymbols.LEXICON,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Where ranking and row mapping happen.
     *
     * Room confines the query itself, but every operator downstream of it runs wherever
     * the flow is collected — which for a ViewModel is the main thread. Scoring and
     * sorting a week of stories there is a frame budget the feed does not have, so the
     * read side declares its own dispatcher rather than trusting the collector's.
     */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /** Serialises refreshes; a manual pull during a scheduled sync must not double-insert. */
    private val refreshLock = Mutex()

    /** Last completed refresh. Read and written under [refreshLock] only. */
    private var lastReport: SyncReport? = null

    // ------------------------------------------------------------------ read

    /**
     * Ranked stories from the last [Retention.KEEP_DAYS] days.
     *
     * Scores are recomputed on every emission rather than stored, so the ordering is
     * correct for the moment it is rendered. The flow re-emits on database change, not on
     * the passage of time — the UI refreshes on resume to pick up decay.
     */
    fun stories(symbols: List<String> = emptyList()): Flow<List<ScoredArticle>> =
        // The cutoff is recomputed per tick rather than captured once. This flow is built
        // when the feed's view model is constructed and collected for as long as the app
        // is in front of someone, so a cutoff fixed at construction stops sliding the
        // moment it is taken — leave the feed open overnight and it is still showing the
        // window that closed when it was opened.
        ticks(WINDOW_TICK_MS, clock)
            .flatMapLatest { now ->
                val since = Retention.cutoffMillis(now)
                if (symbols.isEmpty()) {
                    articleDao.stories(since)
                } else {
                    articleDao.storiesForSymbols(since, symbols)
                }
            }
            .map { list -> FeedRanking.rank(list.map { it.toSummary() }, clock()) }
            .flowOn(computeDispatcher)

    /**
     * Free-text search, over a wider window than the feed.
     *
     * Reaches back to the followed-symbol retention floor rather than the feed's, because
     * the reason to search is usually to find something remembered but no longer
     * scrollable — and those are exactly the older ones.
     */
    fun search(text: String): Flow<List<ScoredArticle>> {
        val trimmed = text.trim()
        if (trimmed.length < MIN_SEARCH_CHARS) return flowOf(emptyList())
        return articleDao.search(
            text = escapeLike(trimmed),
            symbol = trimmed.uppercase(),
            since = Retention.followedCutoffMillis(clock()),
        )
            .map { list -> FeedRanking.rank(list.map { it.toSummary() }, clock()) }
            .flowOn(computeDispatcher)
    }

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
            .flowOn(computeDispatcher)

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
     * The configured feeds, as a file.
     *
     * Feed configuration is the one part of this app genuinely expensive to lose: the
     * articles regenerate on the next sync, but a feed list is an accumulation of URLs
     * found over months, several of which no longer appear anywhere obvious on the sites
     * that publish them.
     *
     * Built-in feeds are included. They are exported for their edits — a renamed or
     * disabled built-in is a decision worth carrying to a new phone — and the file says
     * nothing about which were built in, so a restore onto a build with a different seed
     * list simply treats them as feeds.
     */
    suspend fun exportFeeds(): String = withContext(ioDispatcher) {
        BackupJson.encode(
            records = FeedBackup.toRecords(feedDao.all().map { it.toRecord() }),
            exportedAtIso = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(clock()),
                ZoneId.systemDefault(),
            ).toString(),
        )
    }

    /**
     * Folds a backup file into the configured feeds.
     *
     * Merge, never replace — see [FeedBackup.merge]. Nothing is deleted, polling state is
     * untouched, and a feed already present keeps its id so its history survives.
     *
     * @throws com.abhinavxt.newsforge.data.net.BackupFormatException if the file is not a
     *   backup this build can read.
     */
    suspend fun importFeeds(text: String): FeedMergeResult = withContext(ioDispatcher) {
        val rows = BackupJson.decode(text)
        val restored = FeedBackup.fromRecords(rows)
        val existing = feedDao.all()
        val result = FeedBackup.merge(
            existing = existing.map { it.toRecord() },
            restored = restored,
            newId = { Ingest.idFor(it.url) },
        )

        if (result.toSave.isNotEmpty()) {
            val builtIn = existing.associate { it.id to it.builtIn }
            feedDao.upsertAll(
                result.toSave.map { record ->
                    // builtIn is never taken from the file: whether a feed ships with the
                    // app is a fact about this build, and a crafted file must not be able
                    // to make a row undeletable.
                    record.toEntity(builtIn = builtIn[record.id] ?: false)
                }
            )
        }
        Log.i(TAG, "import: +${result.added} ~${result.updated} =${result.unchanged}")
        // Counted against what the file actually contained, so a row dropped for having
        // no URL and a row dropped for repeating one already in the file both show up.
        // Silence about either would make a half-read file look like a clean restore.
        result.copy(
            skipped = rows.size - (result.added + result.updated + result.unchanged)
        )
    }

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

    fun watchlistTiers(): Flow<Map<String, WatchTier>> =
        watchlistView().map { it.effective }

    /**
     * The watchlist split by source, for screens that have to explain a tier as well as
     * apply it. One subscription; the three maps are derived from the same rows.
     */
    fun watchlistView(): Flow<WatchlistView> = watchlistDao.observeAll().map { rows ->
        val manual = rows.filter { it.source == WatchSource.MANUAL.name }
        val desk = rows.filter { it.source == WatchSource.DESK.name }
        WatchlistView(
            effective = strongest(rows),
            manual = manual.associate { it.symbol to WatchTier.parse(it.tier) },
            desk = desk.associate { it.symbol to WatchTier.parse(it.tier) },
        )
    }

    /**
     * The effective tier per symbol: the stronger of what you set and what you hold.
     *
     * A symbol can have a row from each source. Taking the maximum means buying something
     * on margin raises how loudly it interrupts you without discarding the tier you chose,
     * and selling it drops back to that tier rather than to silence.
     */
    private fun strongest(rows: List<WatchlistEntity>): Map<String, WatchTier> {
        val out = HashMap<String, WatchTier>(rows.size)
        for (row in rows) {
            val tier = WatchTier.parse(row.tier)
            val held = out[row.symbol]
            if (held == null || tier.order > held.order) out[row.symbol] = tier
        }
        return out
    }

    /** Sets a symbol's tier by hand, adding it to the watchlist if it is not there yet. */
    suspend fun setWatchTier(symbol: String, tier: WatchTier) = withContext(ioDispatcher) {
        val normalised = symbol.trim().uppercase()
        // Always written as MANUAL. Editing a tier the desk supplied does not take the
        // row over — it adds your own opinion next to it, which the next snapshot is
        // then free to disagree with without erasing what you said.
        watchlistDao.add(
            WatchlistEntity(normalised, WatchSource.MANUAL.name, clock(), tier.name)
        )
    }

    /**
     * Every symbol worth having a price for: tagged in stored news, or followed.
     *
     * The watchlist is included even when nothing has been written about a name today —
     * those are the ones you would open the app to check.
     */
    suspend fun pricedSymbols(): Set<String> = withContext(ioDispatcher) {
        val tagged = articleDao.distinctSymbols()
        val followed = watchlistDao.symbols()
        (tagged + followed).mapTo(HashSet()) { it.uppercase() }
    }

    suspend fun watchlistSymbols(): Map<String, WatchTier> = withContext(ioDispatcher) {
        strongest(watchlistDao.entries())
    }

    /**
     * Screens the desk has reported, as name to symbols.
     *
     * Each run replaces its own screen and touches no other: one message need not know
     * about every screen there is, so a momentum run must not clear a breakdown list it
     * has never heard of. An empty run is kept rather than deleted — "nothing passed this
     * morning" is an answer, and dropping the screen would make it look like the screener
     * had stopped running.
     */
    fun screens(): Flow<Map<String, List<String>>> = screenDao.observeAll().map { rows ->
        rows.associate { row ->
            row.name to row.symbols.split(',').filter { it.isNotBlank() }
        }
    }

    suspend fun applyScreens(screens: List<ScreenResult>) = withContext(ioDispatcher) {
        if (screens.isEmpty()) return@withContext
        screenDao.upsertAll(
            screens.map { screen ->
                ScreenResultEntity(
                    name = screen.name,
                    symbols = screen.symbols.joinToString(","),
                    updatedAt = screen.takenAtMillis ?: clock(),
                )
            }
        )
        // A screen nobody has refreshed in a fortnight is a filter the desk has stopped
        // producing, and a stale chip is worse than a missing one.
        screenDao.pruneOlderThan(clock() - SCREEN_KEEP_MS)
        Log.i(TAG, "screens: ${screens.joinToString { "${it.name}=${it.symbols.size}" }}")
    }

    /**
     * Portfolio share per symbol, where the desk reported one.
     *
     * Only desk rows carry a weight — a symbol you added by hand is something you want to
     * hear about, not something you are exposed to, and inventing a size for it would let
     * the alert rules act on a number nobody supplied.
     */
    suspend fun positionWeights(): Map<String, Double> = withContext(ioDispatcher) {
        watchlistDao.entriesFrom(WatchSource.DESK.name)
            .mapNotNull { row -> row.weight?.let { row.symbol to it } }
            .toMap()
    }

    /**
     * Replaces everything the desk contributed with one snapshot.
     *
     * Wholesale rather than a merge, because the snapshot is the complete truth about
     * your exposure at that moment — a position missing from it is one you no longer
     * hold, and there is no way to say that by adding rows.
     *
     * Manual rows are untouched. That is the entire point of keeping the two sources
     * apart: this runs unattended, several times an hour, and must never be able to
     * delete a name you chose to follow.
     */
    suspend fun applyPositions(snapshot: PositionSnapshot) = withContext(ioDispatcher) {
        val at = snapshot.takenAtMillis ?: clock()
        watchlistDao.replaceSource(
            source = WatchSource.DESK.name,
            entries = snapshot.tiers.map { (symbol, tier) ->
                WatchlistEntity(
                    symbol = symbol,
                    source = WatchSource.DESK.name,
                    addedAt = at,
                    tier = tier.name,
                    weight = snapshot.weights[symbol],
                )
            },
        )
        Log.i(TAG, "positions: ${snapshot.tiers.size} from desk")
    }

    suspend fun alreadyNotified(since: Long): Set<String> =
        withContext(ioDispatcher) { notifiedDao.recent(since).toSet() }

    suspend fun markNotified(clusterIds: List<String>) = withContext(ioDispatcher) {
        val at = clock()
        notifiedDao.mark(clusterIds.map { NotifiedEntity(it, at) })
    }

    /**
     * @return true if the symbol is now followed by hand.
     *
     * Only ever touches the manual row. Unfollowing something you also hold leaves the
     * position's row in place — you are still exposed to it, and a long-press in a list
     * is not the way to tell the app otherwise.
     */
    suspend fun toggleWatchlist(symbol: String): Boolean = withContext(ioDispatcher) {
        val normalised = symbol.trim().uppercase()
        val manual = WatchSource.MANUAL.name
        if (watchlistDao.entries().any { it.symbol == normalised && it.source == manual }) {
            watchlistDao.remove(normalised, manual)
            false
        } else {
            watchlistDao.add(
                WatchlistEntity(normalised, manual, clock(), WatchTier.DEFAULT.name)
            )
            true
        }
    }

    suspend fun markRead(clusterId: String, read: Boolean = true) =
        withContext(ioDispatcher) { articleDao.setRead(clusterId, read) }

    suspend fun setSaved(articleId: String, saved: Boolean) =
        withContext(ioDispatcher) { articleDao.setSaved(articleId, saved) }

    // ----------------------------------------------------------------- write

    /**
     * Polls every enabled feed and stores what is new.
     *
     * @param minGapMillis when a refresh finished less than this ago, that refresh's
     *   report is returned instead of polling again. Zero, the default, always fetches.
     *
     *   Three things drive refreshes — the periodic worker, the live watch, and the feed
     *   while it is open — and they serialise on the same lock. Without a gap, a caller
     *   that arrives mid-sync waits for it to finish and then immediately repeats every
     *   request it just made. Most answer 304, but that is still a round trip per feed
     *   for nothing, on mobile data, during the session.
     */
    suspend fun refresh(minGapMillis: Long = 0L): SyncReport = refreshLock.withLock {
        val previousRun = lastReport
        if (previousRun != null && minGapMillis > 0L &&
            clock() - previousRun.finishedAt < minGapMillis
        ) {
            return@withLock previousRun
        }

        val report = withContext(ioDispatcher) {
            val startedAt = clock()
            val outcomes = ArrayList<FeedOutcome>()
            val candidates = ArrayList<AlertCandidate>()
            // Checked before anything is inserted, so it means "the store was empty",
            // not "the store is small".
            val firstRun = articleDao.count() == 0

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
            // Primed once, ahead of the fan-out rather than lazily on the first NSE feed.
            // The cookie jar is shared, so parallel NSE requests would otherwise each
            // decide they needed priming and race to install the same session.
            if (feeds.any { it.kind.isNse }) {
                try {
                    fetcher.primeNse()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "NSE priming failed", e)
                }
            }
            for (attempt in fetchAll(feeds)) {
                outcomes += ingest(attempt, window, candidates)
            }

            val cutoff = Retention.cutoffMillis(clock())
            val pruned = articleDao.pruneOlderThan(cutoff) +
                articleDao.pruneToHardFloor(Retention.followedCutoffMillis(clock()))
            notifiedDao.pruneOlderThan(Retention.notifiedCutoffMillis(clock()))
            // Calendar entries are kept far longer than articles: a results date that
            // has passed is still the anchor for the next one.
            calendarDao.pruneOlderThan(clock() - CALENDAR_KEEP_MS)
            SyncReport(startedAt, clock(), outcomes, pruned, candidates, firstRun)
        }

        lastReport = report
        report
    }

    /** One feed's network attempt, carried from the parallel phase to the serial one. */
    private data class FeedAttempt(
        val feed: FeedSource,
        val attemptedAt: Long,
        val previous: FeedStateEntity?,
        val result: FetchResult,
    )

    /**
     * Fetches every feed concurrently, then hands the bodies back in order.
     *
     * Only the network is parallel. Parsing, clustering and insertion stay serial in
     * [refresh], because they share one in-memory cluster window and one write path, and
     * making those concurrent would buy nothing — the time is all in the round trips.
     *
     * Sequential polling could not hold the cadence it promised: twenty feeds at a
     * thirty-second call timeout is ten minutes in the worst case, against a live-watch
     * interval of sixty seconds. The gate keeps that from turning into twenty
     * simultaneous connections on a phone.
     */
    private suspend fun fetchAll(feeds: List<FeedSource>): List<FeedAttempt> = coroutineScope {
        val gate = Semaphore(MAX_CONCURRENT_FETCHES)
        feeds.map { feed ->
            async {
                gate.withPermit {
                    val attemptedAt = clock()
                    val previous = feedStateDao.get(feed.id)
                    val result = try {
                        fetcher.fetch(
                            url = feed.url,
                            validators = CacheValidators(previous?.etag, previous?.lastModified),
                            referer = if (feed.kind.isNse) FeedFetcher.NSE_HOME else null,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FetchResult.Failure(-1, e.message ?: e.javaClass.simpleName)
                    }
                    FeedAttempt(feed, attemptedAt, previous, result)
                }
            }
        }.awaitAll()
    }

    /** Parses and stores one fetched feed. Serial: the cluster window is shared. */
    private suspend fun ingest(
        attempt: FeedAttempt,
        window: MutableList<ClusterMember>,
        candidates: MutableList<AlertCandidate>,
    ): FeedOutcome {
        val (feed, attemptedAt, previous, result) = attempt

        return when (result) {
            is FetchResult.NotModified -> {
                recordSuccess(
                    feed, previous, result.validators, attemptedAt,
                    itemCount = 0, status = 304,
                )
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
        // Recorded as sent rather than flattened to 200. Nothing reads the column yet,
        // but a feed answering 304 every cycle and a feed genuinely re-serving the same
        // items look identical once it is, and only one of them means the conditional
        // request is doing its job.
        status: Int = 200,
    ) {
        feedStateDao.upsert(
            FeedStateEntity(
                feedId = feed.id,
                etag = validators.etag,
                lastModified = validators.lastModified,
                lastAttemptAt = attemptedAt,
                lastSuccessAt = attemptedAt,
                lastStatus = status,
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

        /**
         * Below this a search matches most of the database.
         *
         * One character is not a query, and four hundred rows for "a" reads as the search
         * being broken rather than as the reader not having finished typing.
         */
        const val MIN_SEARCH_CHARS = 2

        /**
         * Makes a typed query mean itself inside a LIKE.
         *
         * `%` and `_` are wildcards to SQLite, so without this a reader who types a
         * percent sign gets the entire store back and one who types an underscore gets
         * matches sharing no such character — both of which read as the search being
         * broken rather than as the query being interpreted. The backslash is replaced
         * first, or it would escape the escapes added after it.
         *
         * Paired with the ESCAPE clause on every LIKE in [ArticleDao.search]; neither
         * half does anything without the other.
         */
        internal fun escapeLike(text: String): String = text
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

        /** Calendar history is cheap and useful; 180 days of it costs almost nothing. */
        private const val CALENDAR_KEEP_MS = 180L * 24 * 60 * 60 * 1000

        /** How long a screen survives without the desk refreshing it. */
        private const val SCREEN_KEEP_MS = 14L * 24 * 60 * 60 * 1000

        /**
         * Feeds polled at once.
         *
         * Held well below OkHttp's own ceiling on purpose. This runs on a phone, often on
         * mobile data, and the aim is to stop one slow feed holding up nineteen others —
         * not to open every connection the device will allow.
         */
        private const val MAX_CONCURRENT_FETCHES = 4
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
    feedKind = feedKind.name,
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
        feedKind = enumOrDefault(article.feedKind, FeedKind.RSS),
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

// ------------------------------------------------------------------- backup mapping

/**
 * Only the fields the reader chose.
 *
 * `builtIn` and everything in `feed_state` are left behind deliberately — see
 * [com.abhinavxt.newsforge.core.backup.FeedRecord].
 */
private fun FeedEntity.toRecord() = FeedRecord(
    id = id,
    name = name,
    url = url,
    tier = tier,
    kind = kind,
    categoryHint = categoryHint,
    enabled = enabled,
    position = position,
)

private fun FeedRecord.toEntity(builtIn: Boolean) = FeedEntity(
    // Present by the time this runs: merge fills it in for new feeds.
    id = id.orEmpty(),
    name = name,
    url = url,
    tier = tier,
    kind = kind,
    categoryHint = categoryHint,
    enabled = enabled,
    builtIn = builtIn,
    position = position,
)
