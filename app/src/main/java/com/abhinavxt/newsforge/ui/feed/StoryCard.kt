@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.abhinavxt.newsforge.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.quote.Reaction
import com.abhinavxt.newsforge.core.quote.VolumeLevel
import com.abhinavxt.newsforge.core.quote.VolumeProfile
import com.abhinavxt.newsforge.core.quote.ReactionBasis
import com.abhinavxt.newsforge.data.model.ArticleSummary
import com.abhinavxt.newsforge.ui.theme.AccentDim
import com.abhinavxt.newsforge.ui.theme.CatRegulatory
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import com.abhinavxt.newsforge.ui.util.RelativeTime

/**
 * One story.
 *
 * Dense by design: on a phone this list is scanned, not read, so the headline gets the
 * weight and everything else is secondary.
 *
 * The left rail means one thing — this story touches something you follow. It used to be
 * the category accent on every card, which made it wallpaper: a signal every row carries
 * is not a signal. The category moved to the section header above, which is where it
 * belongs once the list is grouped, since nine results in a row do not need nine
 * identical labels.
 *
 * @param showCategory when the list is not grouped — search results, a symbol timeline —
 *   nothing above the card says what kind of story this is, so the card says it itself.
 * @param onToggleSave null hides the star. The feed passes null because swiping covers it
 *   and a control on every row for something you do to one story in thirty is noise;
 *   sparser screens pass a handler and keep it.
 * @param prices current prices and what they were earlier. The day's move goes on the
 *   ticker; the move *since this was published* goes next to the timestamp, because that
 *   is the number that says whether the market has reacted yet or whether you are early.
 */
@Composable
fun StoryCard(
    article: ArticleSummary,
    nowMillis: Long,
    watchlist: Set<String>,
    onOpen: () -> Unit,
    onSelectSymbol: (String) -> Unit,
    onToggleSymbol: (String) -> Unit,
    modifier: Modifier = Modifier,
    lead: Boolean = false,
    showCategory: Boolean = false,
    prices: PriceBook = PriceBook(),
    onToggleSave: (() -> Unit)? = null,
) {
    val followed = article.symbols.any { it in watchlist }

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .padding(horizontal = 18.dp, vertical = 13.dp),
        ) {
            if (followed) {
                // Sized to the card rather than to a fixed height: a rail that stops a
                // third of the way down a three-line headline reads as a rendering bug.
                Box(
                    Modifier
                        .padding(end = 10.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = article.title,
                    style = if (lead) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    // Read stories fade rather than disappear: you often want to find
                    // something again after opening it.
                    fontWeight = when {
                        lead -> FontWeight.SemiBold
                        article.read -> FontWeight.Normal
                        else -> FontWeight.Medium
                    },
                    color = if (article.read) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = if (lead) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (showCategory) {
                        Text(
                            text = article.category.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = article.category.accent,
                        )
                    }
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
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Next to the timestamp, because it is measured from it. The day's
                    // move stays on the ticker below: they answer different questions and
                    // putting them side by side would invite reading one as the other.
                    prices.reactionFor(article)?.let { reaction ->
                        ReactionLabel(reaction)
                    }
                }

                if (article.symbols.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Tap filters the feed to that company; long-press follows or
                        // unfollows it. Tap gets the reversible, more common action —
                        // long-press for the one that changes stored state is the safer
                        // way round in a list you are scrolling fast.
                        for (symbol in article.symbols.take(4)) {
                            TickerChip(
                                symbol = symbol,
                                watched = symbol in watchlist,
                                changePercent = prices[symbol]?.changePercent,
                                onClick = { onSelectSymbol(symbol) },
                                onLongClick = { onToggleSymbol(symbol) },
                            )
                        }
                        // Beside the tickers, because it is a fact about them. A name
                        // already trading at three times normal when the story lands
                        // means either the news is out ahead of you or something else is
                        // going on that the headline does not mention.
                        VolumeBadge(prices.volumeFor(article))
                    }
                }
            }

            if (onToggleSave != null) {
                // Drawn rather than set as a glyph: "★" resolves to whatever font the
                // device falls back to, which differs enough between handsets to look
                // like a bug.
                Icon(
                    painter = painterResource(
                        if (article.saved) R.drawable.ic_star else R.drawable.ic_star_border
                    ),
                    contentDescription = if (article.saved) "Remove from saved" else "Save",
                    tint = if (article.saved) MaterialTheme.colorScheme.primary else Chalk500,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onToggleSave)
                        // Inside the touch target, not around it: the glyph draws at
                        // 20dp while the tappable area stays 36.
                        .padding(8.dp),
                )
            }
        }
    }
}

/**
 * A ticker, set in a monospace face.
 *
 * Symbols are codes, not words. Proportional type gives TATASTEEL and ITC wildly
 * different widths and makes a column of them impossible to scan; fixed advance widths
 * line them up, which is the whole reason exchange screens have always set them this way.
 */
@Composable
private fun TickerChip(
    symbol: String,
    watched: Boolean,
    changePercent: Double?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (watched) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (watched) FontWeight.SemiBold else FontWeight.Medium,
            color = if (watched) MaterialTheme.colorScheme.primary else Chalk500,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Absent rather than zero when the desk has not sent a fresh quote. A price that
        // silently falls back to a placeholder is worse than no price: you cannot tell
        // "unchanged" from "the bridge has been down since lunch".
        if (changePercent != null) {
            Text(
                text = StoryPresentation.changeLabel(changePercent),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = if (changePercent < 0) QuoteDown else QuoteUp,
                maxLines = 1,
            )
        }
    }
}

