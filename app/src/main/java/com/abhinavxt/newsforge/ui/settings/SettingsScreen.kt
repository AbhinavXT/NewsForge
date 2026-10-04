@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.abhinavxt.newsforge.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import com.abhinavxt.newsforge.ui.theme.AppTheme
import com.abhinavxt.newsforge.ui.theme.Palette
import com.abhinavxt.newsforge.ui.components.SegmentedControl
import com.abhinavxt.newsforge.ui.theme.ThemeMode
import com.abhinavxt.newsforge.ui.theme.ThemeState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.backup.FeedBackup
import com.abhinavxt.newsforge.core.mute.AlertSensitivity
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.ui.components.GroupCard
import com.abhinavxt.newsforge.ui.components.GroupDivider
import com.abhinavxt.newsforge.ui.components.NfChip
import com.abhinavxt.newsforge.ui.components.Pill
import com.abhinavxt.newsforge.ui.components.PrimaryButton
import com.abhinavxt.newsforge.ui.components.ScreenGutter
import com.abhinavxt.newsforge.ui.components.ScreenHeader
import com.abhinavxt.newsforge.ui.components.SectionTitle
import com.abhinavxt.newsforge.ui.components.TonalButton
import com.abhinavxt.newsforge.ui.desk.DeskUiState
import com.abhinavxt.newsforge.ui.health.FeedManagerUiState
import com.abhinavxt.newsforge.ui.theme.CatOrder
import com.abhinavxt.newsforge.ui.theme.CatRegulatory
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.Hairline
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import com.abhinavxt.newsforge.ui.util.BackgroundStart
import com.abhinavxt.newsforge.ui.util.RelativeTime

/**
 * Everything that configures the app, in one place.
 *
 * Before this, configuration was split by accident of history: alerts and backups sat on
 * top of the feed list, and the desk bridge was a dialog behind a button on the Desk tab
 * that only said "Setup". Finding where a thing was set meant remembering which screen
 * happened to grow it first.
 *
 * Grouped by what the reader is trying to do — be told about things, connect the desk,
 * look after their data — rather than by which store each value lives in.
 */
