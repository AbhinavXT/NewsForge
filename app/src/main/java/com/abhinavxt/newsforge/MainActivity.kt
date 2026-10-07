package com.abhinavxt.newsforge

import androidx.compose.runtime.CompositionLocalProvider
import com.abhinavxt.newsforge.ui.feed.LocalStoryBriefs
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.content.res.Configuration
import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.abhinavxt.newsforge.ui.NewsForgeRoot
import com.abhinavxt.newsforge.ui.reader.ReaderDeps
import com.abhinavxt.newsforge.ui.theme.NewsForgeTheme
import com.abhinavxt.newsforge.ui.theme.ThemeState

class MainActivity : ComponentActivity() {

    /** Symbol from a tapped event reminder, consumed once by the composition. */
    private val pendingSymbol = mutableStateOf<String?>(null)
    private val pendingDesk = mutableStateOf(false)
    private val pendingWorld = mutableStateOf(false)
    private val pendingStoryLink = mutableStateOf<String?>(null)

    /**
     * Registered unconditionally, because `registerForActivityResult` must be called
     * before the activity is STARTED. Gating the registration itself on the SDK level is
     * a common way to get an IllegalStateException on the branch that does need it.
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationsIfNeeded()
        val container = (application as NewsForgeApp).container
        handleAlertIntent(intent)
        // Before the first frame, so the app never flashes the default theme on its way
        // to the chosen one.
        ThemeState.current = container.appearancePreferences.theme
        ThemeState.mode = container.appearancePreferences.mode
        // Read here as well as by the theme composable, which only learns it after the
        // first frame — too late to stop a light phone flashing the dark palette.
        ThemeState.systemDark = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        setContent {
            NewsForgeTheme {
                CompositionLocalProvider(
                    LocalStoryBriefs provides { clusterId, title ->
                        container.repository.storyBrief(clusterId, title)
                    },
                ) {
                    NewsForgeRoot(
                        repository = container.repository,
                        deskRepository = container.deskRepository,
                        quoteRepository = container.quoteRepository,
                        candleRepository = container.candleRepository,
                        symbolDirectory = container.symbolDirectory,
                        priceHistoryRepository = container.priceHistoryRepository,
                        volumeHistoryRepository = container.volumeHistoryRepository,
                        instrumentRepository = container.instrumentRepository,
                        alertPreferences = container.alertPreferences,
                        deskPreferences = container.deskPreferences,
                        watchPreferences = container.watchPreferences,
                        appearancePreferences = container.appearancePreferences,
                        worldPreferences = container.worldPreferences,
                        narrator = container.narrator,
                        readerDeps = ReaderDeps(
                            reader = container.readerRepository,
                            news = container.repository,
                            saved = container.savedArticles,
                            preferences = container.readerPreferences,
                            lexicon = container::lexicon,
                        ),
                        savedArticles = container.savedArticles,
                        priceAlertRepository = container.priceAlertRepository,
                        openSymbol = pendingSymbol.value,
                        onSymbolConsumed = { pendingSymbol.value = null },
                        openDesk = pendingDesk.value,
                        onDeskConsumed = { pendingDesk.value = false },
                        openWorld = pendingWorld.value,
                        onWorldConsumed = { pendingWorld.value = false },
                        openStoryLink = pendingStoryLink.value,
                        onStoryLinkConsumed = { pendingStoryLink.value = null },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // Leaving the app ends listening; a rotation, which also destroys the activity,
        // must not.
        if (isFinishing) (application as NewsForgeApp).container.narrator.shutdown()
        super.onDestroy()
    }

    /**
     * Fires when an alert is tapped while the app is already running.
     *
     * Requires `launchMode="singleTop"` in the manifest; without it Android creates a
     * second activity instance and this is never called.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlertIntent(intent)
    }

    private fun handleAlertIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_DESK, false) == true) {
            // Consumed, so a rotation does not send the reader back to the desk tab
            // after they have navigated away from it.
            intent.removeExtra(EXTRA_OPEN_DESK)
            pendingDesk.value = true
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_WORLD, false) == true) {
            intent.removeExtra(EXTRA_OPEN_WORLD)
            pendingWorld.value = true
        }
        intent?.getStringExtra(EXTRA_SYMBOL)?.let { symbol ->
            intent.removeExtra(EXTRA_SYMBOL)
            pendingSymbol.value = symbol
        }
        val link = intent?.getStringExtra(EXTRA_STORY_LINK) ?: return
        // Consumed so a configuration change does not reopen the story.
        intent.removeExtra(EXTRA_STORY_LINK)
        // This activity is exported, so any app can hand it this extra. Our own alerts
        // only ever carry article links; anything else is not ours to open.
        if (!isWebLink(link)) return
        intent.getStringExtra(EXTRA_STORY_CLUSTER)?.let { clusterId ->
            intent.removeExtra(EXTRA_STORY_CLUSTER)
            val repository = (application as NewsForgeApp).container.repository
            lifecycleScope.launch {
                runCatching { repository.markRead(clusterId) }
            }
        }
        pendingStoryLink.value = link
    }

    /**
     * Asked once, on launch, with no rationale dialog.
     *
     * A pre-prompt would be right for a permission the user might not expect, but a news
     * app asking to send news alerts explains itself, and Android already stops asking
     * after two refusals.
     */
    private fun askForNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun isWebLink(link: String): Boolean =
        link.toUri().scheme?.lowercase() in setOf("http", "https")

    companion object {
        const val EXTRA_STORY_LINK = "story_link"
        const val EXTRA_STORY_CLUSTER = "story_cluster"
        const val EXTRA_SYMBOL = "symbol"

        /** Set by a desk-signal notification: the message is not about an article. */
        const val EXTRA_OPEN_DESK = "open_desk"

        /** Set by the world digest: it is about the day's news, not one story. */
        const val EXTRA_OPEN_WORLD = "open_world"
    }
}
