package com.abhinavxt.newsforge.data

import android.content.Context
import com.abhinavxt.newsforge.core.mute.AlertSensitivity
import com.abhinavxt.newsforge.core.notify.AlertSettings

/**
 * Alert settings, on SharedPreferences.
 *
 * Not DataStore: this is four scalars read once per sync from a background worker, which
 * is exactly the case SharedPreferences handles well, and DataStore would add a
 * dependency and a coroutine boundary to save nothing.
 */
class AlertPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var sensitivity: AlertSensitivity
        get() = AlertSensitivity.parse(prefs.getString(KEY_SENSITIVITY, null))
        set(value) = prefs.edit().putString(KEY_SENSITIVITY, value.name).apply()

    /**
     * Thresholds come from the chosen sensitivity level.
     *
     * The raw numbers stay in the code rather than the UI: a score is meaningless to
     * anyone who has not read the ranker, and exposing it as a slider would invite
     * settings that quietly turn alerting off or into a firehose.
     */
    fun settings(): AlertSettings {
        val level = sensitivity
        return AlertSettings().copy(
            enabled = enabled,
            minScore = level.minScore,
            maxPerSync = level.maxPerSync,
        )
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_SENSITIVITY = "sensitivity"
    }
}
