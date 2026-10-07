@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.abhinavxt.newsforge.ui.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.data.WorldSettings
import com.abhinavxt.newsforge.ui.components.Dot
import com.abhinavxt.newsforge.ui.components.IconCircleButton
import com.abhinavxt.newsforge.ui.components.NfChip
import com.abhinavxt.newsforge.ui.feed.accent
import com.abhinavxt.newsforge.ui.theme.CatRegulatory
import com.abhinavxt.newsforge.ui.theme.Chalk500

/** Everything the customise sheet can change. Grouped so the screen takes one parameter. */
data class WorldCustomiseActions(
    val setTopicHidden: (Category, Boolean) -> Unit,
    val moveTopic: (Category, Int) -> Unit,
    val resetTopics: () -> Unit,
    /** @return false when the keyword was rejected. */
    val addKeyword: (String) -> Boolean,
    val removeKeyword: (String) -> Unit,
    val setKeywordAlerts: (Boolean) -> Unit,
    val setDigestEnabled: (Boolean) -> Unit,
    val setDigestHour: (Int) -> Unit,
)

/**
 * Arranging the World tab: what to follow, when the digest comes, which topics show.
 *
 * Following first. It is the setting that changes the tab the most — it adds a section
 * at the very top — and the one people come here for after the first visit; topic order
 * is set once and left.
 */
@Composable
fun WorldCustomiseSheet(
    settings: WorldSettings,
    actions: WorldCustomiseActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Customise World", style = MaterialTheme.typography.titleLarge)

            Label("Following")
            KeywordField(onAdd = actions.addKeyword)
            if (settings.keywords.isEmpty()) {
                Text(
                    text = "Follow a name, place or subject — stories that mention it get " +
                        "their own section at the top.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Chalk500,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (keyword in settings.keywords) {
                        // The whole chip removes it. A separate tiny ✕ is a hard target
                        // and the chip has no other job here.
                        NfChip(
                            label = "$keyword  ✕",
                            selected = true,
                            onClick = { actions.removeKeyword(keyword) },
                        )
                    }
                }
            }
            SwitchRow(
                title = "Alert me about followed stories",
                body = "A notification when a new story mentions something you follow. " +
                    "Respects quiet hours.",
                checked = settings.keywordAlerts,
                onChange = actions.setKeywordAlerts,
            )

            Label("Evening digest")
            SwitchRow(
                title = "Daily world digest",
                body = "The day's top world stories in one notification.",
                checked = settings.digestEnabled,
                onChange = actions.setDigestEnabled,
            )
            if (settings.digestEnabled) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (hour in DIGEST_HOURS) {
                        NfChip(
                            label = hourLabel(hour),
                            selected = settings.digestHour == hour,
                            onClick = { actions.setDigestHour(hour) },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("Topics", Modifier.weight(1f))
                TextButton(onClick = actions.resetTopics, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Reset")
                }
            }
            settings.topics.forEachIndexed { index, topic ->
                TopicSettingRow(
                    topic = topic,
                    visible = topic !in settings.hidden,
                    canMoveUp = index > 0,
                    canMoveDown = index < settings.topics.lastIndex,
                    onVisible = { actions.setTopicHidden(topic, !it) },
                    onMove = { by -> actions.moveTopic(topic, by) },
                )
            }
        }
    }
}

@Composable
private fun KeywordField(onAdd: (String) -> Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    var rejected by rememberSaveable { mutableStateOf(false) }
    val submit = {
        if (text.isNotBlank()) {
            if (onAdd(text)) {
                text = ""
                rejected = false
            } else {
                rejected = true
            }
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; rejected = false },
        placeholder = { Text("e.g. ISRO, Kohli, Bengaluru metro") },
        singleLine = true,
        isError = rejected,
        supportingText = if (rejected) {
            { Text("Already followed, too long, or the limit is reached", color = CatRegulatory) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            TextButton(onClick = { submit() }, enabled = text.isNotBlank()) { Text("Add") }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

@Composable
private fun TopicSettingRow(
    topic: Category,
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onVisible: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(if (visible) topic.accent else Chalk500, 8.dp)
        Text(
            text = topic.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (visible) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        )
        // Buttons rather than drag handles: twenty rows in a sheet that itself scrolls is
        // where dragging goes wrong, and one tap per place is predictable.
        IconCircleButton(
            icon = R.drawable.ic_chevron_right,
            contentDescription = "Move ${topic.label} up",
            onClick = { onMove(-1) },
            enabled = canMoveUp,
            size = 34.dp,
            modifier = Modifier.rotate(-90f),
        )
        IconCircleButton(
            icon = R.drawable.ic_chevron_right,
            contentDescription = "Move ${topic.label} down",
            onClick = { onMove(1) },
            enabled = canMoveDown,
            size = 34.dp,
            modifier = Modifier.rotate(90f),
        )
        Switch(checked = visible, onCheckedChange = onVisible)
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = Chalk500,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Chalk500,
        modifier = modifier.padding(top = 22.dp, bottom = 4.dp),
    )
}

/** The evening, where a digest of the day belongs. */
private val DIGEST_HOURS = listOf(17, 18, 19, 20, 21, 22)

private fun hourLabel(hour: Int): String = when {
    hour == 0 -> "12 am"
    hour < 12 -> "$hour am"
    hour == 12 -> "12 pm"
    else -> "${hour - 12} pm"
}
