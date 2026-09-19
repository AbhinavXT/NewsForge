package com.abhinavxt.newsforge.di

import android.content.Context
import com.abhinavxt.newsforge.data.AlertPreferences
import com.abhinavxt.newsforge.data.DeskPreferences
import com.abhinavxt.newsforge.data.DeskRepository
import com.abhinavxt.newsforge.data.FeedPreferences
import com.abhinavxt.newsforge.data.NewsRepository
import com.abhinavxt.newsforge.data.CandleRepository
import com.abhinavxt.newsforge.data.tag.SymbolDirectory
import com.abhinavxt.newsforge.data.PriceHistoryRepository
import com.abhinavxt.newsforge.data.QuoteRepository
import com.abhinavxt.newsforge.data.VolumeHistoryRepository
import com.abhinavxt.newsforge.data.WatchPreferences
import com.abhinavxt.newsforge.data.db.NewsForgeDatabase
import com.abhinavxt.newsforge.data.net.FeedFetcher
import com.abhinavxt.newsforge.data.tag.InstrumentRepository
import com.abhinavxt.newsforge.data.tag.SymbolLexiconProvider
import com.abhinavxt.newsforge.notify.Notifier

/**
 * Manual dependency container.
 *
 * No Hilt: this app has one graph with four objects in it, and an annotation processor
 * plus a compiler plugin to wire that would cost more build time than it saves.
 * Everything is lazy so nothing touches disk on the main thread at process start.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: NewsForgeDatabase by lazy { NewsForgeDatabase.build(appContext) }

    private val fetcher: FeedFetcher by lazy { FeedFetcher(FeedFetcher.defaultClient()) }

    val alertPreferences: AlertPreferences by lazy { AlertPreferences(appContext) }

    private val feedPreferences: FeedPreferences by lazy { FeedPreferences(appContext) }

    val deskPreferences: DeskPreferences by lazy { DeskPreferences(appContext) }

    val watchPreferences: WatchPreferences by lazy { WatchPreferences(appContext) }

    val notifier: Notifier by lazy { Notifier(appContext) }

    /**
     * Its own repository, sharing only the HTTP client.
     *
     * Nothing from the desk reaches the article store or the news ranker.
     */
    /**
     * Shares the fetcher with the feeds, which is the point: the NSE quote endpoint wants
     * the same primed session the filing feeds already establish.
     */
    val instrumentRepository: InstrumentRepository by lazy {
        InstrumentRepository(appContext, fetcher)
    }

    val priceHistoryRepository: PriceHistoryRepository by lazy {
        PriceHistoryRepository(database.priceSampleDao())
    }

    val volumeHistoryRepository: VolumeHistoryRepository by lazy {
        VolumeHistoryRepository(database.volumeSampleDao())
    }

    /**
     * Built after [deskRepository] on purpose: it asks the desk for history, so it needs
     * something to ask through. Handed the send function rather than the repository so
     * nothing in the candle store can reach the rest of the bridge.
     */
    val candleRepository: CandleRepository by lazy {
        CandleRepository(
            dao = database.candleDao(),
            publish = { line -> deskRepository.send(line) },
        )
    }

    val symbolDirectory: SymbolDirectory by lazy { SymbolDirectory(appContext) }

    val quoteRepository: QuoteRepository by lazy {
        QuoteRepository(fetcher, history = priceHistoryRepository, volume = volumeHistoryRepository)
    }

    val deskRepository: DeskRepository by lazy {
        DeskRepository(
            deskDao = database.deskDao(),
            fetcher = fetcher,
            history = priceHistoryRepository,
            candles = candleRepository,
            preferences = deskPreferences,
        )
    }

    val repository: NewsRepository by lazy {
        NewsRepository(
            articleDao = database.articleDao(),
            feedStateDao = database.feedStateDao(),
            fetcher = fetcher,
            feedDao = database.feedDao(),
            watchlistDao = database.watchlistDao(),
            notifiedDao = database.notifiedDao(),
            calendarDao = database.calendarDao(),
            screenDao = database.screenResultDao(),
            muteDao = database.muteDao(),
            feedPreferences = feedPreferences,
            lexicon = SymbolLexiconProvider.lexicon(appContext),
        )
    }
}
