@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.abhinavxt.newsforge.ui.health

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.feed.FeedKind
import com.abhinavxt.newsforge.core.mute.AlertSensitivity
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.core.feed.FeedValidation
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.data.db.FeedEntity
import com.abhinavxt.newsforge.ui.theme.CatOrder
import com.abhinavxt.newsforge.ui.theme.CatRegulatory
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.util.RelativeTime

@Composable
fun FeedHealthScreen(
    state: FeedManagerUiState,
    nowMillis: Long,
    alertsEnabled: Boolean,
    onToggleAlerts: (Boolean) -> Unit,
    watchEnabled: Boolean,
    onToggleWatch: (Boolean) -> Unit,
    sensitivity: AlertSensitivity,
    onSelectSensitivity: (AlertSensitivity) -> Unit,
    mutes: List<MuteRule>,
    onRemoveMute: (MuteRule) -> Unit,
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
            TopAppBar(
                title = { Text("Feeds", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    TextButton(onClick = { adding = true; onClearTest() }) { Text("Add") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                AlertToggle(alertsEnabled, onToggleAlerts)
                if (alertsEnabled) {
                    SensitivityPicker(sensitivity, onSelectSensitivity)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingToggle(
                    title = "Live watch",
                    subtitle = "Poll every minute from 09:00 to 15:45 on weekdays. " +
                        "Shows a permanent notification while it runs.",
                    enabled = watchEnabled,
                    onToggle = onToggleWatch,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (mutes.isNotEmpty()) {
                item {
                    Text(
                        text = "MUTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                items(mutes, key = { it.key }) { rule ->
                    MuteRow(rule) { onRemoveMute(rule) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    Text(
                        text = "FEEDS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
            }
            items(state.feeds, key = { it.feed.id }) { item ->
                FeedRow(
                    item = item,
                    nowMillis = nowMillis,
                    onToggle = { onToggleFeed(item.feed.id, it) },
                    onEdit = { editing = item.feed; onClearTest() },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
private fun AlertToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) = SettingToggle(
    title = "Alerts",
    subtitle = "Watchlist and high-impact stories only, silent 22:00-06:30",
    enabled = enabled,
    onToggle = onToggle,
)

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun SensitivityPicker(
    selected: AlertSensitivity,
    onSelect: (AlertSensitivity) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (level in AlertSensitivity.entries) {
                FilterChip(
                    selected = selected == level,
                    onClick = { onSelect(level) },
                    label = { Text(level.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        // The description carries the actual meaning; the label alone is just a mood.
        Text(
            text = selected.description,
            style = MaterialTheme.typography.labelSmall,
            color = Chalk500,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun MuteRow(rule: MuteRule, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rule.value, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = rule.kind.label,
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        TextButton(onClick = onRemove) { Text("Unmute") }
    }
}

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

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.feed.name, style = MaterialTheme.typography.bodyMedium)
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
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (option in FeedKind.entries) {
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = {
                                Text(option.label, style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (option in SourceTier.entries) {
                        FilterChip(
                            selected = tier == option.name,
                            onClick = { tier = option.name },
                            label = {
                                Text(option.label, style = MaterialTheme.typography.labelSmall)
                            },
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
