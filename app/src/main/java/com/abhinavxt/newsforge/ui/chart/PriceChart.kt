package com.abhinavxt.newsforge.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.quote.Candle
import com.abhinavxt.newsforge.ui.theme.Accent
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.Ink500
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp

/**
 * Price history, drawn as candles or as a line depending on how much room each bar gets.
 *
 * The switch is on measured width rather than on the selected range. A year of daily bars
 * is a candle and a half per pixel on a phone, at which point the wicks merge into a grey
 * band and the open and close are a coin toss — so below three pixels a bar it draws the
 * closes as a line instead, which is the honest rendering of data that dense. Deciding it
 * from the actual canvas means the chart is right in a landscape window and on a tablet
 * too, without either having to be anticipated.
 *
 * Story markers along the bottom are the reason this screen exists. Every other app can
 * draw this chart; this one knows which days the reader has coverage for, and putting the
 * two on the same time axis is what turns a price into an explanation.
 */
@Composable
fun PriceChart(
    candles: List<Candle>,
    storyTimes: List<Long>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
) {
    if (candles.size < 2) {
        Box(
            modifier = modifier.fillMaxWidth().height(height),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (candles.isEmpty()) "No price history yet" else "Waiting for more bars",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = Chalk500)
    val rising = candles.last().close >= candles.first().close

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        // Right gutter for the price labels, bottom strip for the story ticks. Reserved
        // rather than overlaid: a label sitting on top of the line is unreadable exactly
        // when the price is near the top or bottom of the range, which is when it matters.
        val gutter = 52.dp.toPx()
        val ticks = 14.dp.toPx()
        val plotWidth = size.width - gutter
        val plotHeight = size.height - ticks
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        val high = candles.maxOf { it.high }
        val low = candles.minOf { it.low }
        // Two per cent of headroom, or the extremes sit exactly on the frame and read as
        // clipped rather than as extremes.
        val pad = ((high - low) * 0.02).takeIf { it > 0 } ?: (high * 0.01).coerceAtLeast(0.01)
        val top = high + pad
        val bottom = low - pad
        val span = (top - bottom).takeIf { it > 1e-9 } ?: 1.0

        fun yFor(price: Double): Float =
            (plotHeight * (1.0 - (price - bottom) / span)).toFloat()

        val slot = plotWidth / candles.size
        fun xFor(index: Int): Float = slot * index + slot / 2f

        drawGrid(plotWidth, plotHeight)

        if (slot >= 3.dp.toPx()) {
            drawCandles(candles, slot, plotHeight, ::xFor, ::yFor)
        } else {
            drawCloseLine(candles, rising, ::xFor, ::yFor, plotHeight)
        }

        drawLastPrice(candles.last().close, plotWidth, rising, ::yFor)
        drawStoryTicks(storyTimes, candles, plotHeight, size.height, ::xFor)
        drawPriceLabels(measurer, labelStyle, top, bottom, plotWidth, plotHeight, size.width)
    }
}

/** Three faint horizontal rules — enough to read a level against, few enough to ignore. */
private fun DrawScope.drawGrid(plotWidth: Float, plotHeight: Float) {
    for (i in 1..3) {
        val y = plotHeight * i / 4f
        drawLine(
            color = Ink500.copy(alpha = 0.45f),
            start = Offset(0f, y),
            end = Offset(plotWidth, y),
            strokeWidth = 1f,
        )
    }
}

private fun DrawScope.drawCandles(
    candles: List<Candle>,
    slot: Float,
    plotHeight: Float,
    xFor: (Int) -> Float,
    yFor: (Double) -> Float,
) {
    // A little air between bars, but never so little that the body disappears.
    val body = (slot * 0.7f).coerceAtLeast(1f)
    for ((index, candle) in candles.withIndex()) {
        val x = xFor(index)
        val up = candle.close >= candle.open
        val color = if (up) QuoteUp else QuoteDown

        drawLine(
            color = color,
            start = Offset(x, yFor(candle.high)),
            end = Offset(x, yFor(candle.low)),
            strokeWidth = 1f.coerceAtLeast(slot * 0.12f),
        )
        val openY = yFor(candle.open)
        val closeY = yFor(candle.close)
        // A doji has zero height and would draw as nothing at all, so it gets a hairline.
        val bodyTop = minOf(openY, closeY)
        val bodyHeight = (kotlin.math.abs(closeY - openY)).coerceAtLeast(1f)
        drawRect(
            color = color,
            topLeft = Offset(x - body / 2f, bodyTop.coerceIn(0f, plotHeight)),
            size = Size(body, bodyHeight),
        )
    }
}

