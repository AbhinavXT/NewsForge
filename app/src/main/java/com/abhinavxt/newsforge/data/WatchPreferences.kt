package com.abhinavxt.newsforge.data

import android.content.Context

/**
 * Whether the live watch may run.
 *
 * Off by default. It costs a permanent notification for six and a half hours a day, which
 * is a reasonable trade for someone trading intraday and an unpleasant surprise for
 * someone who installed a news reader — so it is opted into, never assumed.
 */
class WatchPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("watch", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * When the system last refused to start a segment, or 0 when it has not.
     *
     * Recorded so the setting can say the watch is not actually running. A refusal is
     * otherwise visible only as a notification that never appeared.
     */
    var lastRefusedAt: Long
        get() = prefs.getLong(KEY_LAST_REFUSED, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_REFUSED, value).apply()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_REFUSED = "lastRefusedAt"
    }
}
