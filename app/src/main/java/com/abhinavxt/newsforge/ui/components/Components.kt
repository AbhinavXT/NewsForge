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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.rank.MarketClock
import com.abhinavxt.newsforge.core.rank.MarketPhase
import com.abhinavxt.newsforge.ui.chart.ShimmerBlock
import com.abhinavxt.newsforge.ui.theme.Accent
import com.abhinavxt.newsforge.ui.theme.AccentViolet
import com.abhinavxt.newsforge.ui.theme.Chalk300
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.CatPolicy
import com.abhinavxt.newsforge.ui.theme.EyebrowStyle
import com.abhinavxt.newsforge.ui.theme.Hairline
import com.abhinavxt.newsforge.ui.theme.Ink500
import com.abhinavxt.newsforge.ui.theme.Ink600
import com.abhinavxt.newsforge.ui.theme.Ink700
import com.abhinavxt.newsforge.ui.theme.Mono
import com.abhinavxt.newsforge.ui.theme.NumStyle
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import com.abhinavxt.newsforge.ui.theme.ThemeState
import java.util.Locale

/**
 * The shared vocabulary every screen is built from.
 *
 * Kept in one file on purpose. These are small, and the point of them is that they are
 * the same everywhere — a chip on the calendar and a chip on the feed should not drift
 * apart because they were styled in two places by two different afternoons.
 *
 * The look follows the OrderBlock app and TickerForge's terminal: flat panels with a
 * hairline edge, uppercase tracked eyebrows over sections, mono figures with ▲/▼ so
 * direction never rests on colour alone, index tags ("01") and monogram badges for
 * ranked things, and a tinted wash plus corner brackets reserved for whatever is first.
 */

/** Gutter between a screen's edge and its cards. */
val ScreenGutter: Dp = 16.dp

/** Panels: OrderBlock's `--r-lg`. */
val PanelShape = RoundedCornerShape(16.dp)

/** Controls: buttons, inputs, segmented thumbs. OrderBlock's `--r-sm`. */
val ControlShape = RoundedCornerShape(9.dp)

