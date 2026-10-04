package com.abhinavxt.newsforge.ui.components

import androidx.compose.runtime.getValue
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.core.rank.MarketPhase
import com.abhinavxt.newsforge.ui.chart.ShimmerBlock
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.CatPolicy
import com.abhinavxt.newsforge.ui.theme.Hairline
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import java.util.Locale

/**
 * The shared vocabulary every screen is built from.
 *
 * Kept in one file on purpose. These are small, and the point of them is that they are
 * the same everywhere — a chip on the calendar and a chip on the feed should not drift
 * apart because they were styled in two places by two different afternoons.
 */

/** Gutter between a screen's edge and its cards. */
val ScreenGutter: Dp = 16.dp

/**
 * A screen's title block: large title, one quiet line under it, actions on the right.
 *
 * Replaces the stock top app bar. That bar centres a 17sp title in a 64dp strip, which is
 * the right call for a document and the wrong one for a dashboard — here the title is the
 * landmark that tells you which of four tabs you are on, and it should read that way.
 *
 * With [onBack] the title drops a size, since a pushed screen is a detail of the tab
 * rather than a place of its own.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    eyebrow: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = if (onBack != null) 8.dp else ScreenGutter + 4.dp,
                end = 10.dp,
                top = 10.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconCircleButton(
                icon = R.drawable.ic_arrow_back,
                contentDescription = "Back",
                onClick = onBack,
                container = Color.Transparent,
            )
        }
        Column(Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp)) {
            eyebrow?.let {
                it()
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = title,
                style = if (onBack != null) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = Chalk500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * An icon in a soft circle. The touch target is the whole circle, 40dp, which is the
 * smallest that a thumb hits reliably on a phone held in one hand.
 */
@Composable
fun IconCircleButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    enabled: Boolean = true,
    size: Dp = 40.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * A card: rounded, one tier above the page, hairline edge.
 *
 * The edge is what keeps two adjacent cards from merging into one block on the darker
 * panels some phones ship, where a one-step lift in lightness alone is not visible.
 */
@Composable
fun NfCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surface,
    border: Color? = Hairline,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = color,
        border = border?.let { BorderStroke(1.dp, it) },
    ) {
        Column(
            Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * A card holding a list of rows separated by hairlines — the settings-style group.
 *
 * Rows supply their own padding; the group supplies the shape and the seams.
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    NfCard(modifier = modifier, contentPadding = PaddingValues(0.dp), content = content)
}

@Composable
fun GroupDivider(inset: Dp = 16.dp) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = inset),
    )
}

/**
 * A small tinted label: a category, an event type, a status.
 *
 * The tint is the colour at low alpha over the card, and the text is the colour at full
 * strength — readable, and quieter than a solid fill, which would shout louder than the
 * headline it is labelling.
 */
