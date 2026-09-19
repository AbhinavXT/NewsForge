package com.abhinavxt.newsforge.ui.chart

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.abhinavxt.newsforge.ui.theme.Ink500

/**
 * A block standing in for something not loaded yet.
 *
 * A sweep rather than a pulse, and slow. The point of a placeholder is to say "this is
 * coming" without competing with the content that arrives next to it, and anything
 * brighter or faster turns a half-drawn screen into something flickering at the reader
 * while they try to read the half that is finished.
 */
@Composable
fun ShimmerBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "shimmer-sweep",
    )

    Box(
        modifier
            .clip(shape)
            // Read inside the draw lambda on purpose: `offset` changes every frame, and
            // reading it here invalidates only the draw phase. Computing the brush up in
            // composition instead would recompose this subtree sixty times a second to
            // produce the same layout.
            .drawBehind {
                val width = size.width.coerceAtLeast(1f)
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(
                            Ink500.copy(alpha = 0.35f),
                            Ink500.copy(alpha = 0.70f),
                            Ink500.copy(alpha = 0.35f),
                        ),
                        start = Offset(offset * width - width, 0f),
                        end = Offset(offset * width, 0f),
                    )
                )
            }
    )
}
