package com.abhinavxt.newsforge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.abhinavxt.newsforge.ui.util.Links
import kotlinx.coroutines.launch
import com.abhinavxt.newsforge.ui.NewsForgeRoot
import com.abhinavxt.newsforge.ui.theme.NewsForgeTheme

class MainActivity : ComponentActivity() {

    /**
     * Registered unconditionally, because `registerForActivityResult` must be called
     * before the activity is STARTED. Gating the registration itself on the SDK level is
     * a common way to get an IllegalStateException on the branch that does need it.
     */
    /** Symbol from a tapped event reminder, consumed once by the composition. */
    private val pendingSymbol = mutableStateOf<String?>(null)
    private val pendingDesk = mutableStateOf(false)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationsIfNeeded()
        val container = (application as NewsForgeApp).container
        handleAlertIntent(intent)
        setContent {
            NewsForgeTheme {
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
                    openSymbol = pendingSymbol.value,
                    onSymbolConsumed = { pendingSymbol.value = null },
                    openDesk = pendingDesk.value,
                    onDeskConsumed = { pendingDesk.value = false },
                )
            }
        }
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
        intent?.getStringExtra(EXTRA_SYMBOL)?.let { symbol ->
            intent.removeExtra(EXTRA_SYMBOL)
            pendingSymbol.value = symbol
        }
        val link = intent?.getStringExtra(EXTRA_STORY_LINK) ?: return
        // Consumed so a configuration change does not reopen the browser.
        intent.removeExtra(EXTRA_STORY_LINK)
        intent.getStringExtra(EXTRA_STORY_CLUSTER)?.let { clusterId ->
            intent.removeExtra(EXTRA_STORY_CLUSTER)
            val repository = (application as NewsForgeApp).container.repository
            lifecycleScope.launch {
                runCatching { repository.markRead(clusterId) }
            }
        }
        Links.open(this, link)
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

    companion object {
        const val EXTRA_STORY_LINK = "story_link"
        const val EXTRA_STORY_CLUSTER = "story_cluster"
        const val EXTRA_SYMBOL = "symbol"

        /** Set by a desk-signal notification: the message is not about an article. */
        const val EXTRA_OPEN_DESK = "open_desk"
    }
}
