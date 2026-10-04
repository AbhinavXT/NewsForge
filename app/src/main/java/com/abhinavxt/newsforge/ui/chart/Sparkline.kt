package com.abhinavxt.newsforge.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.quote.PriceSample
import com.abhinavxt.newsforge.core.quote.SampleWindow
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp

/**
 * The session so far, with a mark where the story landed.
 *
 * This is the picture behind the reaction percentage the sheet already prints. "Up 2.1%
 * since publication" is a claim; the shape either supports it or shows that the move
 * happened forty minutes before the headline existed and the number is coincidence. No
 * broker app can draw this, because the broker does not know when the story landed.
 *
 * Plotted against time rather than sample index. The app polls per minute while the market
 * is open and not at all when it is asleep, so a phone that missed an hour has an hour's
 * worth of missing samples — and on an index axis that hour takes no width at all, which
 * slides the marker to a time the story was not published at. Time as the axis costs a
 * flat stretch across the gap and keeps the mark where it belongs.
 */
@Composable
fun Sparkline(
    samples: List<PriceSample>,
    markerAtMillis: Long,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
) {
    // Remembered: this sorts a session's worth of samples, and a composable body runs
    // on the frame. The samples only change when a poll lands.
    val session = remember(samples) { SampleWindow.latestSession(samples) }
    if (session.size < 3) return

    val first = session.first().atMillis
    val last = session.last().atMillis
    val span = (last - first).toFloat().takeIf { it > 0f } ?: return

    // Coloured against the price at the mark, not against the session open: this sits
    // under a "since publication" figure, and the two disagreeing would be worse than
    // either alone.
    val basePrice = session.lastOrNull { it.atMillis <= markerAtMillis }?.price
        ?: session.first().price
    val colour = if (session.last().price >= basePrice) QuoteUp else QuoteDown

    Canvas(modifier.fillMaxWidth().height(height)) {
        val high = session.maxOf { it.price }
        val low = session.minOf { it.price }
        val range = (high - low).takeIf { it > 1e-9 } ?: 1.0
        // A little headroom, or a session high touches the frame and reads as clipped.
        val pad = range * 0.08

        fun x(at: Long): Float = size.width * (at - first) / span
        fun y(price: Double): Float =
            (size.height * (1.0 - (price - (low - pad)) / (range + pad * 2))).toFloat()

        val line = Path()
        for ((index, sample) in session.withIndex()) {
            val px = x(sample.atMillis)
            val py = y(sample.price)
            if (index == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }

        val fill = Path().apply {
            addPath(line)
            lineTo(x(last), size.height)
            lineTo(x(first), size.height)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                listOf(colour.copy(alpha = 0.20f), Color.Transparent),
                startY = 0f,
                endY = size.height,
            ),
        )
        drawPath(line, color = colour, style = Stroke(width = 2f))

        // The mark, only when the story falls inside the session on screen. A story from
        // yesterday would otherwise be pinned to the left edge and read as having been
        // published at the open.
        if (markerAtMillis in first..last) {
            drawLine(
                color = Chalk500,
                start = Offset(x(markerAtMillis), 0f),
                end = Offset(x(markerAtMillis), size.height),
                strokeWidth = 1.5f,
            )
        }
    }
}
