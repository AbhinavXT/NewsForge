@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.abhinavxt.newsforge.ui.health

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.ui.components.Dot
import com.abhinavxt.newsforge.ui.components.IconCircleButton
import com.abhinavxt.newsforge.ui.components.NfCard
import com.abhinavxt.newsforge.ui.components.NfChip
import com.abhinavxt.newsforge.ui.components.ScreenGutter
import com.abhinavxt.newsforge.ui.components.ScreenHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.feed.FeedKind
import com.abhinavxt.newsforge.core.feed.FeedValidation
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.data.db.FeedEntity
import com.abhinavxt.newsforge.ui.theme.CatOrder
import com.abhinavxt.newsforge.ui.theme.CatRegulatory
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.util.RelativeTime

/**
 * The sources, and nothing else.
 *
 * Everything that configures the app rather than a feed — alerts, the watch, the desk
 * bridge, backups — lives on the Settings tab. This screen used to carry all of it above
 * the list, which buried the thirty rows it is named after under a screen and a half of
 * switches.
 */
@Composable
fun FeedHealthScreen(
    state: FeedManagerUiState,
    nowMillis: Long,
    onToggleFeed: (String, Boolean) -> Unit,
    onSaveFeed: (FeedEntity) -> Unit,
    onDeleteFeed: (String) -> Unit,
    onResetFeed: (String) -> Unit,
    onTestFeed: (String, FeedKind) -> Unit,
    onClearTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<FeedEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val failing = state.feeds.count { it.feed.enabled && it.isFailing }
            ScreenHeader(
                title = "Feeds",
                subtitle = buildString {
                    append("${state.feeds.count { it.feed.enabled }} of ${state.feeds.size} on")
                    if (failing > 0) append(" · $failing failing")
                },
                actions = {
                    IconCircleButton(
                        icon = R.drawable.ic_add,
                        contentDescription = "Add feed",
                        onClick = { adding = true; onClearTest() },
                        tint = MaterialTheme.colorScheme.onPrimary,
                        container = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        },
    ) { padding ->
        // Failing first: a broken feed is the reason to open this screen, and it should not
        // need finding. Stable within each group, so the shipped order otherwise holds.
        val ordered = state.feeds.sortedByDescending { it.feed.enabled && it.isFailing }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            items(ordered, key = { it.feed.id }) { item ->
                FeedRow(
                    item = item,
                    nowMillis = nowMillis,
                    onToggle = { onToggleFeed(item.feed.id, it) },
                    onEdit = { editing = item.feed; onClearTest() },
                )
            }
        }
    }

    val target = editing ?: if (adding) BLANK_FEED else null
    if (target != null) {
        FeedEditor(
            feed = target,
            isNew = adding,
            testResult = state.testResult,
            existingUrls = state.feeds.map { it.feed.url }.toSet() - target.url,
            onTest = onTestFeed,
            onSave = {
                onSaveFeed(it)
                editing = null
                adding = false
                onClearTest()
            },
            onDelete = {
                onDeleteFeed(target.id)
                editing = null
                onClearTest()
            },
            onReset = {
                onResetFeed(target.id)
                editing = null
                onClearTest()
            },
            onDismiss = { editing = null; adding = false; onClearTest() },
        )
    }
}

/** Position 1000 so user-added feeds sort after the seeded set. */
private val BLANK_FEED = FeedEntity(
    id = "",
    name = "",
    url = "",
    tier = SourceTier.WIRE.name,
    kind = FeedKind.RSS.name,
    categoryHint = null,
    enabled = true,
    builtIn = false,
    position = 1_000,
)





@Composable
private fun FeedRow(
    item: FeedHealth,
    nowMillis: Long,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val status = when {
        item.isFailing -> "failing"
        item.hasEverSucceeded -> "ok"
        else -> "never fetched"
    }
    val statusColor = when {
        item.isFailing -> CatRegulatory
        item.hasEverSucceeded -> CatOrder
        else -> Chalk500
    }

    NfCard(
        modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 4.dp),
        onClick = onEdit,
        contentPadding = PaddingValues(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The status as a light first, so a failing feed is findable in a list of thirty
            // without reading thirty status lines.
            Dot(if (item.feed.enabled) statusColor else Chalk500, 8.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(
                    text = item.feed.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (item.feed.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = buildString {
                        append(status)
                        append(" · ")
                        append(item.feed.tier.lowercase())
                        if (item.feed.kind != FeedKind.RSS.name) append(" · json")
                        item.state?.lastSuccessAt?.let {
                            append(" · last ok ")
                            append(RelativeTime.format(it, nowMillis))
                        }
                        item.state?.lastItemCount?.takeIf { it > 0 }?.let { append(" · $it items") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier.padding(top = 2.dp),
                )
                item.state?.lastError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = Chalk500,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 2,
                    )
                }
            }
            Switch(checked = item.feed.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun FeedEditor(
    feed: FeedEntity,
    isNew: Boolean,
    testResult: String?,
    existingUrls: Set<String>,
    onTest: (String, FeedKind) -> Unit,
    onSave: (FeedEntity) -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(feed.id) { mutableStateOf(feed.name) }
    var url by remember(feed.id) { mutableStateOf(feed.url) }
    var tier by remember(feed.id) { mutableStateOf(feed.tier) }
    var kind by remember(feed.id) {
        mutableStateOf(FeedKind.entries.firstOrNull { it.name == feed.kind } ?: FeedKind.RSS)
    }

    val problem = FeedValidation.validate(name, url, existingUrls)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add feed" else "Edit feed") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Feed URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                // Parser selection, not cosmetic: an NSE endpoint returns JSON and
                // needs a primed session, so getting this wrong reads as a dead feed.
                FlowRow(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (option in FeedKind.entries) {
                        NfChip(
                            label = option.label,
                            selected = kind == option,
                            onClick = { kind = option },
                        )
                    }
                }
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (option in SourceTier.entries) {
                        NfChip(
                            label = option.label,
                            selected = tier == option.name,
                            onClick = { tier = option.name },
                        )
                    }
                }
                // Validation only appears once something has been typed, so a new feed
                // does not open already scolding the user for an empty field.
                val message = testResult
                    ?: problem?.message?.takeIf { name.isNotBlank() || url.isNotBlank() }
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (testResult?.startsWith("OK") == true) CatOrder else CatRegulatory,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = {
                    val normalized = FeedValidation.normalizeUrl(url)
                    onSave(
                        feed.copy(
                            // A new feed gets its id from the URL; an existing one keeps
                            // its own, so editing a URL never orphans its polling state.
                            id = feed.id.ifEmpty { FeedValidation.idFor(normalized) },
                            name = name.trim(),
                            url = normalized,
                            tier = tier,
                            kind = kind.name,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = url.isNotBlank(), onClick = { onTest(url, kind) }) { Text("Test") }
                if (!isNew) {
                    // A built-in cannot be deleted but can always be put back the way it
                    // shipped, so a mistyped URL never needs a reinstall to recover.
                    if (feed.builtIn) {
                        TextButton(onClick = onReset) { Text("Reset") }
                    } else {
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}


