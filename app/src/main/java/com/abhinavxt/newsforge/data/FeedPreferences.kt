package com.abhinavxt.newsforge.data

import android.content.Context

/**
 * Remembers which built-in feeds this install has been offered.
 *
 * SharedPreferences rather than a table: it is one set of short strings, read once per
 * refresh, and a schema migration to store it would be more machinery than the fact
 * deserves.
 */
class FeedPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("feeds", Context.MODE_PRIVATE)

    var everSeeded: Set<String>
        // getStringSet hands back a live instance the docs forbid mutating, so copy it.
        get() = prefs.getStringSet(KEY_SEEDED, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SEEDED, value.toSet()).apply()

    private companion object {
        const val KEY_SEEDED = "ever_seeded"
    }
}
