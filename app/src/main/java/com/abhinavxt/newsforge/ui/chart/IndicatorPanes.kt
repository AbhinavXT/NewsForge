package com.abhinavxt.newsforge.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.ta.Indicators
import com.abhinavxt.newsforge.core.ta.PriceRange
import com.abhinavxt.newsforge.ui.theme.Accent
import com.abhinavxt.newsforge.ui.theme.Chalk100
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.Ink500
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp

/**
 * A titled strip under the price chart.
 *
 * Every indicator gets the same frame — same height, same label position, same left edge —
 * so the eye can drop from one to the next without re-finding anything. The current value
 * sits in the header rather than on the plot, because the number is what gets read and
 * the shape is what gets glanced at.
 */
@Composable
private fun IndicatorPane(
    title: String,
    value: String?,
    valueColor: Color,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = if (value == null) Chalk500 else valueColor,
            )
        }
        content()
    }
}

/**
 * Relative strength, with the thirty and seventy lines drawn in.
 *
 * Those two guides are the only reason the number is legible at a glance: 62 means
 * nothing on its own and everything against the band. Drawn as dashes so they read as
 * reference rather than as data.
 */
@Composable
fun RsiPane(values: List<Double?>, modifier: Modifier = Modifier) {
    val latest = values.lastOrNull { it != null }
    val colour = when {
        latest == null -> Chalk500
        latest >= 70 -> QuoteDown
        latest <= 30 -> QuoteUp
        else -> Chalk100
    }
    IndicatorPane(
        title = "RSI ${Indicators.RSI_PERIOD}",
        value = latest?.let { String.format(java.util.Locale.US, "%.1f", it) },
        valueColor = colour,
    ) {
        Canvas(modifier.fillMaxWidth().height(56.dp)) {
            val defined = values.count { it != null }
            if (defined < 2) return@Canvas
            val slot = size.width / values.size
            fun y(v: Double) = (size.height * (1.0 - (v.coerceIn(0.0, 100.0) / 100.0))).toFloat()

            for (level in listOf(30.0, 70.0)) {
                drawLine(
                    color = Ink500,
                    start = Offset(0f, y(level)),
                    end = Offset(size.width, y(level)),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)),
                )
            }

            val path = Path()
            var started = false
            for ((index, value) in values.withIndex()) {
                if (value == null) continue
                val x = slot * index + slot / 2f
                if (!started) {
                    path.moveTo(x, y(value)); started = true
                } else {
                    path.lineTo(x, y(value))
                }
            }
            drawPath(path, color = Accent, style = Stroke(width = 2f))
        }
    }
}

/**
 * MACD as a histogram with its two lines over the top.
 *
 * The histogram is the part that gets read — it crosses zero before the lines visibly
 * cross — so it is drawn solid and the lines are thin over it. Zero is a real rule here
 * rather than a dashed guide, because on this pane the sign is the signal.
 */
@Composable
fun MacdPane(macd: Indicators.Macd, modifier: Modifier = Modifier) {
    val latest = macd.histogram.lastOrNull { it != null }
    IndicatorPane(
        title = "MACD ${Indicators.MACD_FAST}/${Indicators.MACD_SLOW}/${Indicators.MACD_SIGNAL}",
        value = latest?.let { String.format(java.util.Locale.US, "%+.2f", it) },
        valueColor = if ((latest ?: 0.0) >= 0) QuoteUp else QuoteDown,
    ) {
        Canvas(modifier.fillMaxWidth().height(56.dp)) {
            val all = (macd.line + macd.signal + macd.histogram).filterNotNull()
            if (all.size < 2) return@Canvas
            // Symmetric about zero, so the zero rule sits in the middle and a positive
            // and a negative bar of equal size look equal.
            val extent = all.maxOf { kotlin.math.abs(it) }.takeIf { it > 1e-9 } ?: return@Canvas
            val slot = size.width / macd.line.size
            fun y(v: Double) = (size.height / 2f * (1.0 - v / extent)).toFloat()

            val zero = y(0.0)
            drawLine(Ink500, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1f)

            val body = (slot * 0.6f).coerceAtLeast(1f)
            for ((index, value) in macd.histogram.withIndex()) {
                if (value == null) continue
                val x = slot * index + slot / 2f
                val top = minOf(zero, y(value))
                val h = kotlin.math.abs(y(value) - zero).coerceAtLeast(1f)
                drawRect(
                    color = if (value >= 0) QuoteUp.copy(alpha = 0.7f) else QuoteDown.copy(alpha = 0.7f),
                    topLeft = Offset(x - body / 2f, top),
                    size = Size(body, h),
                )
            }

            fun line(series: List<Double?>, colour: Color) {
                val path = Path()
                var started = false
                for ((index, value) in series.withIndex()) {
                    if (value == null) continue
                    val x = slot * index + slot / 2f
                    if (!started) {
                        path.moveTo(x, y(value)); started = true
                    } else {
                        path.lineTo(x, y(value))
                    }
                }
                if (started) drawPath(path, color = colour, style = Stroke(width = 1.5f))
            }
            line(macd.line, Accent)
            line(macd.signal, Chalk500)
        }
    }
}

/**
 * Where the price sits inside a range, as a bar with a marker.
 *
 * Used for the 52-week window. A high and a low as two numbers require the reader to do
 * the subtraction; a marker two thirds along says the same thing without arithmetic, and
 * the numbers stay underneath for when the exact figure is the point.
 */
@Composable
fun RangeBar(
    label: String,
    range: PriceRange,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.fillMaxWidth().height(20.dp).padding(vertical = 6.dp)) {
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val track = size.height / 2f
                drawLine(
                    color = Ink500,
                    start = Offset(0f, track),
                    end = Offset(size.width, track),
                    strokeWidth = size.height,
                )
                val position = range.position
                if (position != null) {
                    val x = (size.width * position).toFloat()
                    drawLine(
                        color = Accent,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 3f,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatPrice(range.low),
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
            )
            Text(
                text = formatPrice(range.high),
                style = MaterialTheme.typography.labelSmall,
                color = Chalk500,
            )
        }
    }
}
