package com.abhinavxt.newsforge.core.reader

/** Typeface for article text. */
enum class ReaderFont(val label: String) { SANS("Sans"), SERIF("Serif") }

/** Space between lines, as a multiple of the text size. */
enum class ReaderSpacing(val label: String, val lineHeight: Float) {
    COMPACT("Compact", 1.4f),
    NORMAL("Normal", 1.6f),
    RELAXED("Relaxed", 1.8f),
}

/**
 * How the reader sets its text.
 *
 * Steps rather than a free slider: five sizes cover everyone from arm's length to reading
 * glasses, and a continuous value makes "a little bigger" a fiddly drag instead of a tap.
 */
data class ReaderSettings(
    val sizeStep: Int = DEFAULT_STEP,
    val font: ReaderFont = ReaderFont.SANS,
    val spacing: ReaderSpacing = ReaderSpacing.NORMAL,
) {
    val textSizeSp: Float get() = SIZES_SP[sizeStep.coerceIn(SIZES_SP.indices)]
    val canGrow: Boolean get() = sizeStep < SIZES_SP.lastIndex
    val canShrink: Boolean get() = sizeStep > 0

    fun grow(): ReaderSettings = copy(sizeStep = (sizeStep + 1).coerceAtMost(SIZES_SP.lastIndex))
    fun shrink(): ReaderSettings = copy(sizeStep = (sizeStep - 1).coerceAtLeast(0))

    companion object {
        val SIZES_SP = listOf(15f, 16f, 17f, 19f, 21f, 24f)
        const val DEFAULT_STEP = 2
    }
}
