package com.abhinavxt.newsforge.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.quote.VolumeCurve
import com.abhinavxt.newsforge.ui.theme.Accent
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.Ink500

/**
 * Today's cumulative volume against a normal session, mark by mark.
 *
 * The "2.1x vol" chip elsewhere says how busy a stock is. This says *when* it got busy,
 * which is a different fact and usually the more useful one next to a headline. Volume
 * that arrived in the first twenty minutes and then stopped is a reaction to something
 * overnight; the same total accumulated evenly through the afternoon is not. A single
 * ratio describes both identically.
 *
 * The grey band is the median session, the line is today. Reading it is the gap between
 * them, so the band is drawn filled and mute and the line bright over it — the shape of
 * the divergence is the content, and the absolute numbers are not worth an axis.
 */
@Composable
fun VolumePane(curve: VolumeCurve, modifier: Modifier = Modifier) {
    val reached = curve.today.indexOfLast { it != null }
    val latest = curve.today.getOrNull(reached)
    val normal = curve.typical.getOrNull(reached)
    val ratio = if (latest != null && normal != null && normal > 0) latest / normal else null

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Volume vs normal",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = ratio?.let { String.format(java.util.Locale.US, "%.1fx", it) } ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = if (ratio == null) Chalk500 else Accent,
            )
        }

        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val points = curve.typical.size
            if (points < 2) return@Canvas
            // Scaled to the larger of the two, so a heavy day is visibly above the band
            // rather than clipped flat against the top of the pane.
            val peak = maxOf(
                curve.typical.filterNotNull().maxOrNull() ?: 0.0,
                curve.today.filterNotNull().maxOrNull() ?: 0.0,
            )
            if (peak <= 0.0) return@Canvas

            val slot = size.width / (points - 1).toFloat()
            fun x(i: Int) = slot * i
            fun y(v: Double) = (size.height * (1.0 - v / peak)).toFloat()

            // The band, closed to the baseline.
            val band = Path()
            var started = false
            for ((i, value) in curve.typical.withIndex()) {
                if (value == null) continue
                if (!started) {
                    band.moveTo(x(i), y(value)); started = true
                } else {
                    band.lineTo(x(i), y(value))
                }
            }
            if (started) {
                val last = curve.typical.indexOfLast { it != null }
                val first = curve.typical.indexOfFirst { it != null }
                val filled = Path().apply {
                    addPath(band)
                    lineTo(x(last), size.height)
                    lineTo(x(first), size.height)
                    close()
                }
                drawPath(filled, color = Ink500.copy(alpha = 0.55f))
                drawPath(band, color = Ink500, style = Stroke(width = 1f))
            }

            // Today, stopping where the session has actually reached rather than running
            // on to the close — a line drawn to the right edge at noon would claim a full
            // day's volume was already in.
            val line = Path()
            var begun = false
            for ((i, value) in curve.today.withIndex()) {
                if (value == null) continue
                if (!begun) {
                    line.moveTo(x(i), y(value)); begun = true
                } else {
                    line.lineTo(x(i), y(value))
                }
            }
            if (begun) drawPath(line, color = Accent, style = Stroke(width = 2f))
        }

        Text(
            text = "Median of ${curve.priorSessions} sessions",
            style = MaterialTheme.typography.labelSmall,
            color = Chalk500,
        )
    }
}
