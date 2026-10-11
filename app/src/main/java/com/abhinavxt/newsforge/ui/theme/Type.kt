@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.abhinavxt.newsforge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.abhinavxt.newsforge.R

/**
 * A scale for scanning, not for reading.
 *
 * Two bundled faces, the same pair the OrderBlock web app uses so the two read as one
 * family: Inter Tight for words — a narrower Inter, which buys back a line on long Indian
 * company names — and JetBrains Mono for anything that is a figure, a ticker, a time or an
 * index. Mono figures line up down a column without needing tabular-nums from a
 * proportional face, and they mark "this is data" before you read it.
 *
 * Material's defaults are tuned for prose. Everything here is tighter and a little smaller,
 * with negative tracking on the large sizes, and medium weight as the floor so 11sp labels
 * stay legible against a dark surface.
 */
private fun variable(resource: Int, vararg weights: Int): FontFamily = FontFamily(
    weights.map { weight ->
        Font(
            resId = resource,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    }
)

/** Inter Tight: every word in the chrome. */
val Sans: FontFamily = variable(R.font.inter_tight, 400, 500, 600, 700, 800)

/** JetBrains Mono: prices, changes, tickers, times, counts and index tags. */
val Mono: FontFamily = variable(R.font.jetbrains_mono, 400, 500, 600, 700)

val Typography = Typography(
    // Screen titles. Bold and tight, the way a masthead is: read once on arrival, then it
    // should get out of the way.
    headlineMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.6).sp,
    ),
    // Prices on the symbol screen and the quote panel's last traded price.
    headlineSmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.9).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 14.5.sp,
        lineHeight = 19.sp,
        letterSpacing = (-0.15).sp,
    ),
    // Story headlines. Three of these stack in one card, so the line height decides how
    // many cards fit on screen.
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.5.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.2).sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.05).sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    // The workhorse: bylines, category tags, tickers, chip counts.
    labelSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp,
    ),
)

/**
 * The uppercase, widely tracked caption over a section or beside a figure: "RESISTANCE 1",
 * "ZONE", "TOP STORIES". Quiet by colour and loud by shape, so it orders a screen without
 * adding colour to it.
 */
val EyebrowStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Bold,
    fontSize = 10.5.sp,
    lineHeight = 13.sp,
    letterSpacing = 1.3.sp,
)

/** A figure: a price, a change, a count. */
val NumStyle = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Medium,
    fontSize = 12.5.sp,
    lineHeight = 16.sp,
    letterSpacing = (-0.25).sp,
)
