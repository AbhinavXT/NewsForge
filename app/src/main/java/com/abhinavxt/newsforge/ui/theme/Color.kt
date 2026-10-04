package com.abhinavxt.newsforge.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// A low-chroma surface set, light or dark. A news feed read at 08:30 and again at 09:20 should not
// be the brightest thing on the screen, and colour here is reserved for the category
// accent — if the chrome also competes for attention the accents stop meaning anything.
//
// The steps are elevation, not decoration: the page sits on Ink900, cards on Ink800,
// anything raised off a card (chips, the quote panel, the nav bar) on Ink700, and Ink600 is
// the pressed or selected version of that.
//
// These follow the selected [AppTheme]. They are getters over snapshot state rather than
// constants, so every existing reference — composables and chart draw lambdas alike —
// repaints when the theme changes.
val Ink950: Color get() = ThemeState.palette.ink950
val Ink900: Color get() = ThemeState.palette.ink900
val Ink800: Color get() = ThemeState.palette.ink800
val Ink700: Color get() = ThemeState.palette.ink700
val Ink600: Color get() = ThemeState.palette.ink600
val Ink500: Color get() = ThemeState.palette.ink500

/** Hairlines. Used where two surfaces of the same tier meet and need a seam. */
val Hairline: Color get() = ThemeState.palette.hairline

val Chalk100: Color get() = ThemeState.palette.chalk100
val Chalk300: Color get() = ThemeState.palette.chalk300
val Chalk500: Color get() = ThemeState.palette.chalk500

val Accent: Color get() = ThemeState.palette.accent
val AccentDim: Color get() = ThemeState.palette.accentDim
/** The far end of the brand gradient. Only ever drawn blended with [Accent]. */
val AccentViolet: Color get() = ThemeState.palette.accentEnd

/**
 * The brand mark, as a brush: the lead story's edge, range fills, the heat bars.
 *
 * Used sparingly and only on chrome that means "this is NewsForge" or "this is first" —
 * never on anything that also has to carry a category, because a gradient beside a
 * category accent reads as a fifth category.
 */
val BrandGradient: Brush get() = Brush.linearGradient(listOf(Accent, AccentViolet))

// Category accents. Chosen so the four that matter most intraday — regulatory, M&A,
// order wins, results — are separable at a glance in a dense list, including for the
// common red/green colour deficiencies, which is why nothing here relies on red vs green
// alone to carry meaning.
//
// Each has a deeper shade for light palettes. The bright ones are tuned for dark surfaces,
// and on white the yellow and cyan fall to around 2:1 contrast — legible as a dot, not as
// a label. Same hue, same meaning; only the depth changes.
val CatRegulatory: Color get() = shade(0xFFF0883E, 0xFFBC5A12)
val CatDeal: Color get() = shade(0xFFA371F7, 0xFF7447C2)
val CatOrder: Color get() = shade(0xFF3FB950, 0xFF1F7F36)
val CatResults: Color get() = shade(0xFF58A6FF, 0xFF2766B8)
val CatPolicy: Color get() = shade(0xFFD29922, 0xFF8F6400)
val CatGlobal: Color get() = shade(0xFF39C5CF, 0xFF117A82)
/** Broker calls — pink, distinct from the blue that marks reported numbers. */
val CatViews: Color get() = shade(0xFFDB61A2, 0xFFAA3A77)
val CatNeutral: Color get() = shade(0xFF6E7681, 0xFF6A717C)

// Price moves. Green and red are what every terminal and broker app in the country uses,
// so anything else would be actively confusing here — but the sign is always printed
// alongside, because this is the one place in the app where red against green carries
// meaning and the palette above deliberately avoids leaning on that pair.
val QuoteUp: Color get() = shade(0xFF3FB950, 0xFF1F7F36)
val QuoteDown: Color get() = shade(0xFFE5534B, 0xFFC4262E)

private fun shade(dark: Long, light: Long): Color =
    Color(if (ThemeState.palette.isLight) light else dark)
