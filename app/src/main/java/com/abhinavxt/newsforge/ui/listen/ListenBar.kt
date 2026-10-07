package com.abhinavxt.newsforge.ui.listen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.listen.NarratorState
import com.abhinavxt.newsforge.ui.components.IconCircleButton
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.Hairline

/**
 * The mini player for listen mode, sitting just above the tab dock.
 *
 * Above the dock rather than inside a screen, because listening outlives the screen it
 * started on: start the front page on World, go and look at a chart, and the controls
 * should still be under your thumb.
 */
@Composable
fun ListenBar(
    state: NarratorState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.active || !state.available,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 14.dp, end = 14.dp, top = 6.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, Hairline, RoundedCornerShape(18.dp))
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Column(Modifier.weight(1f).padding(end = 6.dp)) {
                if (!state.available) {
                    Text(
                        text = "No text-to-speech engine on this device",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Install one, such as Speech Services by Google",
                        style = MaterialTheme.typography.labelSmall,
                        color = Chalk500,
                    )
                } else {
                    Text(
                        text = if (state.items.size > 1) {
                            "LISTENING · ${state.index + 1} OF ${state.items.size}"
                        } else {
                            "LISTENING"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = state.current?.title.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.available) {
                IconCircleButton(
                    icon = R.drawable.ic_chevron_right,
                    contentDescription = "Previous story",
                    onClick = onPrevious,
                    size = 36.dp,
                    modifier = Modifier.rotate(180f),
                )
                IconCircleButton(
                    icon = if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                    contentDescription = if (state.playing) "Pause" else "Play",
                    onClick = onToggle,
                    size = 40.dp,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    container = MaterialTheme.colorScheme.primary,
                )
                IconCircleButton(
                    icon = R.drawable.ic_chevron_right,
                    contentDescription = "Next story",
                    onClick = onNext,
                    enabled = state.index < state.items.lastIndex,
                    size = 36.dp,
                )
            }
            IconCircleButton(
                icon = R.drawable.ic_close,
                contentDescription = "Stop listening",
                onClick = onStop,
                size = 36.dp,
            )
        }
    }
}
