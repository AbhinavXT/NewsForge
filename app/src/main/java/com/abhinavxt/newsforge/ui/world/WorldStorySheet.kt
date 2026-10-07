@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abhinavxt.newsforge.ui.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.ui.components.Pill
import com.abhinavxt.newsforge.ui.components.PrimaryButton
import com.abhinavxt.newsforge.ui.components.TonalButton
import com.abhinavxt.newsforge.ui.feed.AcrossOutlets
import com.abhinavxt.newsforge.ui.feed.Outlets
import com.abhinavxt.newsforge.ui.feed.StoryPresentation
import com.abhinavxt.newsforge.ui.feed.accent
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.util.RelativeTime

/**
 * A world story before opening it: the whole headline, what the outlets agree on, and
 * who carried it.
 *
 * The market feed's sheet without the market: no tickers, tiers, price reaction or
 * ranking arithmetic, none of which a world story has. What is left is what decides
 * whether to read it — and, with several outlets, often answers it without reading.
 */
@Composable
fun WorldStorySheet(
    article: ArticleSummary,
    nowMillis: Long,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    /** Reads the story aloud; null hides the button where listening is unavailable. */
    onListen: (() -> Unit)? = null,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill(article.category.label, article.category.accent, dot = true)
                Text(
                    text = RelativeTime.format(article.publishedAt, nowMillis),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = Chalk500,
                    maxLines = 1,
                )
            }
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp),
                modifier = Modifier.padding(top = 14.dp),
            )
            StoryPresentation.summaryOrNull(article)?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            AcrossOutlets(article.clusterId, article.title)
            Outlets(article)

            Row(
                Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryButton(
                    text = "Open",
                    icon = R.drawable.ic_open,
                    onClick = onOpen,
                    modifier = Modifier.weight(1.2f),
                )
                TonalButton(
                    text = if (article.saved) "Saved" else "Save",
                    icon = if (article.saved) R.drawable.ic_star else R.drawable.ic_star_border,
                    onClick = onToggleSave,
                    contentColor = if (article.saved) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                TonalButton(
                    text = "Share",
                    icon = R.drawable.ic_share,
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                )
            }
            if (onListen != null) {
                TonalButton(
                    text = "Listen",
                    icon = R.drawable.ic_listen,
                    onClick = onListen,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}
