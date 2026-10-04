package com.abhinavxt.newsforge.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent

/** Opens article links. */
object Links {

    /**
     * Custom Tabs rather than an in-app WebView.
     *
     * Publisher sites are heavy and paywalled; a WebView would mean owning cookies,
     * consent dialogs and login state for a dozen news sites. Custom Tabs hands all of
     * that to the user's browser, where they are already signed in, and comes back in one
     * gesture. Falls back to a plain view intent when no browser supports it.
     */
    fun open(context: Context, url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (inner: ActivityNotFoundException) {
                Log.w(TAG, "No app can open $url", inner)
            }
        }
    }

    private const val TAG = "Links"
}