/**
 * An exchange filing, as one line.
 *
 * A filing is a ticker, a subject and a time — there is no byline worth reading and no
 * cluster to speak of. Rendering it with a headline, a source and a chip row is what
 * turns fourteen routine intimations into a wall that owns the screen; as rows, the same
 * fourteen fit where four cards did and read as the register they are.
 */
@Composable
fun FilingRow(
    article: ArticleSummary,
    nowMillis: Long,
    watchlist: Set<String>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val symbol = article.symbols.firstOrNull()
    val followed = symbol != null && symbol in watchlist

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = symbol ?: "—",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = if (followed) MaterialTheme.colorScheme.primary else Chalk500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Fixed, so the subjects line up into a readable second column. A symbol
                // long enough to overflow it is rarer than a ragged column is annoying.
                modifier = Modifier.width(76.dp),
            )
            Text(
                text = article.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (article.read || !followed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = RelativeTime.format(article.publishedAt, nowMillis),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Chalk500,
                maxLines = 1,
            )
        }
    }
}

/**
 * A story you can act on without opening it.
 *
 * Swipe right saves, swipe left toggles read. Both were already one tap away — the point
 * is that triaging forty headlines is a thumb travelling down the list, and making it
 * leave that line to hit a 36dp star is what makes the list feel slow.
 *
 * Deliberately the two reversible actions. Muting a source is the other obvious candidate
 * and stays in the sheet: it changes what arrives tomorrow, and a gesture you can trigger
 * by mis-scrolling is the wrong home for it.
 */
@Composable
fun SwipeableStoryCard(
    article: ArticleSummary,
    nowMillis: Long,
    watchlist: Set<String>,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
    onToggleRead: () -> Unit,
    onSelectSymbol: (String) -> Unit,
    onToggleSymbol: (String) -> Unit,
    modifier: Modifier = Modifier,
    lead: Boolean = false,
    showCategory: Boolean = false,
    prices: PriceBook = PriceBook(),
) {
    val haptics = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onToggleSave()
                SwipeToDismissBoxValue.EndToStart -> onToggleRead()
                SwipeToDismissBoxValue.Settled -> return@rememberSwipeToDismissBoxState true
            }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            // Refused on purpose, so the row springs back instead of vanishing. Neither
            // action removes the story — a saved one stays where it was, and a read one
            // fades rather than disappearing, because you often want it again.
            false
        },
    )

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = { SwipeBackground(state.dismissDirection, article) },
    ) {
        StoryCard(
            article = article,
            nowMillis = nowMillis,
            watchlist = watchlist,
            onOpen = onOpen,
            onSelectSymbol = onSelectSymbol,
            onToggleSymbol = onToggleSymbol,
            lead = lead,
            showCategory = showCategory,
            prices = prices,
        )
    }
}

/** Named rather than iconographic: a gesture nobody has been taught needs its label once. */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, article: ArticleSummary) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val saving = direction == SwipeToDismissBoxValue.StartToEnd
    val label = when {
        saving && article.saved -> "Unsave"
        saving -> "Save"
        article.read -> "Unread"
        else -> "Read"
    }
    val icon = if (saving) R.drawable.ic_star else R.drawable.ic_check

    Row(
        Modifier
            .fillMaxSize()
            .background(if (saving) AccentDim else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (saving) Arrangement.Start else Arrangement.End,
    ) {
        if (!saving) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Chalk500)
        }
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (saving) MaterialTheme.colorScheme.primary else Chalk500,
            modifier = Modifier.padding(horizontal = 8.dp).size(18.dp),
        )
        if (saving) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The move since the story broke.
 *
 * Labelled, not just coloured. "+2.1%" on its own would be read as the day's move — which
 * is the number sitting six pixels below it on the ticker — and the whole point is that
 * these are different: a stock up four per cent on the session that has not moved since
 * publication has already priced whatever you are reading.
 *
 * The previous-close basis says so in its own words rather than borrowing the same
 * phrasing, because it is a weaker claim: nobody watched the gap happen, and attributing
 * an overnight move to one headline would be inventing causation the data cannot support.
 */
@Composable
private fun ReactionLabel(reaction: Reaction) {
    val rising = reaction.percent >= 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = StoryPresentation.changeLabel(reaction.percent),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = if (rising) QuoteUp else QuoteDown,
            maxLines = 1,
        )
        Text(
            text = when (reaction.basis) {
                ReactionBasis.PUBLICATION -> " since"
                ReactionBasis.PREVIOUS_CLOSE -> " on close"
            },
            style = MaterialTheme.typography.labelSmall,
            color = Chalk500,
            maxLines = 1,
        )
    }
}

/**
 * How busy the stock is, when that is worth saying at all.
 *
 * Silent below [VolumeProfile.ACTIVE_RATIO], which is most of the time and by design.
 * A badge that appears on every row stops being read within a day, and ordinary volume is
 * not information — the whole value of this one is that seeing it is unusual.
 */
@Composable
private fun VolumeBadge(ratio: Double?) {
    val level = VolumeProfile.levelOf(ratio)
    if (ratio == null || level == VolumeLevel.ORDINARY) return
    Text(
        text = VolumeProfile.label(ratio),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        // Amber rather than the price colours: this is neither up nor down, and borrowing
        // green or red would suggest a direction the number does not carry.
        color = if (level == VolumeLevel.HEAVY) CatRegulatory else Chalk500,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