private fun DrawScope.drawCloseLine(
    candles: List<Candle>,
    rising: Boolean,
    xFor: (Int) -> Float,
    yFor: (Double) -> Float,
    plotHeight: Float,
) {
    val color = if (rising) QuoteUp else QuoteDown
    val line = Path()
    for ((index, candle) in candles.withIndex()) {
        val x = xFor(index)
        val y = yFor(candle.close)
        if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }

    // The fill is the same path closed to the baseline. Drawn first so the stroke sits on
    // top of its own gradient rather than being half-covered by it.
    val fill = Path().apply {
        addPath(line)
        lineTo(xFor(candles.lastIndex), plotHeight)
        lineTo(xFor(0), plotHeight)
        close()
    }
    drawPath(
        path = fill,
        brush = Brush.verticalGradient(
            listOf(color.copy(alpha = 0.22f), Color.Transparent),
            startY = 0f,
            endY = plotHeight,
        ),
    )
    drawPath(path = line, color = color, style = Stroke(width = 2f))
}

/** A dashed rule at the last close, so the current price is findable without reading. */
private fun DrawScope.drawLastPrice(
    close: Double,
    plotWidth: Float,
    rising: Boolean,
    yFor: (Double) -> Float,
) {
    val y = yFor(close)
    drawLine(
        color = (if (rising) QuoteUp else QuoteDown).copy(alpha = 0.55f),
        start = Offset(0f, y),
        end = Offset(plotWidth, y),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
    )
}

/**
 * One tick per bar that has coverage, not one per story.
 *
 * A day with nine stories would otherwise draw nine marks on top of each other and read
 * as a thicker one — which looks like emphasis and is an artefact. Collapsing to the bar
 * means the strip says "there is news here", and the timeline below says how much.
 */
private fun DrawScope.drawStoryTicks(
    storyTimes: List<Long>,
    candles: List<Candle>,
    plotHeight: Float,
    fullHeight: Float,
    xFor: (Int) -> Float,
) {
    if (storyTimes.isEmpty()) return
    val marked = HashSet<Int>()
    for (at in storyTimes) {
        // The bar the story falls in: the last one that opened at or before it.
        val index = candles.indexOfLast { it.openTimeMillis <= at }
        if (index >= 0) marked += index
    }
    for (index in marked) {
        val x = xFor(index)
        drawLine(
            color = Accent,
            start = Offset(x, plotHeight + 3f),
            end = Offset(x, fullHeight - 2f),
            strokeWidth = 2f,
        )
    }
}

private fun DrawScope.drawPriceLabels(
    measurer: TextMeasurer,
    style: TextStyle,
    top: Double,
    bottom: Double,
    plotWidth: Float,
    plotHeight: Float,
    fullWidth: Float,
) {
    fun label(value: Double, y: Float) {
        val text = formatPrice(value)
        val measured = measurer.measure(text, style)
        drawText(
            textMeasurer = measurer,
            text = text,
            style = style,
            topLeft = Offset(
                x = (plotWidth + 6f).coerceAtMost(fullWidth - measured.size.width),
                y = (y - measured.size.height / 2f).coerceIn(0f, plotHeight - measured.size.height),
            ),
        )
    }
    label(top, 0f)
    label((top + bottom) / 2, plotHeight / 2f)
    label(bottom, plotHeight)
}

/**
 * Two decimals below a thousand, none above.
 *
 * Paise matter on a forty-rupee stock and are noise on a hundred-thousand-rupee one, and
 * the axis has room for about six characters either way.
 */
internal fun formatPrice(value: Double): String =
    if (kotlin.math.abs(value) >= 1000) {
        String.format(java.util.Locale.US, "%,.0f", value)
    } else {
        String.format(java.util.Locale.US, "%.2f", value)
    }

/**
 * Volume in the units an Indian market reader thinks in.
 *
 * 1,23,45,678 is unreadable at a glance and "12345678" worse; "1.23 Cr" is the figure as
 * anyone here would say it aloud. Lakh and crore rather than K and M for the same reason
 * the prices carry rupees.
 */
internal fun formatVolume(value: Double): String = when {
    value >= 1_00_00_000 -> String.format(java.util.Locale.US, "%.2f Cr", value / 1_00_00_000)
    value >= 1_00_000 -> String.format(java.util.Locale.US, "%.2f L", value / 1_00_000)
    value >= 1_000 -> String.format(java.util.Locale.US, "%,.0f", value)
    else -> value.toLong().toString()
}
