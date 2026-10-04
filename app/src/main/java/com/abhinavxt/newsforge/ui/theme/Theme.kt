package com.abhinavxt.newsforge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color as AndroidColor
import androidx.compose.ui.unit.dp

private fun colorsFor(p: Palette): ColorScheme {
    val base = if (p.isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = p.accent,
        onPrimary = p.onAccent,
        // Selection is a soft wash of the accent with accent-coloured text, not a solid
        // fill — see NfChip. A screen of solid-accent chips is what made a theme feel like
        // it was all one colour.
        primaryContainer = p.accentDim,
        onPrimaryContainer = p.onAccentDim,
        secondary = p.chalk300,
        onSecondary = p.ink900,
        background = p.ink900,
        onBackground = p.chalk100,
        surface = p.ink800,
        onSurface = p.chalk100,
        surfaceVariant = p.ink700,
        onSurfaceVariant = p.chalk300,
        // M3 reaches for these on sheets, dialogs and menus. Left unset they fall back to a
        // tonal purple computed from `primary`, which is what made the old dialogs look like
        // they belonged to another app.
        surfaceContainerLowest = p.ink950,
        surfaceContainerLow = p.ink800,
        surfaceContainer = p.ink800,
        surfaceContainerHigh = p.ink700,
        surfaceContainerHighest = p.ink600,
        surfaceBright = p.ink600,
        surfaceDim = p.ink900,
        outline = p.ink500,
        outlineVariant = p.hairline,
        error = CatRegulatory,
        onError = if (p.isLight) Color.White else p.ink900,
    )
}

/**
 * Rounded, and more so as things get bigger.
 *
 * A chip at 10dp, a card at 16, a sheet at 28. One radius everywhere makes small things
 * look like pills and big things look like boxes; scaling it keeps the curvature reading
 * as the same family at every size.
 */
private val NewsForgeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Light or dark as the reader chose in Settings, dynamic colour off on purpose. The
 * palette comes from [ThemeState] — one of a fixed set of [AppTheme]s, in the brightness
 * [ThemeMode] picks.
 *
 * Category accents are load-bearing here — they are how you tell a regulatory story from
 * an order win without reading — and Material You would rewrite the palette per device
 * wallpaper, which would leave those accents fighting whatever the system picked.
 */
@Composable
fun NewsForgeTheme(content: @Composable () -> Unit) {
    // Fed into the state rather than read where it is needed, because the colour tokens
    // are read from chart draw lambdas, which cannot read a composition value.
    val systemDark = isSystemInDarkTheme()
    SideEffect { ThemeState.systemDark = systemDark }

    val palette = ThemeState.palette
    val colors = remember(palette) { colorsFor(palette) }
    SystemBars(light = palette.isLight)
    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        shapes = NewsForgeShapes,
        content = content,
    )
}

/**
 * Status and navigation bar icons that match the app rather than the phone.
 *
 * Edge-to-edge picks icon colour from the system's dark mode. With the app light and the
 * phone dark that gives white clock and battery icons on a white page, so the app says
 * which it is instead.
 */
@Composable
private fun SystemBars(light: Boolean) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    LaunchedEffect(light) {
        val style = if (light) {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