@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    dot: Boolean = false,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot) Dot(color, 5.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
fun Dot(color: Color, size: Dp = 6.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/**
 * A price move as a filled pill: sign, number and colour together.
 *
 * Exactly zero gets the neutral text colour rather than green. Flat is not a small gain.
 */
@Composable
fun ChangePill(percent: Double, modifier: Modifier = Modifier, suffix: String = "%") {
    val color = when {
        percent > 0.0 -> QuoteUp
        percent < 0.0 -> QuoteDown
        else -> Chalk500
    }
    Text(
        text = String.format(Locale.US, "%+.2f", percent) + suffix,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * A filter chip. One component for every chip in the app, so selected looks the same on
 * every screen.
 *
 * [count] is drawn as its own small badge rather than appended to the label: "Results 9"
 * as one string reads as a name with a number in it, while a separate badge reads as a
 * name and how many.
 */
@Composable
fun NfChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    accent: Color? = null,
    monospace: Boolean = false,
) {
    // A wash of the accent with accent-coloured text, rather than a solid accent fill. On a
    // strip of eight chips with two selected, solid fills were most of the accent on the
    // screen; the wash says "on" just as clearly at a fraction of the colour.
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        label = "chip-container",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chip-content",
    )
    val edge by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else Hairline,
        label = "chip-edge",
    )
    Row(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(container)
            .border(1.dp, edge, RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (accent != null && !selected) Dot(accent, 6.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = content,
            maxLines = 1,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (selected) content else Chalk500,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (selected) content.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.background.copy(alpha = 0.6f)
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** A horizontally scrolling strip of chips with the screen gutter on both ends. */
@Composable
fun ChipStrip(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ScreenGutter, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A section heading inside a list: optional coloured dot, caps title, count on the right.
 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    accent: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = ScreenGutter + 4.dp, end = ScreenGutter + 4.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (accent != null) Dot(accent, 7.dp)
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 4,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Chalk500,
            )
        }
        trailing?.invoke()
    }
}

/**
 * Two to four options with a sliding selection behind them.
 *
 * Used where the options are modes of one view rather than independent filters — Live and
 * Brief, a chart range. Chips would suggest they combine; a segmented control says they
 * do not.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
) {
    BoxWithConstraints(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, Hairline, RoundedCornerShape(height / 2))
            .padding(3.dp),
    ) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val offset by animateDpAsState(
            targetValue = segment * selectedIndex.coerceIn(0, options.lastIndex.coerceAtLeast(0)),
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
            label = "segment-offset",
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(RoundedCornerShape((height - 6.dp) / 2))
                // The same wash as a selected chip, edged so the thumb holds its shape.
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    RoundedCornerShape((height - 6.dp) / 2),
                )
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val color by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "segment-label",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape((height - 6.dp) / 2))
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = color,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Where the session is, as a pill with a status light.
 *
 * The light pulses only while the market is open. A pulsing light is a claim that
 * something is live, and outside the session nothing is.
 */
@Composable
fun MarketStatusPill(nowMillis: Long, modifier: Modifier = Modifier) {
    val phase = MarketClock.phase(nowMillis)
    val (label, color) = when (phase) {
        MarketPhase.OPEN -> "Market open" to QuoteUp
        MarketPhase.PRE_OPEN -> "Pre-open" to CatPolicy
        MarketPhase.POST_CLOSE -> "Market closed" to Chalk500
        MarketPhase.WEEKEND -> "Weekend" to Chalk500
    }
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (phase == MarketPhase.OPEN) {
            val transition = rememberInfiniteTransition(label = "market-pulse")
            val pulse by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                label = "market-pulse-alpha",
            )
            Dot(color, 6.dp, Modifier.alpha(pulse))
        } else {
            Dot(color, 6.dp)
        }
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 3,
            color = color,
        )
    }
}

/**
 * An empty screen that says why it is empty and what to do about it.
 */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, Hairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (actionLabel != null) {
                TonalButton(actionLabel, onAction, Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        border = BorderStroke(1.dp, Hairline),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = contentColor,
        ),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A card-shaped placeholder for a story, for the first load.
 *
 * The shape of what is coming rather than a spinner: a spinner says "wait", a skeleton
 * says "wait, and here is roughly what you are waiting for", which makes the same second
 * feel shorter.
 */
@Composable
fun StorySkeleton(modifier: Modifier = Modifier) {
    NfCard(modifier.padding(horizontal = ScreenGutter, vertical = 5.dp)) {
        ShimmerBlock(Modifier.width(72.dp).height(12.dp))
        Spacer(Modifier.height(12.dp))
        ShimmerBlock(Modifier.fillMaxWidth().height(14.dp))
        Spacer(Modifier.height(6.dp))
        ShimmerBlock(Modifier.fillMaxWidth(0.7f).height(14.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerBlock(Modifier.width(56.dp).height(18.dp))
            ShimmerBlock(Modifier.width(56.dp).height(18.dp))
        }
    }
}
