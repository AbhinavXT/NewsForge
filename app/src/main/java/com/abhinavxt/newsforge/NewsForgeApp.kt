package com.abhinavxt.newsforge

import android.app.Application
import com.abhinavxt.newsforge.di.AppContainer
import com.abhinavxt.newsforge.work.MarketWatchWorker
import com.abhinavxt.newsforge.work.SyncScheduler

class NewsForgeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Both are cheap: enqueueUniquePeriodicWork with KEEP is a no-op once scheduled,
        // and the one-off is what populates an empty store on first launch.
        // Registered at startup so the channels appear in system settings before the
        // first alert, and the user can pre-configure them.
        container.notifier.ensureChannels()
        SyncScheduler.ensureScheduled(this)
        SyncScheduler.syncNow(this)
        // Re-armed on every launch because the next start time is recomputed each
        // day; cancelled outright when switched off so nothing lingers.
        if (container.watchPreferences.enabled) {
            MarketWatchWorker.reschedule(this)
        } else {
            MarketWatchWorker.cancel(this)
        }
    }
}
