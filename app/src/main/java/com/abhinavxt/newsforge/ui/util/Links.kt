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

    /**
     * Whether [url] names a file rather than a page — a PDF, a slide deck, a filing.
     *
     * The reader extracts HTML and has nothing to show for these, so callers send them
     * straight to [open]. Judged by extension, which is free; links that hide their type
     * behind a script URL are caught later by the reader's content-type check.
     */
    fun isFile(url: String): Boolean {
        val path = runCatching { Uri.parse(url) }.getOrNull()?.path ?: return false
        val extension = path.substringAfterLast('/').substringAfterLast('.', "")
        return extension.lowercase() in FILE_EXTENSIONS
    }

    private val FILE_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "csv", "ppt", "pptx", "odt", "ods", "odp",
        "rtf", "txt", "xml", "json", "zip", "rar", "7z", "gz",
        "jpg", "jpeg", "png", "gif", "webp", "svg",
        "mp3", "m4a", "wav", "mp4", "m4v", "mov", "webm",
    )

    private const val TAG = "Links"
}
