package com.abhinavxt.newsforge.data

/**
 * Saving a story, and keeping a copy of it to read offline.
 *
 * One place, because three things save — the feed, a company's timeline and the button
 * on a notification — and a copy made by only some of them would leave the Saved list
 * readable offline or not depending on where the star was pressed.
 *
 * The flag is written first and on its own. The copy needs the network and can take
 * seconds; the star must not wait on it, and must not be lost if it fails.
 */
class SavedArticles(
    private val news: NewsRepository,
    private val reader: ReaderRepository,
) {

    suspend fun setSaved(articleId: String, saved: Boolean) {
        news.setSaved(articleId, saved)
        val link = news.linkOf(articleId) ?: return
        if (saved) reader.keepOfflineQuietly(link) else reader.dropOffline(link)
    }

    /**
     * Makes offline copies for saved stories that lack one.
     *
     * Run from the background sync: a story saved on the train, with no signal, gets its
     * copy at the next sync that has one. Bounded, so a long list saved before this
     * existed is worked through over several syncs rather than in one burst.
     */
    suspend fun backfill(limit: Int = BACKFILL_PER_RUN) {
        for (link in news.savedWithoutOfflineCopy(limit)) reader.keepOfflineQuietly(link)
    }

    private companion object {
        const val BACKFILL_PER_RUN = 5
    }
}
