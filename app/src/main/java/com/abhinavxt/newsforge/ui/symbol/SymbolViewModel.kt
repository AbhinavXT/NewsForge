@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package com.abhinavxt.newsforge.ui.symbol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhinavxt.newsforge.core.calendar.UpcomingEvent
import com.abhinavxt.newsforge.core.notify.CompanyAlertLevel
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.core.quote.Candle
import com.abhinavxt.newsforge.core.quote.EventStudy
import com.abhinavxt.newsforge.core.quote.EventStudyResult
import com.abhinavxt.newsforge.core.quote.MarketBreadth
import com.abhinavxt.newsforge.core.quote.PriceSummaries
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.quote.BrokerTarget
import com.abhinavxt.newsforge.core.quote.PriceSummary
import com.abhinavxt.newsforge.core.quote.TargetConsensus
import com.abhinavxt.newsforge.core.quote.TargetPrices
import com.abhinavxt.newsforge.core.quote.VolumeCurve
import com.abhinavxt.newsforge.core.ta.Indicators
import com.abhinavxt.newsforge.core.ta.PriceRange
import com.abhinavxt.newsforge.core.ta.Range
import com.abhinavxt.newsforge.data.CandleRepository
import com.abhinavxt.newsforge.data.DeskRepository
import com.abhinavxt.newsforge.data.NewsRepository
import com.abhinavxt.newsforge.data.PriceHistoryRepository
import com.abhinavxt.newsforge.data.QuoteRepository
import com.abhinavxt.newsforge.data.VolumeHistoryRepository
import com.abhinavxt.newsforge.data.model.ScoredArticle
import com.abhinavxt.newsforge.ui.chart.ChartRange
import com.abhinavxt.newsforge.ui.feed.PriceBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import com.abhinavxt.newsforge.core.quote.Comparison
import com.abhinavxt.newsforge.core.quote.EarningsHistory
import com.abhinavxt.newsforge.core.quote.PriceAlertKind
import com.abhinavxt.newsforge.core.quote.ResultsEvent
import com.abhinavxt.newsforge.core.quote.ResultsStory
import com.abhinavxt.newsforge.core.tag.SymbolEntry
import com.abhinavxt.newsforge.core.tag.SymbolSearch
import com.abhinavxt.newsforge.data.PriceAlertItem
import com.abhinavxt.newsforge.data.PriceAlertRepository
import com.abhinavxt.newsforge.data.tag.SymbolDirectory
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SymbolUiState(
    val symbol: String = "",
    val summary: TimelineSummary? = null,
    val sections: List<TimelineSection> = emptyList(),
    val events: List<UpcomingEvent> = emptyList(),
    val tier: WatchTier? = null,
    /** Share of the portfolio, per cent, when the desk reports one for this name. */
    val positionWeight: Double? = null,
    /** How loudly this company may interrupt; only offered while it is followed. */
    val alertLevel: CompanyAlertLevel = CompanyAlertLevel.DEFAULT,
    val quote: DeskPayload? = null,
    /** What the rest of the index did today, for reading [quote]'s move against. */
    val breadth: MarketBreadth? = null,
    val nowMillis: Long = 0L,
    val loaded: Boolean = false,
)

/**
 * The chart half of the screen, kept apart from [SymbolUiState].
 *
 * Two states rather than one because they change on completely different clocks. The
 * timeline moves when a sync lands; the chart moves when the range button is pressed and
 * again whenever the desk answers, which may be a minute later or never. Folding them
 * together would mean every arriving story recomputes two hundred RSI values, and every
 * range change redraws the story list.
 */
