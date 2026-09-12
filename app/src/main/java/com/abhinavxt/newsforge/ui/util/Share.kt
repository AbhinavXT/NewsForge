package com.abhinavxt.newsforge.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log

object Share {

    /**
     * Opens the system share sheet.
     *
     * `createChooser` rather than the bare intent so the target is always chosen fresh —
     * a news link goes to a different place each time, and a remembered default here
     * would be wrong more often than right.
     */
    fun text(context: Context, body: String, subject: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        try {
            context.startActivity(Intent.createChooser(intent, null))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app can handle a share", e)
        }
    }

    private const val TAG = "Share"
}
