package com.abhinavxt.newsforge.data

import com.abhinavxt.newsforge.core.reader.ArticleExtractor
import com.abhinavxt.newsforge.core.reader.GoogleNews
import com.abhinavxt.newsforge.core.reader.ReaderArticle
import com.abhinavxt.newsforge.core.reader.ReaderCodec
import com.abhinavxt.newsforge.data.db.OfflineArticleEntity
import com.abhinavxt.newsforge.data.db.ReaderDao
import com.abhinavxt.newsforge.data.db.ReadingPositionEntity
import com.abhinavxt.newsforge.data.net.FeedFetcher
import com.abhinavxt.newsforge.data.net.FetchResult
import com.abhinavxt.newsforge.data.net.JsonLd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream

/** What came of asking for an article in reader form. */
sealed interface ReaderResult {
    /** @param offline true when this came from the saved copy rather than the network. */
    data class Ready(val article: ReaderArticle, val offline: Boolean = false) : ReaderResult

    /**
     * The page loaded but held no recognisable article — paywall, consent wall, script shell.
     *
     * @param url where the browser should go: the publisher's page, when a redirect
     *   service had to be seen through to reach it.
     */
    data class NotReadable(val url: String) : ReaderResult

    /**
     * The link is a file, not a page — a PDF or a deck behind a URL that did not say so.
     * Nothing to extract; the screen hands [url] to the browser.
     */
    data class NotAPage(val url: String) : ReaderResult

    data class Failed(val message: String) : ReaderResult
}

/** Where the reader left an article. */
data class ReadingPosition(val item: Int, val offset: Int, val fraction: Float)

/**
 * Fetches a publisher page and reduces it to text.
 *
 * Three tiers, cheapest first: the last few articles in memory, so going back to the feed
 * and into the same story does not refetch it; then saved articles, kept whole in the
 * database so they read with no connection; then the network.
 */
class ReaderRepository(
    private val fetcher: FeedFetcher,
    private val dao: ReaderDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val cache = object : LinkedHashMap<String, ReaderArticle>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReaderArticle>) =
            size > CACHE_SIZE
    }

    /** Google News links resolved this process, so a reopened story skips the round trip. */
    private val resolved = HashMap<String, String>()

    suspend fun load(url: String): ReaderResult {
        synchronized(cache) { cache[url] }?.let { return ReaderResult.Ready(it) }
        dao.offline(url)?.let { saved ->
            return ReaderResult.Ready(saved.toArticle(), offline = true)
        }

        val target = resolve(url)
            ?: return ReaderResult.Failed("Couldn't find the publisher's page")
        val result = fetcher.fetch(target, validators = null, accept = ACCEPT_HTML)
        val contentType = when (result) {
            is FetchResult.Success -> result.contentType
            is FetchResult.Failure -> result.contentType
            is FetchResult.NotModified -> null
        }
        // An undeclared type is given the benefit of the doubt: plenty of pages omit it.
        if (contentType != null && "html" !in contentType.lowercase()) {
            return ReaderResult.NotAPage(target)
        }
        val bytes = when (result) {
            is FetchResult.Success -> result.bytes
            is FetchResult.Failure -> return ReaderResult.Failed(
                if (result.status > 0) "The site answered ${result.status}" else result.message
            )
            // No validators were sent, so a 304 is the server misbehaving.
            is FetchResult.NotModified -> return ReaderResult.Failed("The site sent no page")
        }

        val article = withContext(Dispatchers.Default) {
            // From bytes, with no charset given, so jsoup reads the page's own
            // <meta charset> rather than trusting a header that publishers get wrong.
            val document = Jsoup.parse(ByteArrayInputStream(bytes), null, target)
            // Read before extraction, which strips scripts along with the rest.
            val structuredBody = JsonLd.articleBody(ArticleExtractor.structuredData(document))
            ArticleExtractor.extract(document, target, structuredBody)
        } ?: return ReaderResult.NotReadable(target)

        synchronized(cache) { cache[url] = article }
        return ReaderResult.Ready(article)
    }

    /**
     * Stores [url]'s article for reading offline.
     *
     * @return false when it could not be fetched or reduced, which leaves the story saved
     *   but readable only online — still worth having, and not worth an error.
     */
    suspend fun keepOffline(url: String): Boolean {
        val article = (load(url) as? ReaderResult.Ready)?.article ?: return false
        dao.keepOffline(
            OfflineArticleEntity(
                url = url,
                resolvedUrl = article.url,
                title = article.title,
                siteName = article.siteName,
                byline = article.byline,
                leadImage = article.leadImage,
                blocks = ReaderCodec.encode(article.blocks),
                savedAt = clock(),
            )
        )
        return true
    }

    /** Never throws: offline copies are a convenience on top of saving, not part of it. */
    suspend fun keepOfflineQuietly(url: String) {
        try {
            keepOffline(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun dropOffline(url: String) = dao.dropOffline(url)

    fun offlineUrls(): Flow<Set<String>> = dao.observeOfflineUrls().map { it.toSet() }

    suspend fun position(url: String): ReadingPosition? =
        dao.position(url)?.let { ReadingPosition(it.item, it.offset, it.fraction) }

    suspend fun savePosition(url: String, position: ReadingPosition) =
        dao.savePosition(
            ReadingPositionEntity(
                url = url,
                item = position.item,
                offset = position.offset,
                fraction = position.fraction.coerceIn(0f, 1f),
                updatedAt = clock(),
            )
        )

    suspend fun prune() = dao.prunePositions(clock() - PROGRESS_WINDOW_MS)

    /**
     * The page to fetch for [url]: itself, or the publisher's page behind a Google News link.
     *
     * Null when a Google News link could not be seen through. Fetching Google's page
     * instead would only produce "can't be shown" with Google's address on the browser
     * button, which is the one place the reader should not be sent.
     */
    private suspend fun resolve(url: String): String? {
        if (!GoogleNews.isArticleLink(url)) return url
        synchronized(resolved) { resolved[url] }?.let { return it }

        val page = fetcher.fetch(url, validators = null, accept = ACCEPT_HTML)
            as? FetchResult.Success ?: return null
        val tokens = GoogleNews.tokensIn(page.bytes.toString(Charsets.UTF_8)) ?: return null
        val reply = fetcher.fetch(
            GoogleNews.DECODE_URL,
            validators = null,
            body = GoogleNews.requestBody(tokens),
            bodyType = GoogleNews.FORM_CONTENT_TYPE,
            accept = "*/*",
        ) as? FetchResult.Success ?: return null
        val target = GoogleNews.urlIn(reply.bytes.toString(Charsets.UTF_8)) ?: return null
        synchronized(resolved) { resolved[url] = target }
        return target
    }

    private fun OfflineArticleEntity.toArticle() = ReaderArticle(
        url = resolvedUrl,
        title = title,
        siteName = siteName,
        byline = byline,
        blocks = ReaderCodec.decode(blocks),
        leadImage = leadImage,
    )

    private companion object {
        const val CACHE_SIZE = 20
        const val ACCEPT_HTML = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5"

        /** Long enough to come back to a story the next morning; a month covers it. */
        const val PROGRESS_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    }
}