data class SymbolChartState(
    val range: ChartRange = ChartRange.Default,
    val candles: List<Candle> = emptyList(),
    val rsi: List<Double?> = emptyList(),
    val macd: Indicators.Macd = Indicators.Macd(emptyList(), emptyList(), emptyList()),
    val mfi: List<Double?> = emptyList(),
    /**
     * Always the 52-week figure, whatever the chart is showing.
     *
     * Computed from the daily series rather than from the bars on screen, so switching to
     * the one-day view does not quietly relabel a session's high as a year's.
     */
    val yearRange: PriceRange? = null,
    /**
     * The price, from a quote where there is one and from the newest bar where there is
     * not. Lives here rather than in [SymbolUiState] because half its inputs are candles.
     */
    val price: PriceSummary? = null,
    val storyTimes: List<Long> = emptyList(),
    /**
     * A request is out and its reply has not arrived.
     *
     * Separate from `candles.isEmpty()`, which cannot tell the difference between "the
     * desk has not answered yet" and "there is nothing for this company" — and the screen
     * has to, because it used to announce the second the instant it opened and was
     * routinely wrong for the next ten seconds.
     */
    val awaiting: Boolean = false,
    /** Today's volume shape against a normal session; null until there is a baseline. */
    val volume: VolumeCurve? = null,
    /**
     * Quotes and samples for the cards below the chart.
     *
     * Assembled the same way the feed's is, because it is the same thing: a story about
     * this company shows the same reaction figure whichever screen it is read on. It was
     * only missing here — the cards defaulted to an empty book, so the company's own
     * timeline was the one place the price context went away.
     */
    val prices: PriceBook = PriceBook(),
    /**
     * What the brokers covering this name have put in print lately.
     *
     * Null unless at least [TargetPrices.MIN_NOTES] recent notes carried a readable
     * figure. The numbers come out of headline text, so a lone one is never shown: the
     * band across several is the claim, and a single misparse widens it rather than
     * asserting a price.
     */
    val targets: TargetConsensus? = null,
    /** A second company drawn over this one, when the reader asked for one. */
    val compare: CompareLine? = null,
    val loaded: Boolean = false,
)

/**
 * The comparison line and the two figures it is read by.
 *
 * @param line one value per candle on screen, rebased to start with this company.
 */
data class CompareLine(
    val symbol: String,
    val line: List<Double?>,
    val ownPercent: Double?,
    val otherPercent: Double?,
)