/**
 * A screen's title block: an eyebrow, a large title, one quiet line under it, actions on
 * the right.
 *
 * Replaces the stock top app bar. Here the title is the landmark that tells you which tab
 * you are on, and it should read that way. With [onBack] the title drops a size, since a
 * pushed screen is a detail of the tab rather than a place of its own.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    eyebrow: (@Composable () -> Unit)? = null,
    /** Drawn before the title: a monogram on a symbol's screen. */
    badge: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = if (onBack != null) 10.dp else ScreenGutter + 2.dp,
                end = 12.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconCircleButton(
                icon = R.drawable.ic_arrow_back,
                contentDescription = "Back",
                onClick = onBack,
            )
        }
        if (badge != null) {
            Spacer(Modifier.width(if (onBack != null) 10.dp else 0.dp))
            badge()
        }
        Column(Modifier.weight(1f).padding(start = if (onBack != null || badge != null) 12.dp else 0.dp)) {
            eyebrow?.let {
                it()
                Spacer(Modifier.height(5.dp))
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
                    modifier = Modifier.padding(top = 3.dp),
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
 * An icon button: a filled rounded square, the way OrderBlock's `.btn.icon` is. The touch
 * target is the whole square, 40dp, the smallest a thumb hits reliably one-handed.
 *
 * Named for the circle it used to be; every call site reads the same either way.
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
    val shape = RoundedCornerShape(size * 0.25f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(container)
            .then(if (container != Color.Transparent) Modifier.border(1.dp, Hairline, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

/**
 * A panel: one tier above the page, hairline edge, 16dp corners.
 *
 * With [accent] the panel takes OrderBlock's sentiment treatment: a wash of the colour that
 * fades down the card, and an edge in the same colour. That is for the one thing on a
 * screen that is first or most important — a screen of tinted panels is a screen of none.
 */
@Composable
fun NfCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = PanelShape,
    color: Color = MaterialTheme.colorScheme.surface,
    border: Color? = Hairline,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    accent: Color? = null,
    /** A sentiment edge down the left side: priority, direction. */
    rail: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = color,
        border = (accent?.copy(alpha = 0.38f) ?: border)?.let { BorderStroke(1.dp, it) },
    ) {
        Column(
            Modifier
                .then(if (accent != null) Modifier.background(accentWash(accent)) else Modifier)
                .then(if (rail != null) Modifier.leftRail(rail) else Modifier)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(contentPadding),
            content = content,
        )
    }
}

/** The tint behind an accented panel: strongest at the top edge, gone by the bottom. */
fun accentWash(accent: Color): Brush = Brush.verticalGradient(
    listOf(
        accent.copy(alpha = if (ThemeState.isLight) 0.10f else 0.16f),
        accent.copy(alpha = if (ThemeState.isLight) 0.03f else 0.04f),
    )
)

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
 * The uppercase tracked caption over a section or beside a figure — OrderBlock's
 * `.eyebrow`. Quiet by colour, so it orders a screen without adding colour to it.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Chalk500,
) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = EyebrowStyle,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * A small tinted label: a category, an event type, a status.
 *
 * The tint is the colour at low alpha and the text is the colour at full strength —
 * readable, and quieter than a solid fill, which would shout louder than the headline it
 * is labelling.
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
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(color.copy(alpha = if (ThemeState.isLight) 0.10f else 0.13f))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot) Dot(color, 5.dp)
        Text(
            text = text,
            style = if (monospace) NumStyle.copy(fontSize = 11.sp) else MaterialTheme.typography.labelSmall,
            fontWeight = if (monospace) FontWeight.SemiBold else FontWeight.Bold,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
fun Dot(color: Color, size: Dp = 6.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** A status light with OrderBlock's soft halo: the dot, and a ring of itself at low alpha. */
@Composable
fun HaloDot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(
        modifier
            .size(size + 6.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Dot(color, size)
    }
}

/** Colour for a signed move. Exactly zero is flat, not a small gain. */
fun moveColor(value: Double): Color = when {
    value > 0.0 -> QuoteUp
    value < 0.0 -> QuoteDown
    else -> Chalk500
}

/** "▲ 2.84%" / "▼ 2.47%" / "0.00%": direction in the glyph, so colour is never alone. */
fun formatMove(percent: Double, suffix: String = "%", decimals: Int = 2): String {
    // Magnitude first, arrow from the result: a move of -0.001 is "0.00%", not a fall.
    val magnitude = String.format(Locale.US, "%.${decimals}f", kotlin.math.abs(percent))
    val arrow = when {
        magnitude.all { it == '0' || it == '.' } -> ""
        percent > 0.0 -> "▲ "
        else -> "▼ "
    }
    return arrow + magnitude + suffix
}

/**
 * A price move as a filled pill: arrow, number and colour together.
 */
@Composable
fun ChangePill(percent: Double, modifier: Modifier = Modifier, suffix: String = "%") {
    val color = moveColor(percent)
    Text(
        text = formatMove(percent, suffix),
        style = NumStyle,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** A bare move, for inline use beside a price: mono, arrowed, coloured. */
@Composable
fun Delta(
    percent: Double,
    modifier: Modifier = Modifier,
    suffix: String = "%",
    style: androidx.compose.ui.text.TextStyle = NumStyle,
    decimals: Int = 2,
) {
    Text(
        text = formatMove(percent, suffix, decimals),
        style = style,
        fontWeight = FontWeight.SemiBold,
        color = moveColor(percent),
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * A rank or position as a boxed two-digit mono number — "01", "02" — OrderBlock's index
 * marker. Two digits so a column of them lines up from first to tenth.
 */
@Composable
fun IndexTag(
    index: Int,
    modifier: Modifier = Modifier,
    color: Color = Chalk300,
) {
    Text(
        text = String.format(Locale.US, "%02d", index),
        style = NumStyle.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * A rounded-square badge with a symbol's initials — "IR" for IREDA — in the symbol's
 * colour. Gives a row of tickers something to scan by shape before reading.
 */
@Composable
fun Monogram(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    val shape = RoundedCornerShape(size * 0.28f)
    val initials = text.filter { it.isLetterOrDigit() }.take(2).uppercase(Locale.ROOT).ifEmpty { "·" }
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0.10f))
                )
            )
            .border(1.dp, color.copy(alpha = 0.45f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = NumStyle.copy(fontSize = (size.value * 0.36f).sp, lineHeight = (size.value * 0.4f).sp),
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * An icon in a tinted rounded square — OrderBlock's `.ibadge`, used beside section heads
 * and in settings rows.
 */
@Composable
fun IconBadge(
    @DrawableRes icon: Int,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.29f))
            .background(color.copy(alpha = if (ThemeState.isLight) 0.10f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * Corner brackets: an L at each corner, drawn just outside the bounds. OrderBlock's mark
 * for "this one leads the list". Pair with an accented [NfCard], and only on one item.
 */
fun Modifier.cornerBrackets(
    color: Color,
    length: Dp = 12.dp,
    stroke: Dp = 1.5.dp,
    outset: Dp = 3.dp,
): Modifier = drawWithContent {
    drawContent()
    val l = length.toPx()
    val s = stroke.toPx()
    val o = outset.toPx()
    val left = -o
    val top = -o
    val right = size.width + o
    val bottom = size.height + o
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(color, Offset(x, y), Offset(x + dx * l, y), s, StrokeCap.Square)
        drawLine(color, Offset(x, y), Offset(x, y + dy * l), s, StrokeCap.Square)
    }
    corner(left, top, 1f, 1f)
    corner(right, top, -1f, 1f)
    corner(left, bottom, 1f, -1f)
    corner(right, bottom, -1f, -1f)
}

/**
 * A rail down the left edge of a panel in the given colour — TickerForge's level card and
 * OrderBlock's zone card both carry their sentiment this way.
 */
fun Modifier.leftRail(color: Color, width: Dp = 3.dp): Modifier = drawBehind {
    drawRect(color, size = androidx.compose.ui.geometry.Size(width.toPx(), size.height))
}

/**
 * A label and a value on one line, label left in a quiet uppercase, value right in mono —
 * the "VWAP ··· 113.01" row of OrderBlock's zone card.
 */
@Composable
fun KeyValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(Locale.ROOT),
            style = NumStyle.copy(fontSize = 11.sp, letterSpacing = 0.4.sp),
            color = Chalk500,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = NumStyle,
            color = valueColor,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Figures side by side, each a label over a big mono value, split by hairlines —
 * OrderBlock's `.stats` strip. Two to four of them; more stops being a glance.
 */
@Composable
fun StatStrip(
    stats: List<Stat>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(PanelShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Hairline, PanelShape),
    ) {
        stats.forEachIndexed { i, stat ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Hairline))
            Column(
                Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Eyebrow(stat.label)
                Text(
                    text = stat.value,
                    style = NumStyle.copy(fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = (-0.6).sp),
                    fontWeight = FontWeight.SemiBold,
                    color = stat.color ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (stat.caption != null) {
                    Text(
                        text = stat.caption,
                        style = NumStyle.copy(fontSize = 11.sp),
                        color = Chalk500,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

data class Stat(
    val label: String,
    val value: String,
    val caption: String? = null,
    val color: Color? = null,
)

/** Five dots, [filled] of them lit — OrderBlock's conviction meter. */
@Composable
fun ConvictionDots(
    filled: Int,
    color: Color,
    modifier: Modifier = Modifier,
    total: Int = 5,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(total) { i ->
            if (i < filled) {
                Dot(color, 6.dp)
            } else {
                Box(Modifier.size(6.dp).border(1.dp, color.copy(alpha = 0.5f), CircleShape))
            }
        }
    }
}

/** A thin track with a fill: a score, a share, a position in a range. */
@Composable
fun ScoreBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height))
            .background(Ink700),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(height))
                .background(color),
        )
    }
}

/**
 * A filter chip. One component for every chip in the app, so selected looks the same on
 * every screen.
 *
 * Filled and borderless when off, the way OrderBlock's chips are; on, a wash of the accent
 * with accent text and an accent edge. [count] is its own small badge rather than part of
 * the label: a separate badge reads as a name and how many.
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
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        label = "chip-container",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chip-content",
    )
    val edge by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent,
        label = "chip-edge",
    )
    Row(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .border(1.dp, edge, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (accent != null && !selected) Dot(accent, 6.dp)
        Text(
            text = label,
            style = if (monospace) NumStyle else MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = content,
            maxLines = 1,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = NumStyle.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                fontWeight = FontWeight.SemiBold,
                color = if (selected) content else Chalk500,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (selected) content.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.background.copy(alpha = 0.7f)
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
 * A section heading inside a list: optional coloured dot, eyebrow title, a hairline that
 * runs to the edge, and the count in mono at the end — the divider between OrderBlock's
 * bullish and bearish boards.
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
            .padding(start = ScreenGutter + 2.dp, end = ScreenGutter + 2.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (accent != null) HaloDot(accent, size = 6.dp)
        Eyebrow(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Hairline)
        )
        if (count != null) {
            Text(
                text = String.format(Locale.US, "%02d", count),
                style = NumStyle.copy(fontSize = 11.sp),
                color = Chalk500,
            )
        }
        trailing?.invoke()
    }
}

/**
 * Two to four options with a sliding selection behind them — OrderBlock's `.seg`: a filled
 * track and a raised neutral thumb.
 *
 * Used where the options are modes of one view rather than independent filters. Chips
 * would suggest they combine; a segmented control says they do not.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
) {
    val outer = RoundedCornerShape(10.dp)
    val inner = RoundedCornerShape(8.dp)
    BoxWithConstraints(
        modifier
            .height(height)
            .clip(outer)
            .background(MaterialTheme.colorScheme.surfaceVariant)
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
                .clip(inner)
                // Raised, not tinted: the surface in light, a step up in dark.
                .background(if (ThemeState.isLight) MaterialTheme.colorScheme.surface else Ink500)
                .border(1.dp, if (ThemeState.isLight) Hairline else Ink600, inner)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val color by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "segment-label",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(inner)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        color = color,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Where the session is, as a status light and an eyebrow.
 *
 * The light pulses only while the market is open. A pulsing light is a claim that
 * something is live, and outside the session nothing is.
 */
@Composable
fun MarketStatusPill(nowMillis: Long, modifier: Modifier = Modifier) {
    val phase = MarketClock.phase(nowMillis)
    val (label, color) = when (phase) {
        MarketPhase.OPEN -> "Live" to QuoteUp
        MarketPhase.PRE_OPEN -> "Pre-open" to CatPolicy
        MarketPhase.POST_CLOSE -> "Closed" to Chalk500
        MarketPhase.WEEKEND -> "Weekend" to Chalk500
    }
    Row(
        modifier
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.11f))
            .padding(start = 4.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (phase == MarketPhase.OPEN) {
            val transition = rememberInfiniteTransition(label = "market-pulse")
            val pulse by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                label = "market-pulse-alpha",
            )
            HaloDot(color, Modifier.alpha(pulse), size = 6.dp)
        } else {
            HaloDot(color, size = 6.dp)
        }
        Eyebrow(label, color = color)
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
            IconBadge(icon, MaterialTheme.colorScheme.primary, size = 52.dp)
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
                    modifier = Modifier.padding(top = 6.dp).width(300.dp),
                )
            }
            if (actionLabel != null) {
                TonalButton(actionLabel, onAction, Modifier.padding(top = 16.dp))
            }
        }
    }
}

