package com.abhinavxt.newsforge.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.core.summary.StoryBrief
import com.abhinavxt.newsforge.ui.theme.Chalk500
import kotlinx.coroutines.CancellationException

/**
 * Loads a story's cross-outlet brief: (clusterId, headline) to the brief, or null.
 *
 * A composition local rather than a parameter because the brief appears on three
 * screens several layers below the root, and threading a loader through every signature
 * in between would be plumbing for one block. The default loads nothing, so a preview or
 * a test without a store simply shows no brief.
 */
val LocalStoryBriefs = staticCompositionLocalOf<suspend (String, String) -> StoryBrief?> {
    { _, _ -> null }
}

/**
 * "Across 4 outlets" and what they agree on, under a story.
 *
 * Draws nothing until the brief has loaded, and nothing at all for a story only one
 * outlet carried — the summary already shown is that outlet's whole account.
 */
@Composable
fun AcrossOutlets(clusterId: String, title: String, modifier: Modifier = Modifier) {
    val load = LocalStoryBriefs.current
    val brief by produceState<StoryBrief?>(null, clusterId) {
        value = try {
            load(clusterId, title)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A brief is an extra. Failing to build one must not disturb the story.
            null
        }
    }
    AnimatedVisibility(
        visible = brief?.points?.isNotEmpty() == true,
        enter = fadeIn() + expandVertically(),
        modifier = modifier,
    ) {
        val current = brief ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = if (current.outlets > 1) {
                    "ACROSS ${current.outlets} OUTLETS"
                } else {
                    "FROM THE COVERAGE"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Chalk500,
            )
            for (point in current.points) {
                Row(Modifier.padding(top = 8.dp)) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Chalk500,
                        modifier = Modifier.width(14.dp),
                    )
                    Text(
                        text = point,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
