package com.abhinavxt.newsforge.data

import com.abhinavxt.newsforge.core.model.Desk

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

    /**
     * One-time corrections already applied.
     *
     * Separate from [everSeeded] because they answer different questions. That one says
     * which feeds this install has been shown; this says which shipped mistakes have
     * been put right. Sharing a key would mean a correction could never touch a feed
     * that had been seeded, which is the only kind of feed a correction is ever for.
     */
    var corrections: Set<String>
        get() = prefs.getStringSet(KEY_CORRECTIONS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_CORRECTIONS, value.toSet()).apply()

    /**
     * When each desk last finished a refresh, or 0 if never.
     *
     * Persisted rather than held in the repository, because the background worker decides
     * from it whether the World feeds are due, and WorkManager is free to run that worker
     * in a process that has never seen the last refresh.
     */
    fun lastSyncedAt(desk: Desk): Long = prefs.getLong(KEY_SYNCED_PREFIX + desk.name, 0L)

    fun setLastSyncedAt(desk: Desk, millis: Long) =
        prefs.edit().putLong(KEY_SYNCED_PREFIX + desk.name, millis).apply()

    private companion object {
        const val KEY_SYNCED_PREFIX = "last_synced_"
        const val KEY_SEEDED = "ever_seeded"
        const val KEY_CORRECTIONS = "corrections_applied"
    }
}
