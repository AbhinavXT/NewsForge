package com.abhinavxt.newsforge.data

import android.content.Context
import com.abhinavxt.newsforge.ui.theme.AppTheme
import com.abhinavxt.newsforge.ui.theme.ThemeMode

/**
 * Which theme the reader picked, and whether it is light, dark or follows the phone.
 *
 * Stored by enum name, so reordering or adding themes never moves anyone onto a different
 * one, and a name a later build no longer has falls back to the default.
 */
class AppearancePreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    var theme: AppTheme
        get() = AppTheme.parse(prefs.getString(KEY_THEME, null))
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var mode: ThemeMode
        get() = ThemeMode.parse(prefs.getString(KEY_MODE, null))
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_MODE = "mode"
    }
}