class SymbolViewModel(
    private val repository: NewsRepository,
    private val deskRepository: DeskRepository,
    private val quoteRepository: QuoteRepository,
    private val candleRepository: CandleRepository,
    private val volumeHistoryRepository: VolumeHistoryRepository,
    private val priceHistoryRepository: PriceHistoryRepository,
    private val priceAlertRepository: PriceAlertRepository,
    private val directory: SymbolDirectory,
    private val symbol: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val range = MutableStateFlow(ChartRange.Default)

    private val compareSymbol = MutableStateFlow<String?>(null)

    private val compareQuery = MutableStateFlow("")

    /** Companies matching what is typed in the compare picker. */
    val compareMatches: StateFlow<List<SymbolEntry>> = compareQuery
        .debounce(200)
        .mapLatest { query ->
            if (query.trim().length < SymbolSearch.MIN_QUERY) {
                emptyList()
            } else {
                directory.search(query.trim()).filter { it.symbol != symbol }
            }
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val priceAlerts: StateFlow<List<PriceAlertItem>> = priceAlertRepository.alertsFor(symbol)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * This company's results, and how the stock took each one.
     *
     * Daily bars only, whatever range the chart is on: a reaction is close to close, and
     * the intraday series does not reach back to last quarter anyway.
     */
    val earnings: StateFlow<List<ResultsEvent>> = combine(
        repository.storiesForSymbol(symbol),
        candleRepository.series(symbol, ChartRange.YEAR.interval),
    ) { stories, daily ->
        EarningsHistory.of(
            stories
                .filter { it.article.category == Category.RESULTS }
                .map { ResultsStory(it.article.publishedAt, it.article.title, it.article.sourceName) },
            daily,
        )
    }
        .flowOn(Dispatchers.Default)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The same merge the feed does: exchange underneath, desk on top where it has one. */
    private val priceBook = combine(
        deskRepository.latestQuotes(),
        quoteRepository.quotes(),
        priceHistoryRepository.recent(),
        volumeHistoryRepository.relativeVolumes(),
    ) { desk, exchange, samples, volumes -> PriceBook(exchange + desk, samples, volumes) }

    /**
     * Set while a candle request is outstanding; see [SymbolChartState.awaiting].
     *
     * Starts true. The request is fired from `init`, which is a coroutine launch and so
     * lands a frame or two after the first composition — long enough for the screen to
     * paint "no price history" before anything has been asked. Assuming a wait and
     * clearing it is right in both directions: a symbol with bars already stored emits
     * them immediately and never shows the placeholder.
     */
    private val awaiting = MutableStateFlow(true)

    val uiState: StateFlow<SymbolUiState> = combine(
        repository.storiesForSymbol(symbol),
        repository.eventsForSymbol(symbol),
        combine(
            repository.watchlistTiers(),
            repository.watchlistWeights(),
            repository.alertLevels(),
        ) { tiers, weights, levels -> Triple(tiers, weights, levels) },
        // Desk first, exchange behind it. The feed has always merged both through
        // PriceBook; this screen read only the desk, so with TickerForge asleep the
        // company page showed no price at all while the feed two taps away showed one.
        // A desk quote is still preferred where there is one — it is real-time, and the
        // exchange figure is delayed.
        combine(
            deskRepository.latestQuote(symbol),
            quoteRepository.quotes(),
        ) { desk, exchange -> desk ?: exchange[symbol] },
        quoteRepository.breadth(),
    ) { stories, events, watchlist, quote, breadth ->
        val now = clock()
        SymbolUiState(
            symbol = symbol,
            summary = SymbolTimeline.summarize(symbol, stories, events, now),
            sections = SymbolTimeline.sections(stories, now),
            events = events,
            tier = watchlist.first[symbol],
            positionWeight = watchlist.second[symbol],
            alertLevel = watchlist.third[symbol] ?: CompanyAlertLevel.DEFAULT,
            quote = quote,
            breadth = breadth,
            nowMillis = now,
            loaded = true,
        )
    }
        // Ninety days of coverage for a followed name, summarised and sectioned on every
        // database change — not work for the frame the UI is trying to draw.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SymbolUiState(symbol = symbol))

    /**
     * What each story was followed by, measured against the market.
     *
     * Built on [uiState] rather than on a second subscription to the same query, and kept
     * apart from it for the reason [SymbolChartState] is: the samples arrive every minute
     * while the market is open, and folding this into the timeline would re-sort ninety
     * days of coverage each time a price ticked.
     *
     * Keyed by article id, so the timeline can look one up without searching.
     */
    val studyState: StateFlow<Map<String, EventStudyResult>> =
        combine(uiState, priceHistoryRepository.recent()) { state, samples ->
            val own = samples[symbol].orEmpty()
            if (own.isEmpty()) return@combine emptyMap()

            val now = clock()
            // Converted and sorted once for the whole batch. Inside the study each
            // series is sorted defensively, and paying for that per story per peer was
            // the difference between arithmetic and a stall.
            val benchmark = samples.mapValues { (_, points) ->
                EventStudy.fromSamples(points).sortedBy { it.atMillis }
            }
            val series = benchmark[symbol] ?: EventStudy.fromSamples(own)

            state.sections
                .flatMap { it.stories }
                // Older than the sample store, so there is nothing to measure against.
                // Checked here rather than discovered inside the study, so the work is
                // not done at all for the ninety days of coverage this screen holds.
                .filter { it.article.publishedAt >= now - PriceHistoryRepository.KEEP_MS }
                .sortedByDescending { it.article.publishedAt }
                .take(MAX_STUDIES)
                .mapNotNull { scored ->
                    val study = EventStudy.of(
                        symbol = symbol,
                        series = series,
                        publishedAt = scored.article.publishedAt,
                        benchmark = benchmark,
                        nowMillis = now,
                    )
                    if (study.isEmpty) null else scored.article.id to study
                }
                .toMap()
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The chart, its indicators and the story dates to mark on it.
     *
     * The indicators are computed here rather than in the composable because they are a
     * fold over several hundred bars and a composable runs on the frame. Recomputed only
     * when the bars or the range change, which is the point of doing it in a flow.
     */
    val chartState: StateFlow<SymbolChartState> = combine(range, compareSymbol) { r, c -> r to c }
        .flatMapLatest { (selected, other) ->
            val otherBars = other
                ?.let { candleRepository.series(it, selected.interval) }
                ?: flowOf(emptyList())
            combine(
                chartBase(selected),
                otherBars,
            ) { base, bars ->
                if (other == null) {
                    base
                } else {
                    val line = Comparison.rebased(base.candles, bars)
                    base.copy(
                        compare = CompareLine(
                            symbol = other,
                            line = line,
                            ownPercent = Comparison.changePercent(base.candles),
                            otherPercent = Comparison.lineChangePercent(line),
                        ),
                    )
                }
            }
        }
        .flowOn(Dispatchers.Default)
        // Seeded as waiting for the same reason the flag starts true: the first frame
        // renders before any flow has emitted, and "empty" is the wrong thing to say then.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SymbolChartState(awaiting = true))

    /** The chart for one range, before any comparison is drawn over it. */
    private fun chartBase(selected: ChartRange) =
        combine(
            candleRepository.series(symbol, selected.interval),
            candleRepository.series(symbol, ChartRange.YEAR.interval),
            repository.storiesForSymbol(symbol),
            // Desk first, exchange behind it — the same precedence the header uses.
            // Two sources of the same price disagreeing between the header and the
            // chart would be worse than either being missing.
            combine(
                deskRepository.latestQuote(symbol),
                quoteRepository.quotes(),
            ) { desk, exchange -> desk ?: exchange[symbol] },
            // Two small things sharing the last slot. `combine` takes five flows and
            // both of these are one value wide, so pairing them here beats reshaping
            // the whole expression around an arity limit.
            combine(
                awaiting,
                volumeHistoryRepository.curve(symbol),
                priceBook,
            ) { pending, curve, book -> Triple(pending, curve, book) },
        ) { bars, dailyBars, stories, quote, extras ->
            val now = clock()
            val windowed = bars.filter { it.openTimeMillis >= now - selected.windowMillis }
            val closes = windowed.map { it.close }
            SymbolChartState(
                range = selected,
                candles = windowed,
                rsi = Indicators.rsi(closes),
                macd = Indicators.macd(closes),
                mfi = Indicators.mfi(windowed),
                yearRange = Range.over(dailyBars, now) ?: exchangeYearRange(quote),
                price = PriceSummaries.of(quote, dailyBars),
                // Only the stories inside the window, or the tick strip marks days
                // that are not on the axis and every mark is off by the overflow.
                storyTimes = stories
                    .map { it.article.publishedAt }
                    .filter { it >= now - selected.windowMillis },
                targets = TargetPrices.consensus(brokerTargets(stories), now),
                awaiting = extras.first,
                volume = extras.second,
                prices = extras.third,
                loaded = true,
            )
        }

    init {
        // Asked for on open rather than on first draw. The reply arrives on the desk's
        // next poll, which may be a minute away, so the request has to go out before the
        // reader has decided they want it.
        requestHistory(ChartRange.Default)
    }

    /**
     * Pulls readable targets out of the broker notes in this company's timeline.
     *
     * Only stories the categoriser already called a broker call, rather than every
     * headline mentioning a rupee figure. That category has its own tests and its own
     * false-positive discipline; running the parser over everything would mean redoing
     * that work here, worse.
     */
    private fun brokerTargets(stories: List<ScoredArticle>): List<BrokerTarget> =
        stories.asSequence()
            .filter { it.article.category == Category.PRICE_TARGET }
            .mapNotNull { scored ->
                TargetPrices.parse(scored.article.title)?.let { price ->
                    BrokerTarget(
                        price = price,
                        publishedAt = scored.article.publishedAt,
                        source = scored.article.sourceName,
                    )
                }
            }
            .toList()

    fun setRange(selected: ChartRange) {
        range.value = selected
        requestHistory(selected)
        compareSymbol.value?.let { requestHistory(selected, it) }
    }

    fun searchCompare(query: String) {
        compareQuery.value = query
    }

    /** Draws [other] over this company, or clears the comparison with null. */
    fun setCompare(other: String?) {
        compareSymbol.value = other
        compareQuery.value = ""
        other?.let { requestHistory(range.value, it) }
    }

    fun addPriceAlert(kind: PriceAlertKind, threshold: Double) {
        viewModelScope.launch {
            try {
                priceAlertRepository.add(symbol, kind, threshold)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The list not gaining a row is the report.
            }
        }
    }

    fun removePriceAlert(id: Long) {
        viewModelScope.launch {
            try {
                priceAlertRepository.remove(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun rearmPriceAlert(id: Long) {
        viewModelScope.launch {
            try {
                priceAlertRepository.rearm(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Asks the desk for this range's bars, plus the daily series the year bar needs.
     *
     * Asks for the full history rather than only what is on screen. That rationing was
     * worth it against a server that capped a message at four kilobytes and charged by
     * the message; against one that takes a year of bars in a single message it buys
     * nothing and costs the reader a second wait the moment they tap 1Y.
     *
     * The daily series is fetched even while the intraday chart is showing, because the
     * 52-week bar is drawn either way and the exchange's own figures only exist for
     * followed names.
     *
     * [CandleRepository.request] is a no-op when the history on hand is already fresh, so
     * a second visit costs nothing.
     */
    private fun requestHistory(selected: ChartRange, which: String = symbol) {
        viewModelScope.launch {
            try {
                var asked = candleRepository.request(which, selected.interval)
                if (selected.interval != ChartRange.YEAR.interval) {
                    asked = candleRepository.request(which, ChartRange.YEAR.interval) || asked
                }
                if (asked) awaitReply()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The bridge being unreachable is not an error worth a banner here: the
                // chart draws from what is stored, and the desk may simply be asleep.
            } finally {
                // Cleared however this ends — nothing asked, reply collected, request
                // failed, screen closed. The view model outlives a single visit, so a
                // flag left set would greet the next one with a placeholder over data
                // that had arrived minutes ago.
                awaiting.value = false
            }
        }
    }

    /**
     * Polls the bridge for the answer, rather than waiting for a worker to notice.
     *
     * Asking was never the missing half — the command went out the moment this screen
     * opened. Nothing read the reply. Only the desk tab polls while the app is in front of
     * someone, so a request made from here sat unanswered until the fifteen-minute sync
     * happened to run, and the chart stayed empty long enough to look broken.
     *
     * A burst rather than one read, for the reason the desk's own command box uses one:
     * the desk has to receive the line, pull a year of bars from Kite under a rate limit
     * and publish several messages, and a single immediate poll lands before any of that.
     * Delay first for the same reason — there is nothing to collect yet.
     *
     * Cheap to get wrong in only one direction: an extra poll costs a request, a missed
     * one costs the feature.
     */
    private suspend fun awaitReply() {
        for (wait in REPLY_POLL_MS) {
            delay(wait)
            deskRepository.sync()
        }
    }

    /**
     * The 52-week range as the exchange already reports it.
     *
     * A fallback, not a preference: a range folded from stored daily bars is the better
     * figure because it is the same history the chart is drawn from, so the marker and
     * the candles cannot disagree. But it needs a year of bars to have arrived over the
     * bridge, and until they have — or for a reader with no desk at all — NSE has been
     * sending these two numbers in every poll and the app has been discarding them.
     */
    private fun exchangeYearRange(quote: DeskPayload?): PriceRange? {
        val high = quote?.yearHigh ?: return null
        val low = quote.yearLow ?: return null
        val last = quote.ltp ?: return null
        if (high <= low) return null
        return PriceRange(high = high, low = low, last = last)
    }

    fun setTier(tier: WatchTier?) {
        viewModelScope.launch {
            try {
                if (tier == null) repository.toggleWatchlist(symbol) else repository.setWatchTier(symbol, tier)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Tier is a preference, not data; a failed write is not worth a banner.
            }
        }
    }

    fun setAlertLevel(level: CompanyAlertLevel) {
        viewModelScope.launch {
            try {
                repository.setAlertLevel(symbol, level)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A preference, like the tier; a failed write is not worth a banner.
            }
        }
    }

    companion object {
        /**
         * Most stories measured at once.
         *
         * The screen holds ninety days of coverage and the sample store holds two, so in
         * practice the retention filter above cuts far harder than this does. It is a
         * ceiling on the pathological case — a name in the news forty times in two days
         * — where the cross-sectional benchmark would otherwise be recomputed forty
         * times over a couple of hundred peers.
         */
        private const val MAX_STUDIES = 20

        /**
         * How long to keep looking for the desk's reply, in steps.
         *
         * Wider spacing than the desk tab's, because this waits on Kite rather than on a
         * lookup: a first fetch for a symbol backfills a year under a three-per-second
         * rate limit, and the messages arrive in a trickle rather than at once.
         */
        private val REPLY_POLL_MS = longArrayOf(3_000L, 4_000L, 6_000L, 8_000L)

        /**
         * Keyed by symbol at the call site, so navigating from one company to another
         * builds a new view model rather than silently reusing the first one's flows.
         */
        fun factory(
            repository: NewsRepository,
            deskRepository: DeskRepository,
            quoteRepository: QuoteRepository,
            candleRepository: CandleRepository,
            volumeHistoryRepository: VolumeHistoryRepository,
            priceHistoryRepository: PriceHistoryRepository,
            priceAlertRepository: PriceAlertRepository,
            directory: SymbolDirectory,
            symbol: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer<SymbolViewModel> {
                SymbolViewModel(
                    repository = repository,
                    deskRepository = deskRepository,
                    quoteRepository = quoteRepository,
                    candleRepository = candleRepository,
                    volumeHistoryRepository = volumeHistoryRepository,
                    priceHistoryRepository = priceHistoryRepository,
                    priceAlertRepository = priceAlertRepository,
                    directory = directory,
                    symbol = symbol,
                )
            }
        }

    }
}
