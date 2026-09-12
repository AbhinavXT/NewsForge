package com.abhinavxt.newsforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NewsForgeColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink900,
    primaryContainer = AccentDim,
    onPrimaryContainer = Chalk100,
    secondary = Chalk300,
    onSecondary = Ink900,
    background = Ink900,
    onBackground = Chalk100,
    surface = Ink800,
    onSurface = Chalk100,
    surfaceVariant = Ink700,
    onSurfaceVariant = Chalk300,
    outline = Ink500,
    outlineVariant = Ink600,
    error = CatRegulatory,
    onError = Ink900,
)

/**
 * Dark only, and dynamic colour off on purpose.
 *
 * Category accents are load-bearing here — they are how you tell a regulatory story from
 * an order win without reading — and Material You would rewrite the palette per device
 * wallpaper, which would leave those accents fighting whatever the system picked.
 */
@Composable
fun NewsForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NewsForgeColors,
        typography = Typography,
        content = content,
    )
}
