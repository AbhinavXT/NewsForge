package com.abhinavxt.newsforge.ui.theme

import androidx.compose.ui.graphics.Color

// A dark, low-chroma surface set. A news feed read at 08:30 and again at 09:20 should not
// be the brightest thing on the screen, and colour here is reserved for the category
// accent — if the chrome also competes for attention the accents stop meaning anything.
val Ink900 = Color(0xFF0D1117)
val Ink800 = Color(0xFF11161D)
val Ink700 = Color(0xFF161B22)
val Ink600 = Color(0xFF1F2630)
val Ink500 = Color(0xFF30363D)

val Chalk100 = Color(0xFFE6EDF3)
val Chalk300 = Color(0xFFB0BAC5)
val Chalk500 = Color(0xFF7D8590)

val Accent = Color(0xFF4C8DFF)
val AccentDim = Color(0xFF1B3A66)

// Category accents. Chosen so the four that matter most intraday — regulatory, M&A,
// order wins, results — are separable at a glance in a dense list, including for the
// common red/green colour deficiencies, which is why nothing here relies on red vs green
// alone to carry meaning.
val CatRegulatory = Color(0xFFF0883E)
val CatDeal = Color(0xFFA371F7)
val CatOrder = Color(0xFF3FB950)
val CatResults = Color(0xFF58A6FF)
val CatPolicy = Color(0xFFD29922)
val CatGlobal = Color(0xFF39C5CF)
/** Broker calls — pink, distinct from the blue that marks reported numbers. */
val CatViews = Color(0xFFDB61A2)
val CatNeutral = Color(0xFF6E7681)

// Price moves. Green and red are what every terminal and broker app in the country uses,
// so anything else would be actively confusing here — but the sign is always printed
// alongside, because this is the one place in the app where red against green carries
// meaning and the palette above deliberately avoids leaning on that pair.
val QuoteUp = Color(0xFF3FB950)
val QuoteDown = Color(0xFFE5534B)