/**
 * The one button that is the point of a screen: the brand gradient, accent into its far
 * end, with a soft glow of itself underneath — OrderBlock's `.btn.primary`.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.55f)
            .clip(ControlShape)
            .background(Brush.linearGradient(listOf(Accent, AccentViolet)))
            .border(1.dp, Color.White.copy(alpha = 0.14f), ControlShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = onAccent, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = onAccent)
    }
}

/** The everyday button: filled, borderless, the surface one step up. */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.55f)
            .clip(ControlShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, Hairline, ControlShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = contentColor, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}

/**
 * A card-shaped placeholder for a story, for the first load.
 *
 * The shape of what is coming rather than a spinner: a skeleton says "wait, and here is
 * roughly what you are waiting for", which makes the same second feel shorter.
 */
@Composable
fun StorySkeleton(modifier: Modifier = Modifier) {
    NfCard(modifier.padding(horizontal = ScreenGutter, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShimmerBlock(Modifier.size(34.dp))
            Column(Modifier.weight(1f)) {
                ShimmerBlock(Modifier.width(72.dp).height(10.dp))
                Spacer(Modifier.height(6.dp))
                ShimmerBlock(Modifier.width(120.dp).height(10.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        ShimmerBlock(Modifier.fillMaxWidth().height(14.dp))
        Spacer(Modifier.height(6.dp))
        ShimmerBlock(Modifier.fillMaxWidth(0.7f).height(14.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerBlock(Modifier.width(56.dp).height(20.dp))
            ShimmerBlock(Modifier.width(56.dp).height(20.dp))
        }
    }
}
