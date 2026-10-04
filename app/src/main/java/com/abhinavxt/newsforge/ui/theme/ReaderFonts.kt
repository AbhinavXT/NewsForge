@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.abhinavxt.newsforge.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.reader.ReaderFont

/**
 * The reader's two typefaces, shipped with the app rather than asked of the phone.
 *
 * `FontFamily.Serif` and `FontFamily.SansSerif` are whatever the device maps those names
 * to, and on many phones — any with a manufacturer or user-chosen system font — both come
 * back as the same face, so the reader's font switch did nothing at all. Bundled faces look
 * the same everywhere: Literata, which Google drew for long-form reading in Play Books, and
 * Inter. Both are variable fonts under the SIL Open Font License (see assets/licenses).
 *
 * One entry per weight the reader actually sets, each pinning the weight axis, so a
 * SemiBold heading draws at 600 rather than being synthesised from the regular.
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

private val ReaderSerif: FontFamily by lazy { variable(R.font.literata, 400, 500, 600, 700) }

private val ReaderSans: FontFamily by lazy { variable(R.font.inter, 400, 500, 600, 700) }

val ReaderFont.family: FontFamily
    get() = when (this) {
        ReaderFont.SANS -> ReaderSans
        ReaderFont.SERIF -> ReaderSerif
    }
