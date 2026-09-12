package com.abhinavxt.newsforge.data.ingest

import com.abhinavxt.newsforge.core.dedupe.Headline
import com.abhinavxt.newsforge.core.dedupe.UrlCanonicalizer
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.FeedSource
import com.abhinavxt.newsforge.core.model.ParsedItem
import com.abhinavxt.newsforge.core.tag.Categorizer
import com.abhinavxt.newsforge.core.tag.SeedSymbols
import com.abhinavxt.newsforge.core.tag.SectorMap
import com.abhinavxt.newsforge.core.tag.SymbolLexicon
import com.abhinavxt.newsforge.data.model.NewArticle
import java.security.MessageDigest
import java.util.Locale

/**
 * Turns a parsed feed entry into a storable article.
 *
 * This is the point where the pipeline order is enforced: canonicalise, tag, categorise —
 * and only then can the caller cluster, because clustering needs the symbols.
 */
object Ingest {

    /**
     * How far ahead of the fetch a publisher's timestamp is allowed to be.
     *
     * Feeds do emit future dates, sometimes by hours. Left alone those items would sit
     * permanently at the top of the feed, because the ranker clamps negative age to zero
     * and so scores them at full recency forever. Clamping here rather than in the ranker
     * means the stored value is honest about when we could first have known.
     */
    private const val MAX_FUTURE_SKEW_MS = 0L

    /**
     * Oldest timestamp accepted before falling back to fetch time.
     *
     * Some feeds emit an epoch-zero or otherwise broken date on every item. Storing those
     * as 1970 buries them below the retention cutoff and they are effectively dropped.
     */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    fun toArticle(
        item: ParsedItem,
        feed: FeedSource,
        fetchedAtMillis: Long,
        lexicon: SymbolLexicon = SeedSymbols.LEXICON,
        sectors: SectorMap = SeedSymbols.SECTORS,
    ): NewArticle {
        val canonicalUrl = UrlCanonicalizer.canonicalize(item.link)
        val text = listOfNotNull(item.title, item.summary).joinToString(" ")
        val published = resolvePublishedAt(item.publishedAtMillis, fetchedAtMillis)
        val symbols = lexicon.match(text)

        return NewArticle(
            id = idFor(canonicalUrl),
            feedId = feed.id,
            title = item.title,
            summary = item.summary,
            link = item.link,
            canonicalUrl = canonicalUrl,
            // Prefer the outlet named inside the entry over the feed we happened to poll:
            // for a Google News query feed the latter is meaningless to a reader.
            sourceName = item.sourceName?.takeIf { it.isNotBlank() } ?: feed.name,
            category = Categorizer.categorize(item.title, item.summary, feed.categoryHint),
            tier = feed.tier,
            publishedAt = published,
            fetchedAt = fetchedAtMillis,
            hadPublishedDate = item.publishedAtMillis != null,
            tokens = Headline.tokenSet(item.title),
            symbols = symbols,
            // Both mechanisms, not either: a story can name a bank and still be about
            // rates, and a duty change names no company at all while being the most
            // important thing that happened to a whole basket.
            sectors = sectors.sectorsFor(symbols, text).map { it.name },
        )
    }

    internal fun resolvePublishedAt(published: Long?, fetchedAtMillis: Long): Long {
        if (published == null) return fetchedAtMillis
        if (published > fetchedAtMillis + MAX_FUTURE_SKEW_MS) return fetchedAtMillis
        if (published < fetchedAtMillis - MAX_AGE_MS) return fetchedAtMillis
        return published
    }

    /**
     * Stable primary key derived from the canonical URL.
     *
     * The URL rather than the title, because outlets edit headlines in place after
     * publishing; keying on the title would fork one article into several rows over the
     * course of a morning.
     */
    fun idFor(canonicalUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalUrl.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(ID_LENGTH)
        for (i in 0 until ID_LENGTH / 2) {
            hex.append(String.format(Locale.US, "%02x", digest[i]))
        }
        return hex.toString()
    }

    /** 24 hex chars is 96 bits — collision-free at any store size this app will reach. */
    private const val ID_LENGTH = 24
}
