package com.abhinavxt.newsforge.data

import android.content.Context

/**
 * Bridge configuration.
 *
 * On SharedPreferences rather than in the database, and separate from every other store in
 * the app: this is credentials and an endpoint, not data.
 */
class DeskPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("desk", Context.MODE_PRIVATE)

    var server: String
        get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER).orEmpty().ifEmpty { DEFAULT_SERVER }
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim()).apply()

    var topic: String
        get() = prefs.getString(KEY_TOPIC, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOPIC, value.trim()).apply()

    /** Optional ntfy access token, for a private or self-hosted server. */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** The bridge is off until a topic is set; there is nothing to poll without one. */
    val isConfigured: Boolean get() = topic.isNotBlank()

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
        private const val KEY_SERVER = "server"
        private const val KEY_TOPIC = "topic"
        private const val KEY_TOKEN = "token"
    }
}