@Composable
fun SettingsScreen(
    theme: AppTheme,
    onSelectTheme: (AppTheme) -> Unit,
    mode: ThemeMode,
    onSelectMode: (ThemeMode) -> Unit,
    nowMillis: Long,
    alertsEnabled: Boolean,
    onToggleAlerts: (Boolean) -> Unit,
    sensitivity: AlertSensitivity,
    onSelectSensitivity: (AlertSensitivity) -> Unit,
    quietHours: QuietHours,
    onSetQuietHours: (QuietHours) -> Unit,
    watchEnabled: Boolean,
    /** When the system last refused a watch segment, or 0. */
    watchLastRefusedAt: Long,
    onToggleWatch: (Boolean) -> Unit,
    desk: DeskUiState,
    onSaveDesk: (server: String, topic: String, token: String, commandTopic: String) -> Unit,
    onTestDesk: (server: String, topic: String, token: String) -> Unit,
    onClearDeskTest: () -> Unit,
    onClearRecentCommands: () -> Unit,
    feeds: FeedManagerUiState,
    onManageFeeds: () -> Unit,
    onRemoveMute: (MuteRule) -> Unit,
    onRefreshCompanyList: () -> Unit,
    onExportTo: (Uri) -> Unit,
    onImportFrom: (Uri) -> Unit,
    onClearBackupMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Re-read on every resume: the exemption is granted in system settings, and the row
    // asking for it should be gone the moment the person comes back.
    val context = LocalContext.current
    var backgroundExempt by remember { mutableStateOf(BackgroundStart.isExempt(context)) }
    LifecycleResumeEffect(Unit) {
        backgroundExempt = BackgroundStart.isExempt(context)
        onPauseOrDispose { }
    }

    // The document picker rather than a path of our own: no storage permission to ask
    // for, the file lands wherever the person actually keeps things — Drive, Files, an
    // SD card — and restoring works across a reinstall, which writing into app storage
    // would not.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(FeedBackup.MIME_TYPE)
    ) { uri -> uri?.let(onExportTo) }
    val importLauncher = rememberLauncherForActivityResult(
        // Widened past our own MIME type on purpose: providers hand back
        // `application/octet-stream` for a .json often enough that filtering strictly
        // greys out the very file the person is trying to pick.
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportFrom) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenHeader(title = "Settings", subtitle = "Appearance, alerts, desk bridge, data")
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "appearance") {
                SectionTitle("Appearance")
                GroupCard(Modifier.padding(horizontal = ScreenGutter)) {
                    ThemePicker(theme, onSelectTheme, mode, onSelectMode)
                }
            }

            item(key = "notifications") {
                SectionTitle("Notifications")
                GroupCard(Modifier.padding(horizontal = ScreenGutter)) {
                    AlertToggle(alertsEnabled, quietHours, onToggleAlerts)
                    if (alertsEnabled) {
                        SensitivityPicker(sensitivity, onSelectSensitivity)
                        GroupDivider()
                        QuietHoursSetting(quietHours, onSetQuietHours)
                    }
                    GroupDivider()
                    SettingToggle(
                        title = "Live watch",
                        subtitle = "Poll every minute from 09:00 to 12:00 and 13:30 to 15:45 " +
                            "on weekdays. " +
                            "Shows a permanent notification while it runs.",
                        enabled = watchEnabled,
                        onToggle = onToggleWatch,
                    )
                    // Alerts need it too, not only the watch: an app that is rarely opened
                    // drops into a standby bucket where its fifteen-minute sync can be held
                    // back for hours, and a watchlist alert that arrives at lunch is late.
                    if ((watchEnabled || alertsEnabled) && !backgroundExempt) {
                        GroupDivider()
                        SettingAction(
                            title = "Allow background start",
                            subtitle = if (watchEnabled) {
                                "Android will not start the watch while the app is " +
                                    "closed unless battery optimisation is off for NewsForge."
                            } else {
                                "Android can hold back background checks for hours when the " +
                                    "app is rarely opened. Turn battery optimisation off for " +
                                    "NewsForge so alerts arrive on time."
                            },
                            action = "Allow",
                            message = watchLastRefusedAt.takeIf { it > 0L }?.let {
                                "Last start was blocked (${RelativeTime.format(it, nowMillis)}) — " +
                                    "allow it to run while the app is closed."
                            },
                            onClick = { BackgroundStart.request(context) },
                        )
                    }
                    GroupDivider()
                    // Channels, sound and vibration belong to the system, and a copy of those
                    // controls here would drift from the real ones. A door to them does not.
                    SettingAction(
                        title = "System notification settings",
                        subtitle = "Sound, vibration and per-channel importance.",
                        action = "Open",
                        message = null,
                        onClick = { openNotificationSettings(context) },
                    )
                }
            }

            item(key = "desk") {
                SectionTitle(
                    title = "Desk bridge",
                    trailing = {
                        Pill(
                            text = if (desk.configured) "Connected" else "Not connected",
                            color = if (desk.configured) QuoteUp else Chalk500,
                            dot = true,
                        )
                    },
                )
                DeskBridgeCard(
                    desk = desk,
                    onSave = onSaveDesk,
                    onTest = onTestDesk,
                    onClearTest = onClearDeskTest,
                    onClearRecentCommands = onClearRecentCommands,
                )
            }

            item(key = "data") {
                SectionTitle("Sources & data")
                GroupCard(Modifier.padding(horizontal = ScreenGutter)) {
                    val enabled = feeds.feeds.count { it.feed.enabled }
                    val failing = feeds.feeds.count { it.feed.enabled && it.isFailing }
                    SettingAction(
                        title = "Feeds",
                        subtitle = buildString {
                            append("$enabled of ${feeds.feeds.size} sources on")
                            if (failing > 0) append(" · $failing failing")
                        },
                        action = "Manage",
                        message = null,
                        onClick = onManageFeeds,
                    )
                    GroupDivider()
                    SettingAction(
                        title = "Company list",
                        subtitle = "Tagging only finds companies it knows about. Pulls the " +
                            "current NSE list — refreshed weekly on its own.",
                        action = "Refresh",
                        message = feeds.lexiconMessage,
                        onClick = onRefreshCompanyList,
                    )
                    GroupDivider()
                    BackupRow(
                        message = feeds.backupMessage,
                        onExport = {
                            onClearBackupMessage()
                            exportLauncher.launch(FeedBackup.fileName(nowMillis))
                        },
                        onImport = {
                            onClearBackupMessage()
                            importLauncher.launch(arrayOf(FeedBackup.MIME_TYPE, "text/*", "*/*"))
                        },
                    )
                }
            }

            item(key = "mutes") {
                SectionTitle("Muted", count = feeds.mutes.size)
                GroupCard(Modifier.padding(horizontal = ScreenGutter)) {
                    if (feeds.mutes.isEmpty()) {
                        // Said rather than hidden, so the reader learns where mutes go and
                        // how one is made before they need to undo one.
                        Text(
                            text = "Nothing muted. Mute a source from a story's detail sheet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Chalk500,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    feeds.mutes.forEachIndexed { index, rule ->
                        if (index > 0) GroupDivider()
                        MuteRow(rule) { onRemoveMute(rule) }
                    }
                }
            }

            item(key = "about") {
                SectionTitle("About")
                GroupCard(Modifier.padding(horizontal = ScreenGutter)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("NewsForge", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Market news for NSE and BSE, ranked for the session.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Chalk500,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            text = "v" + appVersion(context),
                            style = MaterialTheme.typography.labelMedium,
                            color = Chalk500,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One tile per theme, each drawn in its own colours rather than the current ones, so the
 * row is a preview of every option at once and nobody has to tap through them to see.
 */
@Composable
private fun ThemePicker(
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
    mode: ThemeMode,
    onSelectMode: (ThemeMode) -> Unit,
) {
    // The swatches preview whichever brightness is in force, so picking a theme while in
    // light mode shows the light version of each.
    val light = ThemeState.isLight
    Column(Modifier.padding(16.dp)) {
        SegmentedControl(
            options = ThemeMode.entries.map { it.label },
            selectedIndex = mode.ordinal,
            onSelect = { onSelectMode(ThemeMode.entries[it]) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (theme in AppTheme.entries) {
                ThemeSwatch(
                    theme = theme,
                    palette = theme.palette(light),
                    selected = theme == selected,
                    onClick = { onSelect(theme) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            // Said, because otherwise the reader reasonably expects a green theme to make
            // the green categories greener, and wonders why it did not.
            text = "Category and price colours mean the same in every theme. " +
                "They are a shade deeper in light mode so they stay readable.",
            style = MaterialTheme.typography.labelSmall,
            color = Chalk500,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun ThemeSwatch(
    theme: AppTheme,
    palette: Palette,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .clip(shape)
                .background(palette.ink900)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) palette.accent else palette.hairline,
                    shape = shape,
                )
                .padding(7.dp),
        ) {
            // A miniature card: the accent bar a lead story gets, then two lines of text.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.ink800)
                    .padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.linearGradient(listOf(palette.accent, palette.accentEnd)))
                )
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(palette.chalk300))
                Box(Modifier.fillMaxWidth(0.6f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(palette.chalk500))
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(palette.accent),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = palette.onAccent,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        Text(
            text = theme.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
    }
}

/**
 * The bridge, as a form rather than a dialog.
 *
 * Fields hold their own text and start from what is saved. They are keyed on the saved
 * values, so a save — or the real values arriving after the first empty frame — resets
 * them to match, and Save greys out again because there is nothing left to save.
 */
@Composable
private fun DeskBridgeCard(
    desk: DeskUiState,
    onSave: (String, String, String, String) -> Unit,
    onTest: (String, String, String) -> Unit,
    onClearTest: () -> Unit,
    onClearRecentCommands: () -> Unit,
) {
    var server by rememberSaveable(desk.server) { mutableStateOf(desk.server) }
    var topic by rememberSaveable(desk.topic) { mutableStateOf(desk.topic) }
    var commandTopic by rememberSaveable(desk.commandTopic) { mutableStateOf(desk.commandTopic) }
    var token by rememberSaveable(desk.token) { mutableStateOf(desk.token) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    val dirty = server.trim() != desk.server || topic.trim() != desk.topic ||
        commandTopic.trim() != desk.commandTopic || token.trim() != desk.token

    GroupCard(Modifier.padding(horizontal = ScreenGutter)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Connect your ntfy topic to see alerts from your own machine on the " +
                    "Desk tab, and to send it commands. Keep the ntfy app installed for " +
                    "instant push.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsField(
                value = server,
                onValueChange = { server = it; onClearTest() },
                label = "Server",
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.padding(top = 12.dp),
            )
            SettingsField(
                value = topic,
                onValueChange = { topic = it; onClearTest() },
                label = "Topic",
                monospace = true,
            )
            SettingsField(
                value = commandTopic,
                onValueChange = { commandTopic = it },
                label = "Command topic (optional)",
                supporting = "Leave blank if the desk listens on the same topic it replies on.",
                monospace = true,
            )
            SettingsField(
                value = token,
                onValueChange = { token = it; onClearTest() },
                label = "Access token (optional)",
                monospace = true,
                visualTransformation = if (showToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailing = if (token.isNotEmpty()) {
                    {
                        TextButton(onClick = { showToken = !showToken }) {
                            Text(if (showToken) "Hide" else "Show")
                        }
                    }
                } else {
                    null
                },
            )

            desk.testResult?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        it.startsWith("OK") || it.startsWith("Connected") -> CatOrder
                        it.startsWith("Testing") -> Chalk500
                        else -> CatRegulatory
                    },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TonalButton(
                    text = "Test",
                    onClick = { onTest(server, topic, token) },
                    enabled = topic.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = if (!dirty && desk.configured) "Saved" else "Save",
                    onClick = {
                        onSave(server, topic, token, commandTopic)
                        onClearTest()
                    },
                    enabled = topic.isNotBlank() && dirty,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (desk.recentCommands.isNotEmpty()) {
            GroupDivider()
            SettingAction(
                title = "Recent commands",
                subtitle = "${desk.recentCommands.size} kept as shortcuts above the Desk composer.",
                action = "Clear",
                message = null,
                onClick = onClearRecentCommands,
            )
        }

        if (desk.configured) {
            GroupDivider()
            SettingAction(
                title = "Disconnect",
                subtitle = "Forget the topic and token. Messages already received stay.",
                action = "Disconnect",
                message = null,
                onClick = { confirmDisconnect = true },
                destructive = true,
            )
        }
    }

    if (confirmDisconnect) {
        // Confirmed, because a topic is usually a random string nobody has memorised, and
        // getting it back means a trip to the machine that made it.
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect the desk?") },
            text = { Text("The topic and token will be cleared from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    onSave(desk.server, "", "", "")
                    confirmDisconnect = false
                }) { Text("Disconnect", color = CatRegulatory) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    monospace: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyMedium.let {
            if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it
        },
        supportingText = supporting?.let {
            { Text(it, style = MaterialTheme.typography.labelSmall) }
        },
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.background,
            unfocusedContainerColor = MaterialTheme.colorScheme.background,
            unfocusedBorderColor = Hairline,
        ),
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun appVersion(context: Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

@Composable
private fun AlertToggle(enabled: Boolean, quiet: QuietHours, onToggle: (Boolean) -> Unit) = SettingToggle(
    title = "Alerts",
    subtitle = "Watchlist and high-impact stories only" +
        if (quiet.enabled) ", silent ${quiet.from.format(HH_MM)}–${quiet.until.format(HH_MM)}" else "",
    enabled = enabled,
    onToggle = onToggle,
)

/** Quiet hours, as the settings screen edits them. */
data class QuietHours(
    val enabled: Boolean,
    val from: LocalTime,
    val until: LocalTime,
    /** A morning summary of what quiet hours held back. */
    val digest: Boolean,
)

/**
 * When alerts hold off, and whether the morning brings a summary of what was held.
 *
 * Times open the system's clock picker rather than a text field: a time typed as "6.30"
 * or "18:30" for half six in the morning is exactly the mistake that silences a day.
 */
@Composable
private fun QuietHoursSetting(quiet: QuietHours, onChange: (QuietHours) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    SettingToggle(
        title = "Quiet hours",
        subtitle = "No alerts overnight. Regulatory news and holdings wait too.",
        enabled = quiet.enabled,
        onToggle = { onChange(quiet.copy(enabled = it)) },
    )
    if (quiet.enabled) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NfChip(
                label = "From ${quiet.from.format(HH_MM)}",
                selected = false,
                onClick = { editing = "from" },
            )
            NfChip(
                label = "Until ${quiet.until.format(HH_MM)}",
                selected = false,
                onClick = { editing = "until" },
            )
        }
        GroupDivider()
        SettingToggle(
            title = "Morning digest",
            subtitle = "One notification when quiet hours end, with the stories they held back.",
            enabled = quiet.digest,
            onToggle = { onChange(quiet.copy(digest = it)) },
        )
    }
    editing?.let { which ->
        TimePickerDialog(
            initial = if (which == "from") quiet.from else quiet.until,
            title = if (which == "from") "Quiet from" else "Quiet until",
            onPick = { time ->
                editing = null
                onChange(if (which == "from") quiet.copy(from = time) else quiet.copy(until = time))
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun TimePickerDialog(
    initial: LocalTime,
    title: String,
    onPick: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
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
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
        // Chips rather than a segmented control: the labels are phrases, and equal-width
        // segments clip the longest one on a narrow phone.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (level in AlertSensitivity.entries) {
                NfChip(
                    label = level.label,
                    selected = selected == level,
                    onClick = { onSelect(level) },
                )
            }
        }
        // The description carries the actual meaning; the label alone is just a mood.
        Text(
            text = selected.description,
            style = MaterialTheme.typography.labelSmall,
            color = Chalk500,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun MuteRow(rule: MuteRule, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mute),
            contentDescription = null,
            tint = Chalk500,
            modifier = Modifier.size(18.dp),
        )
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

/**
 * Backing the feed list up, and putting it back.
 *
 * Here rather than in a settings screen because this is where the thing being backed up
 * lives, and because the moment you want it is the moment you have just finished adding
 * a feed you spent twenty minutes finding.
 *
 * Restoring merges and never deletes, which the subtitle says outright — an import
 * control that might silently wipe the list is one nobody presses.
 */
@Composable
private fun BackupRow(
    message: String?,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = "Backup",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Save your feed list to a file, or merge one back in. " +
                "Restoring adds and updates; it never deletes.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TonalButton("Back up", onExport, Modifier.weight(1f))
            TonalButton("Restore", onImport, Modifier.weight(1f))
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * A setting that does something once rather than holding a state.
 *
 * Separate from the toggles above it because the feedback matters: a toggle's effect is
 * visible in the toggle, and pressing this produces nothing you can see anywhere in the
 * app — the whole result is that future stories tag better. Without a count reported back,
 * there would be no way to tell a working refresh from one silently failing.
 */
@Composable
private fun SettingAction(
    title: String,
    subtitle: String,
    action: String,
    message: String?,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = message ?: subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (message != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        TextButton(onClick = onClick) {
            Text(action, color = if (destructive) CatRegulatory else MaterialTheme.colorScheme.primary)
        }
    }
}
