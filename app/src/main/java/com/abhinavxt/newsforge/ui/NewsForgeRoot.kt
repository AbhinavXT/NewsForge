@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abhinavxt.newsforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.newsforge.data.AlertPreferences
import com.abhinavxt.newsforge.data.DeskPreferences
import com.abhinavxt.newsforge.data.DeskRepository
import com.abhinavxt.newsforge.core.mute.AlertSensitivity
import com.abhinavxt.newsforge.data.NewsRepository
import com.abhinavxt.newsforge.data.WatchPreferences
import com.abhinavxt.newsforge.work.MarketWatchWorker
import com.abhinavxt.newsforge.ui.calendar.CalendarScreen
import com.abhinavxt.newsforge.ui.calendar.CalendarViewModel
import com.abhinavxt.newsforge.ui.desk.DeskScreen
import com.abhinavxt.newsforge.ui.desk.DeskViewModel
import com.abhinavxt.newsforge.ui.feed.FeedScreen
import com.abhinavxt.newsforge.ui.feed.FeedUiState
import com.abhinavxt.newsforge.ui.feed.FeedViewModel
import com.abhinavxt.newsforge.ui.health.FeedHealthScreen
import com.abhinavxt.newsforge.ui.health.FeedManagerViewModel
import com.abhinavxt.newsforge.ui.nav.Detail
import com.abhinavxt.newsforge.ui.nav.Nav
import com.abhinavxt.newsforge.ui.nav.NavState
import com.abhinavxt.newsforge.ui.nav.Tab
import com.abhinavxt.newsforge.ui.symbol.SymbolScreen
import com.abhinavxt.newsforge.ui.symbol.SymbolViewModel
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
    alertPreferences: AlertPreferences,
    deskPreferences: DeskPreferences,
    watchPreferences: WatchPreferences,
    openSymbol: String? = null,
    onSymbolConsumed: () -> Unit = {},
) {
    var nav by rememberSaveable(stateSaver = NavSaver) { mutableStateOf(NavState()) }
    var alertsEnabled by rememberSaveable { mutableStateOf(alertPreferences.enabled) }
    var watchEnabled by rememberSaveable { mutableStateOf(watchPreferences.enabled) }
    // Saved as its name rather than the enum: rememberSaveable's default saver only
    // handles Bundle-friendly types, and an enum survives rotation by luck at best.
    var sensitivityName by rememberSaveable { mutableStateOf(alertPreferences.sensitivity.name) }
    val sensitivity = AlertSensitivity.parse(sensitivityName)
    val context = LocalContext.current

    val feedViewModel: FeedViewModel = viewModel(factory = FeedViewModel.factory(repository))
    val state by feedViewModel.uiState.collectAsStateWithLifecycle()

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

    // A tapped event reminder lands on the company's timeline. Consumed immediately so
    // a rotation does not push it a second time.
    LaunchedEffect(openSymbol) {
        openSymbol?.let {
            nav = Nav.pushOn(nav, Tab.NEWS, Detail.Symbol(it))
            onSymbolConsumed()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                for (tab in Tab.entries) {
                    NavigationBarItem(
                        selected = nav.tab == tab,
                        onClick = { nav = Nav.selectTab(nav, tab) },
                        // Label-only: adding an icon set for three destinations would mean
                        // taking on material-icons for nothing.
                        icon = {},
                        label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val detail = nav.current) {
                is Detail.Symbol -> {
                    val symbolViewModel: SymbolViewModel = viewModel(
                        key = "symbol-${detail.symbol}",
                        factory = SymbolViewModel.factory(repository, deskRepository, detail.symbol),
                    )
                    val symbolState by symbolViewModel.uiState.collectAsStateWithLifecycle()
                    SymbolScreen(
                        state = symbolState,
                        onSetTier = symbolViewModel::setTier,
                        onOpenStory = { scored ->
                            feedViewModel.onStoryOpened(scored.article.clusterId)
                            Links.open(context, scored.article.link)
                        },
                        onToggleSave = { scored ->
                            feedViewModel.toggleSaved(scored.article.id, !scored.article.saved)
                        },
                        onBack = { Nav.pop(nav)?.let { nav = it } },
                    )
                }

                null -> when (nav.tab) {
                    Tab.NEWS -> FeedScreen(
                        state = state,
                        onOpenStory = { scored ->
                            feedViewModel.onStoryOpened(scored.article.clusterId)
                            Links.open(context, scored.article.link)
                        },
                        onMarkRead = { scored ->
                            feedViewModel.onStoryOpened(scored.article.clusterId)
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
                        onQueryChange = feedViewModel::setQuery,
                        onToggleSaved = feedViewModel::toggleSavedOnly,
                        onToggleUnread = feedViewModel::toggleUnreadOnly,
                        onClearFilters = feedViewModel::clearFilters,
                        onSetMode = feedViewModel::setMode,
                        onRefresh = feedViewModel::refresh,
                    )

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
                        DeskScreen(
                            state = deskState,
                            onSave = deskViewModel::save,
                            onTest = deskViewModel::test,
                            onRefresh = deskViewModel::refresh,
                            onMarkAllRead = deskViewModel::markAllRead,
                            onOpenLink = { url -> Links.open(context, url) },
                            onClearTest = deskViewModel::clearTest,
                        )
                    }

                    Tab.FEEDS -> {
                        val feedsViewModel: FeedManagerViewModel =
                            viewModel(factory = FeedManagerViewModel.factory(repository))
                        val feedsState by feedsViewModel.uiState.collectAsStateWithLifecycle()
                        FeedHealthScreen(
                            state = feedsState,
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
                            mutes = feedsState.mutes,
                            onRemoveMute = feedsViewModel::removeMute,
                            watchEnabled = watchEnabled,
                            onToggleWatch = { enabled ->
                                watchPreferences.enabled = enabled
                                watchEnabled = enabled
                                // Applied immediately rather than at next launch: a
                                // toggle that does nothing until restart reads as broken.
                                if (enabled) {
                                    MarketWatchWorker.reschedule(context)
                                } else {
                                    MarketWatchWorker.cancel(context)
                                }
                            },
                            onToggleFeed = feedsViewModel::setEnabled,
                            onSaveFeed = feedsViewModel::save,
                            onDeleteFeed = feedsViewModel::delete,
                            onResetFeed = feedsViewModel::reset,
                            onTestFeed = feedsViewModel::test,
                            onClearTest = feedsViewModel::clearTest,
                        )
                    }
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
                    if (detail is Detail.Symbol) add("${tab.name}:${detail.symbol}")
                }
            }
        }
    },
    restore = { saved ->
        val tab = Tab.entries.firstOrNull { it.name == saved.firstOrNull() } ?: Tab.NEWS
        val stacks = LinkedHashMap<Tab, MutableList<Detail>>()
        for (entry in saved.drop(1)) {
            val owner = Tab.entries.firstOrNull { it.name == entry.substringBefore(':') } ?: continue
            stacks.getOrPut(owner) { ArrayList() }.add(Detail.Symbol(entry.substringAfter(':')))
        }
        NavState(tab, stacks.mapValues { it.value.toList() })
    },
)
