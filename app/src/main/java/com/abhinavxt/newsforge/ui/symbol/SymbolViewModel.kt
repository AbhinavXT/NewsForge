@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.abhinavxt.newsforge.ui.symbol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhinavxt.newsforge.core.calendar.UpcomingEvent
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.core.desk.DeskPayload
import com.abhinavxt.newsforge.core.quote.Candle
import com.abhinavxt.newsforge.core.quote.MarketBreadth
import com.abhinavxt.newsforge.core.ta.Indicators
import com.abhinavxt.newsforge.core.ta.PriceRange
import com.abhinavxt.newsforge.core.ta.Range
import com.abhinavxt.newsforge.data.CandleRepository
import com.abhinavxt.newsforge.data.DeskRepository
import com.abhinavxt.newsforge.data.NewsRepository
import com.abhinavxt.newsforge.data.QuoteRepository
import com.abhinavxt.newsforge.ui.chart.ChartRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SymbolUiState(
    val symbol: String = "",
    val summary: TimelineSummary? = null,
    val sections: List<TimelineSection> = emptyList(),
    val events: List<UpcomingEvent> = emptyList(),
    val tier: WatchTier? = null,
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
    val storyTimes: List<Long> = emptyList(),
    val loaded: Boolean = false,
)

class SymbolViewModel(
    private val repository: NewsRepository,
    private val deskRepository: DeskRepository,
    private val quoteRepository: QuoteRepository,
    private val candleRepository: CandleRepository,
    private val symbol: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val range = MutableStateFlow(ChartRange.Default)

    val uiState: StateFlow<SymbolUiState> = combine(
        repository.storiesForSymbol(symbol),
        repository.eventsForSymbol(symbol),
        repository.watchlistTiers(),
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
    ) { stories, events, tiers, quote, breadth ->
        val now = clock()
        SymbolUiState(
            symbol = symbol,
            summary = SymbolTimeline.summarize(symbol, stories, events, now),
            sections = SymbolTimeline.sections(stories, now),
            events = events,
            tier = tiers[symbol],
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
     * The chart, its indicators and the story dates to mark on it.
     *
     * The indicators are computed here rather than in the composable because they are a
     * fold over several hundred bars and a composable runs on the frame. Recomputed only
     * when the bars or the range change, which is the point of doing it in a flow.
     */
    val chartState: StateFlow<SymbolChartState> = range
        .flatMapLatest { selected ->
            combine(
                candleRepository.series(symbol, selected.interval),
                candleRepository.series(symbol, ChartRange.YEAR.interval),
                repository.storiesForSymbol(symbol),
                quoteRepository.quotes(),
            ) { bars, dailyBars, stories, exchange ->
                val now = clock()
                val windowed = bars.filter { it.openTimeMillis >= now - selected.windowMillis }
                val closes = windowed.map { it.close }
                SymbolChartState(
                    range = selected,
                    candles = windowed,
                    rsi = Indicators.rsi(closes),
                    macd = Indicators.macd(closes),
                    mfi = Indicators.mfi(windowed),
                    yearRange = Range.over(dailyBars, now) ?: exchangeYearRange(exchange[symbol]),
                    // Only the stories inside the window, or the tick strip marks days
                    // that are not on the axis and every mark is off by the overflow.
                    storyTimes = stories
                        .map { it.article.publishedAt }
                        .filter { it >= now - selected.windowMillis },
                    loaded = true,
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SymbolChartState())

    init {
        // Asked for on open rather than on first draw. The reply arrives on the desk's
        // next poll, which may be a minute away, so the request has to go out before the
        // reader has decided they want it.
        requestHistory(ChartRange.Default)
    }

    fun setRange(selected: ChartRange) {
        range.value = selected
        requestHistory(selected)
    }

    /**
     * Asks the desk for whatever this range needs, plus the daily series it does not.
     *
     * The daily bars are fetched even when the reader is looking at the intraday chart,
     * because the 52-week range is shown either way and there is nowhere else to get it.
     * [CandleRepository.request] is a no-op when the history on hand is already fresh, so
     * this costs a message only when it would otherwise be missing data.
     */
    private fun requestHistory(selected: ChartRange) {
        viewModelScope.launch {
            try {
                var asked = candleRepository.request(symbol, selected.interval)
                if (selected.interval != ChartRange.YEAR.interval) {
                    asked = candleRepository.request(symbol, ChartRange.YEAR.interval) || asked
                }
                if (asked) awaitReply()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The bridge being unreachable is not an error worth a banner here: the
                // chart draws from what is stored, and the desk may simply be asleep.
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

    companion object {
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
            symbol: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer<SymbolViewModel> {
                SymbolViewModel(
                    repository = repository,
                    deskRepository = deskRepository,
                    quoteRepository = quoteRepository,
                    candleRepository = candleRepository,
                    symbol = symbol,
                )
            }
        }

    }
}
