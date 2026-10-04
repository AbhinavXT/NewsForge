package com.abhinavxt.newsforge.ui.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.ui.theme.Chalk500

/**
 * What the research screen looks like while the desk is answering.
 *
 * Shaped like the thing it replaces — a wide price, a small caption, three figures, a
 * chart of the same height, two indicator strips — so that when the data lands nothing
 * moves. A spinner in the middle of an empty area would be honest about the wait and
 * dishonest about the result: the reader learns nothing about what is coming, and then
 * the whole screen jumps when it does.
 *
 * It also replaces a worse message. The chart used to say "No price history yet" the
 * instant it opened, before the request had crossed the bridge — a true statement about
 * this millisecond that reads as a verdict on the company.
 */
@Composable
fun ResearchSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                ShimmerBlock(Modifier.width(132.dp).height(30.dp))
                Spacer(Modifier.width(10.dp))
                ShimmerBlock(Modifier.width(96.dp).height(18.dp))
            }
            Spacer(Modifier.height(8.dp))
            ShimmerBlock(Modifier.width(120.dp).height(12.dp))

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Column(Modifier.weight(1f)) {
                        ShimmerBlock(Modifier.width(36.dp).height(10.dp))
                        Spacer(Modifier.height(6.dp))
                        ShimmerBlock(Modifier.width(72.dp).height(16.dp))
                    }
                }
            }
        }

        // Same height as PriceChart's default, so the swap is invisible.
        ShimmerBlock(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                .height(220.dp)
        )

        Spacer(Modifier.height(16.dp))
        SkeletonPane(label = "52-week range", height = 8.dp)
        SkeletonPane(label = "RSI 14", height = 56.dp)
        SkeletonPane(label = "MACD 12/26/9", height = 56.dp)

        // The one line of actual text. A skeleton says something is coming; it cannot say
        // where from, and "the desk" is the part a reader can do something about when the
        // wait turns out to be permanent.
        Text(
            text = "Asking the desk for price history…",
            style = MaterialTheme.typography.labelSmall,
            color = Chalk500,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** One labelled strip, matching [RsiPane] and friends so nothing shifts on arrival. */
@Composable
private fun SkeletonPane(label: String, height: androidx.compose.ui.unit.Dp) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // The labels are real. They are known before the data is, and showing them
            // tells the reader what this screen is about to contain.
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ShimmerBlock(Modifier.width(48.dp).height(14.dp))
        }
        Spacer(Modifier.height(8.dp))
        ShimmerBlock(Modifier.fillMaxWidth().height(height))
    }
}
