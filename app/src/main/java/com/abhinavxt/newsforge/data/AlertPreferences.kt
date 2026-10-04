package com.abhinavxt.newsforge.data

import android.content.Context
import java.time.LocalTime
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
     * When alerting first looked at the store, or 0 before it has.
     *
     * Nothing stored before this is ever alerted on. Without it the first scan on a
     * fresh install — or the first after an update — would find a store full of
     * unread stories, every one of them new to the alerter.
     */
    var armedAt: Long
        get() = prefs.getLong(KEY_ARMED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_ARMED_AT, value).apply()

    /**
     * Thresholds come from the chosen sensitivity level.
     *
     * The raw numbers stay in the code rather than the UI: a score is meaningless to
     * anyone who has not read the ranker, and exposing it as a slider would invite
     * settings that quietly turn alerting off or into a firehose.
     */
    /** Whether alerts hold off overnight at all. On by default, as before it was a choice. */
    var quietEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUIET_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_QUIET_ENABLED, value).apply()

    /** Minutes after midnight, so a time survives without a format to parse. */
    var quietFrom: LocalTime
        get() = LocalTime.ofSecondOfDay(prefs.getInt(KEY_QUIET_FROM, DEFAULT_FROM) * 60L)
        set(value) = prefs.edit().putInt(KEY_QUIET_FROM, value.toSecondOfDay() / 60).apply()

    var quietUntil: LocalTime
        get() = LocalTime.ofSecondOfDay(prefs.getInt(KEY_QUIET_UNTIL, DEFAULT_UNTIL) * 60L)
        set(value) = prefs.edit().putInt(KEY_QUIET_UNTIL, value.toSecondOfDay() / 60).apply()

    /** One notification after quiet hours with what they held back. */
    var digestEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIGEST, true)
        set(value) = prefs.edit().putBoolean(KEY_DIGEST, value).apply()

    /** End of the last quiet period a digest was sent for, so one night gives one digest. */
    var lastDigestFor: Long
        get() = prefs.getLong(KEY_LAST_DIGEST, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_DIGEST, value).apply()

    fun settings(): AlertSettings {
        val level = sensitivity
        val from = quietFrom
        return AlertSettings().copy(
            enabled = enabled,
            minScore = level.minScore,
            maxPerSync = level.maxPerSync,
            quietFrom = from,
            // Equal ends mean "never quiet" to the policy, which is what off is.
            quietUntil = if (quietEnabled) quietUntil else from,
        )
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_ARMED_AT = "armedAt"
        const val KEY_QUIET_ENABLED = "quietEnabled"
        const val KEY_QUIET_FROM = "quietFrom"
        const val KEY_QUIET_UNTIL = "quietUntil"
        const val KEY_DIGEST = "digest"
        const val KEY_LAST_DIGEST = "lastDigestFor"
        const val DEFAULT_FROM = 22 * 60
        const val DEFAULT_UNTIL = 6 * 60 + 30
    }
}
