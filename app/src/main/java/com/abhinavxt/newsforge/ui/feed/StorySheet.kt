@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.abhinavxt.newsforge.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.notify.WatchTier
import com.abhinavxt.newsforge.core.mute.MuteKind
import com.abhinavxt.newsforge.core.mute.MuteRule
import com.abhinavxt.newsforge.core.tag.Sector
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.util.RelativeTime

/**
 * Detail for one story, without leaving the app.
 *
 * The reason this exists: triaging forty headlines by opening each in a browser is slow,
 * and most of them you only need the first two lines of to decide. The sheet answers "is
 * this worth reading" in one tap; Open is there for when the answer is yes.
 */
@Composable
fun StorySheet(
    article: ArticleSummary,
    nowMillis: Long,
    tiers: Map<String, WatchTier>,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onToggleSave: () -> Unit,
    onSelectSymbol: (String) -> Unit,
    onSetTier: (String, WatchTier?) -> Unit,
    onSelectSector: (Sector) -> Unit,
    onMute: (MuteRule) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(article.category.accent)
                )
                Text(
                    text = article.category.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = article.category.accent,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Text(
                text = article.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 10.dp),
            )

            StoryPresentation.summaryOrNull(article)?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Text(
                text = RelativeTime.format(article.publishedAt, nowMillis),
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
                modifier = Modifier.padding(top = 12.dp),
            )

            val outlets = StoryPresentation.outlets(article)
            Text(
                text = if (outlets.size > 1) {
                    "Carried by ${outlets.size} outlets: ${outlets.joinToString(", ")}"
                } else {
                    outlets.firstOrNull().orEmpty()
                },
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
                modifier = Modifier.padding(top = 2.dp),
            )

            // Exposure is set explicitly here rather than by cycling a long-press.
            // It changes how loudly the app interrupts you, so guessing at it from a
            // gesture is the wrong trade — three visible states, one tap each.
            for (symbol in article.symbols) {
                val current = tiers[symbol]
                Column(Modifier.padding(top = 12.dp)) {
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (current != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (tier in WatchTier.entries) {
                            FilterChip(
                                selected = current == tier,
                                // Tapping the active tier clears it, so removing a
                                // symbol never needs a separate control.
                                onClick = { onSetTier(symbol, if (current == tier) null else tier) },
                                label = {
                                    Text(tier.label, style = MaterialTheme.typography.labelSmall)
                                },
                            )
                        }
                    }
                }
            }

            // Sectors, when the story touches a basket rather than one name. Often the
            // only tags a policy story has, since it may name no company at all.
            val sectors = article.sectors.mapNotNull { Sector.parse(it) }
            if (sectors.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (sector in sectors) {
                        FilterChip(
                            selected = false,
                            onClick = { onSelectSector(sector) },
                            label = {
                                Text(sector.label, style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onOpen) { Text("Open") }
                TextButton(onClick = onToggleSave) {
                    Text(if (article.saved) "Unsave" else "Save")
                }
                TextButton(onClick = onShare) { Text("Share") }
                // Offered next to the byline it silences, so the thing being muted is
                // on screen when the decision is made.
                TextButton(
                    onClick = { onMute(MuteRule(MuteKind.SOURCE, article.sourceName)) },
                ) { Text("Mute ${article.sourceName}") }
            }
        }
    }
}
