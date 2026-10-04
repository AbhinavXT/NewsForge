package com.abhinavxt.newsforge.ui.util

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri

/**
 * Whether the live watch is allowed to start itself.
 *
 * From Android 12 an app in the background cannot start a foreground service, and the
 * watch's 09:00 start is by definition in the background. Of the platform's exemptions,
 * the one a person can grant is turning battery optimisation off for the app — without
 * it the watch only runs on days the app happens to be open when a segment begins.
 */
object BackgroundStart {

    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Asks for the exemption in one dialog, or opens the full list when that is refused.
     *
     * The direct request is the one Play restricts to apps whose core function needs it.
     * This app is not distributed there; if it ever is, drop to the list alone.
     */
    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData("package:${context.packageName}".toUri())
        try {
            context.startActivity(direct)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (inner: ActivityNotFoundException) {
                Log.w(TAG, "No battery optimisation settings on this device", inner)
            }
        }
    }

    private const val TAG = "BackgroundStart"
}
