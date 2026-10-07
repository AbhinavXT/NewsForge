@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abhinavxt.newsforge.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.Hairline
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.data.AlertPreferences
import com.abhinavxt.newsforge.data.AppearancePreferences
import com.abhinavxt.newsforge.ui.theme.ThemeState
import com.abhinavxt.newsforge.data.DeskPreferences
import com.abhinavxt.newsforge.data.CandleRepository
import com.abhinavxt.newsforge.data.tag.SymbolDirectory
import com.abhinavxt.newsforge.data.DeskRepository
import com.abhinavxt.newsforge.core.mute.AlertSensitivity
import com.abhinavxt.newsforge.data.NewsRepository
import com.abhinavxt.newsforge.data.PriceHistoryRepository
import com.abhinavxt.newsforge.data.QuoteRepository
import com.abhinavxt.newsforge.data.SavedArticles
import com.abhinavxt.newsforge.data.PriceAlertRepository
import com.abhinavxt.newsforge.ui.reader.ReaderDeps
import com.abhinavxt.newsforge.data.model.ScoredArticle
import com.abhinavxt.newsforge.ui.reader.ReaderScreen
import com.abhinavxt.newsforge.ui.reader.ReaderViewModel
import com.abhinavxt.newsforge.data.VolumeHistoryRepository
import com.abhinavxt.newsforge.data.tag.InstrumentRepository
import com.abhinavxt.newsforge.data.WatchPreferences
import com.abhinavxt.newsforge.work.MarketWatchWorker
import com.abhinavxt.newsforge.ui.calendar.CalendarScreen
import com.abhinavxt.newsforge.ui.calendar.CalendarViewModel
import com.abhinavxt.newsforge.ui.desk.DeskScreen
import com.abhinavxt.newsforge.ui.desk.DeskViewModel
import com.abhinavxt.newsforge.ui.feed.FeedScreen
import com.abhinavxt.newsforge.ui.feed.FeedUiState
import com.abhinavxt.newsforge.ui.feed.FeedViewModel
import com.abhinavxt.newsforge.ui.world.WorldScreen
import com.abhinavxt.newsforge.ui.world.WorldViewModel
import com.abhinavxt.newsforge.ui.feed.PriceBook
import com.abhinavxt.newsforge.ui.feed.CatchUpScreen
import com.abhinavxt.newsforge.ui.health.FeedHealthScreen
import com.abhinavxt.newsforge.ui.health.FeedManagerViewModel
import com.abhinavxt.newsforge.ui.nav.Detail
import com.abhinavxt.newsforge.ui.nav.Nav
import com.abhinavxt.newsforge.ui.nav.NavState
import com.abhinavxt.newsforge.ui.nav.Tab
import com.abhinavxt.newsforge.ui.rollup.SectorsScreen
import com.abhinavxt.newsforge.ui.settings.QuietHours
import com.abhinavxt.newsforge.ui.settings.SettingsScreen
import java.time.LocalTime
import com.abhinavxt.newsforge.ui.symbol.SymbolScreen
import com.abhinavxt.newsforge.ui.symbol.SymbolViewModel
import com.abhinavxt.newsforge.ui.util.BackgroundStart
import com.abhinavxt.newsforge.ui.util.Links
import com.abhinavxt.newsforge.ui.util.Share
import com.abhinavxt.newsforge.ui.feed.StoryPresentation

/**
 * App shell: three tabs, with a detail stack per tab.
 *
 * Navigation state is plain data (see [Nav]) rather than a NavHost. Three tabs and one
 * detail screen taking a single string do not need a graph, and keeping it as data means
 * the fiddly cases — re-selecting a tab, back at a root — are unit-tested instead of being
 * discovered on a device.
 */
