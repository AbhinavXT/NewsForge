@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.abhinavxt.newsforge.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.util.RelativeTime

/**
 * One story.
 *
 * Dense by design: on a phone this list is scanned, not read, so the headline gets the
 * weight and everything else is secondary. The left accent bar carries the category at a
 * glance without spending a row on it.
 */
@Composable
fun StoryCard(
    article: ArticleSummary,
    nowMillis: Long,
    watchlist: Set<String>,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
    onSelectSymbol: (String) -> Unit,
    onToggleSymbol: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Box(
                Modifier
                    .padding(top = 3.dp, end = 10.dp)
                    .width(3.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(article.category.accent)
            )

            Column(Modifier.weight(1f)) {
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.bodyLarge,
                    // Read stories fade rather than disappear: you often want to find
                    // something again after opening it.
                    fontWeight = if (article.read) FontWeight.Normal else FontWeight.Medium,
                    color = if (article.read) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = article.category.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = article.category.accent,
                    )
                    Text(
                        text = RelativeTime.byline(
                            article.sourceName,
                            article.otherSources.size,
                            article.publishedAt,
                            nowMillis,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Chalk500,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (article.symbols.isNotEmpty()) {
                    Row(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Tap filters the feed to that company; long-press follows or
                        // unfollows it. Tap gets the reversible, more common action —
                        // long-press for the one that changes stored state is the safer
                        // way round in a list you are scrolling fast.
                        for (symbol in article.symbols.take(4)) {
                            val watched = symbol in watchlist
                            Text(
                                text = symbol,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (watched) FontWeight.Bold else FontWeight.Normal,
                                color = if (watched) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Chalk500
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .combinedClickable(
                                        onClick = { onSelectSymbol(symbol) },
                                        onLongClick = { onToggleSymbol(symbol) },
                                    )
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            // A text glyph rather than an icon dependency; material-icons is a separate
            // artifact and not worth pulling in for one control.
            Text(
                text = if (article.saved) "★" else "☆",
                style = MaterialTheme.typography.titleMedium,
                color = if (article.saved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Chalk500
                },
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onToggleSave)
                    .padding(top = 4.dp, start = 8.dp),
            )
        }
    }
}
