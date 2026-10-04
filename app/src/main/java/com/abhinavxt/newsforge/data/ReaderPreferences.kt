package com.abhinavxt.newsforge.data

import android.content.Context
import com.abhinavxt.newsforge.core.reader.ReaderFont
import com.abhinavxt.newsforge.core.reader.ReaderSettings
import com.abhinavxt.newsforge.core.reader.ReaderSpacing

/** How the reader sets article text. Enums by name, for the same reason as the theme. */
class ReaderPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var settings: ReaderSettings
        get() = ReaderSettings(
            sizeStep = prefs.getInt(KEY_SIZE, ReaderSettings.DEFAULT_STEP),
            font = ReaderFont.entries.firstOrNull { it.name == prefs.getString(KEY_FONT, null) }
                ?: ReaderFont.SANS,
            spacing = ReaderSpacing.entries
                .firstOrNull { it.name == prefs.getString(KEY_SPACING, null) }
                ?: ReaderSpacing.NORMAL,
        )
        set(value) = prefs.edit()
            .putInt(KEY_SIZE, value.sizeStep)
            .putString(KEY_FONT, value.font.name)
            .putString(KEY_SPACING, value.spacing.name)
            .apply()

    private companion object {
        const val KEY_SIZE = "sizeStep"
        const val KEY_FONT = "font"
        const val KEY_SPACING = "spacing"
    }
}