@Composable
fun NewsForgeRoot(
    repository: NewsRepository,
    deskRepository: DeskRepository,
    quoteRepository: QuoteRepository,
    candleRepository: CandleRepository,
    symbolDirectory: SymbolDirectory,
    priceHistoryRepository: PriceHistoryRepository,
    volumeHistoryRepository: VolumeHistoryRepository,
    instrumentRepository: InstrumentRepository,
    alertPreferences: AlertPreferences,
    deskPreferences: DeskPreferences,
    watchPreferences: WatchPreferences,
    appearancePreferences: AppearancePreferences,
    readerDeps: ReaderDeps,
    priceAlertRepository: PriceAlertRepository,
    savedArticles: SavedArticles,
    openSymbol: String? = null,
    onSymbolConsumed: () -> Unit = {},
    openDesk: Boolean = false,
    onDeskConsumed: () -> Unit = {},
    openStoryLink: String? = null,
    onStoryLinkConsumed: () -> Unit = {},
) {
    var nav by rememberSaveable(stateSaver = NavSaver) { mutableStateOf(NavState()) }
    var alertsEnabled by rememberSaveable { mutableStateOf(alertPreferences.enabled) }
    var watchEnabled by rememberSaveable { mutableStateOf(watchPreferences.enabled) }
    // Saved as its name rather than the enum: rememberSaveable's default saver only
    // handles Bundle-friendly types, and an enum survives rotation by luck at best.
    var sensitivityName by rememberSaveable { mutableStateOf(alertPreferences.sensitivity.name) }
    val sensitivity = AlertSensitivity.parse(sensitivityName)
    // Held as plain values for rememberSaveable, rebuilt into one object for the screen.
    var quietEnabled by rememberSaveable { mutableStateOf(alertPreferences.quietEnabled) }
    var quietFrom by rememberSaveable { mutableStateOf(alertPreferences.quietFrom.toSecondOfDay()) }
    var quietUntil by rememberSaveable { mutableStateOf(alertPreferences.quietUntil.toSecondOfDay()) }
    var digestEnabled by rememberSaveable { mutableStateOf(alertPreferences.digestEnabled) }
    val context = LocalContext.current

    val feedViewModel: FeedViewModel =
        viewModel(
            factory = FeedViewModel.factory(
                repository,
                deskRepository,
                quoteRepository,
                priceHistoryRepository,
                volumeHistoryRepository,
                symbolDirectory,
                savedArticles,
            )
        )
    val state by feedViewModel.uiState.collectAsStateWithLifecycle()
    val symbolMatches by feedViewModel.symbolMatches.collectAsStateWithLifecycle()

    // Foreground polling, scoped to STARTED so it stops when the app is backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            feedViewModel.autoRefreshWhileVisible()
        }
    }

    // Returning null from Nav.pop means "nothing left to unwind", and leaving the handler
    // disabled hands the press to the system so back actually exits.
    val canGoBack = Nav.pop(nav) != null
    BackHandler(enabled = canGoBack) {
        Nav.pop(nav)?.let { nav = it }
    }

    val pushSymbol: (String) -> Unit = { symbol -> nav = Nav.push(nav, Detail.Symbol(symbol)) }
    // The catch-up queue, as cluster ids, fixed when it starts. Saveable so a rotation
    // mid-session keeps the reader on the same card of the same pile.
    var catchUpIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    // The reader only understands HTML articles; files go straight to the browser.
    val openLink: (String) -> Unit = { link ->
        if (Links.isFile(link)) Links.open(context, link)
        else nav = Nav.push(nav, Detail.Reader(link))
    }
    val openStory: (ScoredArticle) -> Unit = { scored ->
        feedViewModel.onStoryOpened(scored.article.clusterId)
        openLink(scored.article.link)
    }

    // A tapped event reminder lands on the company's timeline. Consumed immediately so
    // a rotation does not push it a second time.
    LaunchedEffect(openSymbol) {
        openSymbol?.let {
            nav = Nav.pushOn(nav, Tab.NEWS, Detail.Symbol(it))
            onSymbolConsumed()
        }
    }

    // A tapped story alert opens the article in the reader, on top of the news tab.
    LaunchedEffect(openStoryLink) {
        openStoryLink?.let {
            if (Links.isFile(it)) Links.open(context, it)
            else nav = Nav.pushOn(nav, Tab.NEWS, Detail.Reader(it))
            onStoryLinkConsumed()
        }
    }

    // A desk signal is not about an article, so it opens the log rather than a story.
    LaunchedEffect(openDesk) {
        if (openDesk) {
            nav = Nav.selectTab(nav, Tab.DESK)
            onDeskConsumed()
        }
    }

    val screen = Screen(nav.tab, nav.current, nav.stacks[nav.tab].orEmpty().size)
    // One factory for the two tabs that share the feed manager. The instance itself is
    // activity-scoped, so both get the same one regardless; this only avoids spelling the
    // dependencies out twice.
    val feedsFactory = FeedManagerViewModel.factory(
        repository,
        instrumentRepository,
        context.contentResolver,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavDock(
                selected = nav.tab,
                onSelect = { tab -> nav = Nav.selectTab(nav, tab) },
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = screen,
            modifier = Modifier
                .padding(padding)
                // Declared as consumed, not merely applied. Everything below this asks the
                // window for insets of its own — each screen's header, the desk's command
                // bar — and without this they get the full figure back and pad a second
                // time for a system bar the container has already cleared. That is the
                // doubled gap above every title, and it is also what would make the
                // keyboard padding below overshoot by the height of the navigation bar
                // instead of landing the composer on top of the keyboard.
                .consumeWindowInsets(padding)
                .fillMaxSize(),
            // Keyed on where you are, not on the whole nav state: a change to some other
            // tab's stack must not replay an animation on the screen being looked at.
            contentKey = { Triple(it.tab, it.detail, it.depth) },
            transitionSpec = { screenTransition(initialState, targetState) },
            label = "screen",
        ) { screen ->
            Column(Modifier.fillMaxSize()) {
                when (val detail = screen.detail) {
                    is Detail.Symbol -> {
                        val symbolViewModel: SymbolViewModel = viewModel(
                            key = "symbol-${detail.symbol}",
                            factory = SymbolViewModel.factory(
                                repository = repository,
                                deskRepository = deskRepository,
                                quoteRepository = quoteRepository,
                                candleRepository = candleRepository,
                                volumeHistoryRepository = volumeHistoryRepository,
                                priceHistoryRepository = priceHistoryRepository,
                                priceAlertRepository = priceAlertRepository,
                                directory = symbolDirectory,
                                symbol = detail.symbol,
                            ),
                        )
                        val earnings by symbolViewModel.earnings.collectAsStateWithLifecycle()
                        val priceAlerts by symbolViewModel.priceAlerts.collectAsStateWithLifecycle()
                        val compareMatches by
                            symbolViewModel.compareMatches.collectAsStateWithLifecycle()
                        val symbolState by symbolViewModel.uiState.collectAsStateWithLifecycle()
                        val symbolChart by symbolViewModel.chartState.collectAsStateWithLifecycle()
                        val symbolStudies by
                            symbolViewModel.studyState.collectAsStateWithLifecycle()
                        SymbolScreen(
                            state = symbolState,
                            chart = symbolChart,
                            studies = symbolStudies,
                            onSetRange = symbolViewModel::setRange,
                            onSetTier = symbolViewModel::setTier,
                            onSetAlertLevel = symbolViewModel::setAlertLevel,
                            onOpenStory = openStory,
                            onToggleSave = { scored ->
                                feedViewModel.toggleSaved(scored.article.id, !scored.article.saved)
                            },
                            onBack = { Nav.pop(nav)?.let { nav = it } },
                            earnings = earnings,
                            priceAlerts = priceAlerts,
                            compareMatches = compareMatches,
                            onSearchCompare = symbolViewModel::searchCompare,
                            onSetCompare = symbolViewModel::setCompare,
                            onAddPriceAlert = symbolViewModel::addPriceAlert,
                            onRemovePriceAlert = symbolViewModel::removePriceAlert,
                            onRearmPriceAlert = symbolViewModel::rearmPriceAlert,
                        )
                    }

                    is Detail.Reader -> {
                        val readerViewModel: ReaderViewModel = viewModel(
                            key = "reader-${detail.url}",
                            factory = ReaderViewModel.factory(readerDeps, detail.url),
                        )
                        val readerState by readerViewModel.uiState.collectAsStateWithLifecycle()
                        val readerContext by readerViewModel.context.collectAsStateWithLifecycle()
                        val readerSettings by readerViewModel.settings.collectAsStateWithLifecycle()
                        val offline by readerViewModel.offline.collectAsStateWithLifecycle()
                        val ready = state as? FeedUiState.Ready
                        ReaderScreen(
                            url = detail.url,
                            state = readerState,
                            context = readerContext,
                            settings = readerSettings,
                            offline = offline,
                            prices = ready?.prices ?: PriceBook(),
                            nowMillis = ready?.nowMillis ?: System.currentTimeMillis(),
                            onOpenInBrowser = { target -> Links.open(context, target) },
                            onShare = { article ->
                                val link = article?.url ?: detail.url
                                Share.text(
                                    context,
                                    article?.let { "${it.title}\n$link" } ?: link,
                                    subject = article?.title,
                                )
                            },
                            onToggleSave = readerViewModel::toggleSaved,
                            onOpenSymbol = pushSymbol,
                            onOpenCoverage = openLink,
                            onSettingsChange = readerViewModel::updateSettings,
                            onPositionChange = readerViewModel::savePosition,
                            onRetry = readerViewModel::retry,
                            onBack = { Nav.pop(nav)?.let { nav = it } },
                        )
                    }

                    is Detail.CatchUp -> {
                        val ready = state as? FeedUiState.Ready
                        // Looked up live, so a save made on a card shows on it, but in the
                        // order captured at the start.
                        val byId = ready?.stories.orEmpty().associateBy { it.article.clusterId }
                        val queue = remember(catchUpIds, byId.isEmpty()) {
                            catchUpIds.mapNotNull { byId[it] }
                        }.map { byId[it.article.clusterId] ?: it }
                        CatchUpScreen(
                            stories = queue,
                            nowMillis = ready?.nowMillis ?: System.currentTimeMillis(),
                            prices = ready?.prices ?: PriceBook(),
                            onRead = { scored ->
                                feedViewModel.onStoryOpened(scored.article.clusterId)
                                openLink(scored.article.link)
                            },
                            onMarkRead = { scored ->
                                feedViewModel.onStoryOpened(scored.article.clusterId)
                            },
                            onToggleSave = { scored ->
                                feedViewModel.toggleSaved(scored.article.id, !scored.article.saved)
                            },
                            onOpenSymbol = pushSymbol,
                            onBack = { Nav.pop(nav)?.let { nav = it } },
                        )
                    }

                    is Detail.Sectors -> {
                        val sectors by feedViewModel.sectorState.collectAsStateWithLifecycle()
                        SectorsScreen(
                            state = sectors,
                            // Tapping a sector is a request to see it, which is the feed
                            // narrowed rather than a third list of the same stories. Popping
                            // first means back from the filtered feed leaves the filter
                            // behind rather than returning to the summary with it applied.
                            onOpenSector = { sector ->
                                feedViewModel.setSector(sector)
                                Nav.pop(nav)?.let { nav = it }
                            },
                            onOpenSymbol = pushSymbol,
                            onBack = { Nav.pop(nav)?.let { nav = it } },
                        )
                    }

                    null -> when (screen.tab) {
                        Tab.NEWS -> FeedScreen(
                            state = state,
                            symbolMatches = symbolMatches,
                            onOpenStory = openStory,
                            onMarkRead = { scored ->
                                feedViewModel.onStoryOpened(scored.article.clusterId)
                            },
                            onToggleRead = { scored ->
                                feedViewModel.setRead(
                                    scored.article.clusterId,
                                    !scored.article.read,
                                )
                            },
                            onShareStory = { scored ->
                                Share.text(
                                    context,
                                    StoryPresentation.shareText(scored.article),
                                    subject = scored.article.title,
                                )
                            },
                            onToggleSave = { scored ->
                                feedViewModel.toggleSaved(scored.article.id, !scored.article.saved)
                            },
                            onSelectGroup = feedViewModel::selectGroup,
                            onOpenSectors = { nav = Nav.push(nav, Detail.Sectors) },
                            onToggleWatchlist = feedViewModel::toggleWatchlistOnly,
                            onSelectSymbol = feedViewModel::setSymbol,
                            onToggleSymbol = feedViewModel::toggleWatchlist,
                            onSetTier = { symbol, tier ->
                                if (tier == null) {
                                    feedViewModel.toggleWatchlist(symbol)
                                } else {
                                    feedViewModel.setTier(symbol, tier)
                                }
                            },
                            onSelectSector = feedViewModel::setSector,
                            onMute = feedViewModel::mute,
                            onOpenSymbol = pushSymbol,
                            onCatchUp = { ids ->
                                catchUpIds = ids
                                nav = Nav.push(nav, Detail.CatchUp)
                            },
                            onQueryChange = feedViewModel::setQuery,
                            onToggleSaved = feedViewModel::toggleSavedOnly,
                            onToggleUnread = feedViewModel::toggleUnreadOnly,
                            onClearFilters = feedViewModel::clearFilters,
                            onToggleScreen = feedViewModel::toggleScreen,
                            onSetMode = feedViewModel::setMode,
                            onRefresh = feedViewModel::refresh,
                        )

                        Tab.WORLD -> {
                            val worldViewModel: WorldViewModel = viewModel(
                                factory = WorldViewModel.factory(repository, savedArticles),
                            )
                            val worldState by worldViewModel.uiState.collectAsStateWithLifecycle()
                            LaunchedEffect(lifecycleOwner) {
                                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    worldViewModel.autoRefreshWhileVisible()
                                }
                            }
                            WorldScreen(
                                state = worldState,
                                onOpenStory = { scored ->
                                    worldViewModel.onStoryOpened(scored.article.clusterId)
                                    openLink(scored.article.link)
                                },
                                onToggleRead = { scored ->
                                    worldViewModel.setRead(
                                        scored.article.clusterId,
                                        !scored.article.read,
                                    )
                                },
                                onToggleSave = { scored ->
                                    worldViewModel.toggleSaved(
                                        scored.article.id,
                                        !scored.article.saved,
                                    )
                                },
                                onSelectTopic = worldViewModel::selectTopic,
                                onToggleUnread = worldViewModel::toggleUnreadOnly,
                                onToggleSaved = worldViewModel::toggleSavedOnly,
                                onQueryChange = worldViewModel::setQuery,
                                onClearFilters = worldViewModel::clearFilters,
                                onRefresh = worldViewModel::refresh,
                            )
                        }

                        Tab.CALENDAR -> {
                            val calendarViewModel: CalendarViewModel =
                                viewModel(factory = CalendarViewModel.factory(repository))
                            val calendarState by calendarViewModel.uiState.collectAsStateWithLifecycle()
                            CalendarScreen(
                                state = calendarState,
                                onToggleFollowedOnly = calendarViewModel::toggleFollowedOnly,
                                onSelectSymbol = pushSymbol,
                            )
                        }

                        Tab.DESK -> {
                            val deskViewModel: DeskViewModel = viewModel(
                                factory = DeskViewModel.factory(deskRepository, deskPreferences),
                            )
                            val deskState by deskViewModel.uiState.collectAsStateWithLifecycle()

                            // Scoped to this tab, not to the app: the effect leaves the
                            // composition when another tab is selected, so the desk is only
                            // polled while it is the thing being looked at.
                            LaunchedEffect(lifecycleOwner) {
                                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    deskViewModel.autoRefreshWhileVisible()
                                }
                            }

                            DeskScreen(
                                state = deskState,
                                onRefresh = deskViewModel::refresh,
                                onMarkAllRead = deskViewModel::markAllRead,
                                onOpenLink = { url -> Links.open(context, url) },
                                onOpenSettings = { nav = Nav.selectTab(nav, Tab.SETTINGS) },
                                onSend = deskViewModel::send,
                            )
                        }

                        Tab.FEEDS -> {
                            val feedsViewModel: FeedManagerViewModel = viewModel(factory = feedsFactory)
                            val feedsState by feedsViewModel.uiState.collectAsStateWithLifecycle()
                            FeedHealthScreen(
                                state = feedsState,
                                nowMillis = (state as? FeedUiState.Ready)?.nowMillis
                                    ?: System.currentTimeMillis(),
                                onToggleFeed = feedsViewModel::setEnabled,
                                onSaveFeed = feedsViewModel::save,
                                onDeleteFeed = feedsViewModel::delete,
                                onResetFeed = feedsViewModel::reset,
                                onTestFeed = feedsViewModel::test,
                                onClearTest = feedsViewModel::clearTest,
                            )
                        }

                        Tab.SETTINGS -> {
                            // The same instances the Desk and Feeds tabs use — view models
                            // are scoped to the activity — so a topic saved here is the one
                            // the Desk tab is already showing when the reader switches back.
                            val feedsViewModel: FeedManagerViewModel = viewModel(factory = feedsFactory)
                            val feedsState by feedsViewModel.uiState.collectAsStateWithLifecycle()
                            val deskViewModel: DeskViewModel = viewModel(
                                factory = DeskViewModel.factory(deskRepository, deskPreferences),
                            )
                            val deskState by deskViewModel.uiState.collectAsStateWithLifecycle()
                            SettingsScreen(
                                theme = ThemeState.current,
                                onSelectTheme = { theme ->
                                    appearancePreferences.theme = theme
                                    ThemeState.current = theme
                                },
                                mode = ThemeState.mode,
                                onSelectMode = { mode ->
                                    appearancePreferences.mode = mode
                                    ThemeState.mode = mode
                                },
                                nowMillis = (state as? FeedUiState.Ready)?.nowMillis
                                    ?: System.currentTimeMillis(),
                                alertsEnabled = alertsEnabled,
                                onToggleAlerts = { enabled ->
                                    alertPreferences.enabled = enabled
                                    alertsEnabled = enabled
                                },
                                sensitivity = sensitivity,
                                onSelectSensitivity = { level ->
                                    alertPreferences.sensitivity = level
                                    sensitivityName = level.name
                                },
                                quietHours = QuietHours(
                                    enabled = quietEnabled,
                                    from = LocalTime.ofSecondOfDay(quietFrom.toLong()),
                                    until = LocalTime.ofSecondOfDay(quietUntil.toLong()),
                                    digest = digestEnabled,
                                ),
                                onSetQuietHours = { quiet ->
                                    alertPreferences.quietEnabled = quiet.enabled
                                    alertPreferences.quietFrom = quiet.from
                                    alertPreferences.quietUntil = quiet.until
                                    alertPreferences.digestEnabled = quiet.digest
                                    quietEnabled = quiet.enabled
                                    quietFrom = quiet.from.toSecondOfDay()
                                    quietUntil = quiet.until.toSecondOfDay()
                                    digestEnabled = quiet.digest
                                },
                                watchEnabled = watchEnabled,
                                watchLastRefusedAt = watchPreferences.lastRefusedAt,
                                onToggleWatch = { enabled ->
                                    watchPreferences.enabled = enabled
                                    watchEnabled = enabled
                                    // Asked at the moment it is wanted, when the reason is
                                    // obvious. Without it the watch only runs on days the
                                    // app is open when a segment begins.
                                    if (enabled && !BackgroundStart.isExempt(context)) {
                                        BackgroundStart.request(context)
                                    }
                                    // Applied immediately rather than at next launch: a
                                    // toggle that does nothing until restart reads as broken.
                                    if (enabled) {
                                        MarketWatchWorker.reschedule(context)
                                    } else {
                                        MarketWatchWorker.cancel(context)
                                    }
                                },
                                desk = deskState,
                                onSaveDesk = deskViewModel::save,
                                onTestDesk = deskViewModel::test,
                                onClearDeskTest = deskViewModel::clearTest,
                                onClearRecentCommands = deskViewModel::clearRecentCommands,
                                feeds = feedsState,
                                onManageFeeds = { nav = Nav.selectTab(nav, Tab.FEEDS) },
                                onRemoveMute = feedsViewModel::removeMute,
                                onRefreshCompanyList = feedsViewModel::refreshCompanyList,
                                onExportTo = feedsViewModel::exportTo,
                                onImportFrom = feedsViewModel::importFrom,
                                onClearBackupMessage = feedsViewModel::clearBackupMessage,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Where the app is, reduced to what decides which screen is drawn. */
private data class Screen(val tab: Tab, val detail: Detail?, val depth: Int)

/**
 * Pushes slide in from the right and pops slide back out, the direction Android has
 * trained everyone to read as "deeper" and "back". Tab switches only fade: tabs are
 * siblings, and sliding between them would claim an order they do not have.
 */
private fun screenTransition(from: Screen, to: Screen): ContentTransform {
    if (from.tab != to.tab) {
        return (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.98f))
            .togetherWith(fadeOut(tween(90)))
    }
    val forward = to.depth > from.depth
    val slide = tween<IntOffset>(300, easing = FastOutSlowInEasing)
    return if (forward) {
        (slideInHorizontally(slide) { it / 4 } + fadeIn(tween(220)))
            .togetherWith(slideOutHorizontally(slide) { -it / 8 } + fadeOut(tween(160)))
    } else {
        (slideInHorizontally(slide) { -it / 8 } + fadeIn(tween(220)))
            .togetherWith(slideOutHorizontally(slide) { it / 4 } + fadeOut(tween(160)))
    }
}

/**
 * The tab bar, as a floating dock.
 *
 * A rounded slab set in from the screen edges rather than a full-width strip: it reads as
 * one control rather than as the bottom of the page, and the selected tab gets a lit pill
 * behind its icon that slides in, so a change of tab is visible out of the corner of an
 * eye without having to read a label.
 */
@Composable
private fun NavDock(selected: Tab, onSelect: (Tab) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, Hairline, RoundedCornerShape(22.dp))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (tab in Tab.entries) {
                val isSelected = tab == selected
                val tint by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.primary else Chalk500,
                    label = "dock-tint",
                )
                val indicator by animateColorAsState(
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        Color.Transparent
                    },
                    label = "dock-indicator",
                )
                val indicatorWidth by animateDpAsState(
                    if (isSelected) 52.dp else 32.dp,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 600f),
                    label = "dock-indicator-width",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .width(indicatorWidth)
                            .height(30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(indicator),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(tabIcon(tab)),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = tint,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * Saves nav across process death as a flat list of strings.
 *
 * `NavState` holds a map of lists, which the default saver cannot bundle. Flattening keeps
 * a rotation on a symbol screen from dumping the user back at the News root.
 */
private val NavSaver = androidx.compose.runtime.saveable.listSaver<NavState, String>(
    save = { state ->
        buildList {
            add(state.tab.name)
            for ((tab, stack) in state.stacks) {
                for (detail in stack) {
                    // tab:kind:payload. The kind is carried explicitly rather than
                    // inferred from the payload's shape, because the second destination
                    // has no payload and a bare "NEWS:" would be indistinguishable from
                    // a symbol screen for a company with an empty name.
                    when (detail) {
                        is Detail.Symbol -> add("${tab.name}:SYMBOL:${detail.symbol}")
                        is Detail.Sectors -> add("${tab.name}:SECTORS:")
                        // The URL's own colons are safe: the split below stops at three parts.
                        is Detail.Reader -> add("${tab.name}:READER:${detail.url}")
                        is Detail.CatchUp -> add("${tab.name}:CATCHUP:")
                    }
                }
            }
        }
    },
    restore = { saved ->
        val tab = Tab.entries.firstOrNull { it.name == saved.firstOrNull() } ?: Tab.NEWS
        val stacks = LinkedHashMap<Tab, MutableList<Detail>>()
        for (entry in saved.drop(1)) {
            val parts = entry.split(':', limit = 3)
            if (parts.size < 3) continue
            val owner = Tab.entries.firstOrNull { it.name == parts[0] } ?: continue
            // An unrecognised kind is dropped rather than guessed at. It means state
            // saved by a different build, and restoring it wrongly would land the reader
            // on a screen they never opened.
            val detail = when (parts[1]) {
                "SYMBOL" -> Detail.Symbol(parts[2])
                "SECTORS" -> Detail.Sectors
                "READER" -> Detail.Reader(parts[2])
                "CATCHUP" -> Detail.CatchUp
                else -> continue
            }
            stacks.getOrPut(owner) { ArrayList() }.add(detail)
        }
        NavState(tab, stacks.mapValues { it.value.toList() })
    },
)

/**
 * Icons for the bottom bar.
 *
 * Mapped here rather than hung off [Tab] so the navigation model stays a pure enum with
 * no resource ids in it — `Nav` is covered by plain JVM tests and should not need an
 * Android `R` on the classpath to be one.
 */
@DrawableRes
private fun tabIcon(tab: Tab): Int = when (tab) {
    Tab.NEWS -> R.drawable.ic_news
    Tab.WORLD -> R.drawable.ic_globe
    Tab.CALENDAR -> R.drawable.ic_calendar
    Tab.DESK -> R.drawable.ic_desk
    Tab.FEEDS -> R.drawable.ic_feeds
    Tab.SETTINGS -> R.drawable.ic_settings
}
