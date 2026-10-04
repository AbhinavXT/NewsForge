package com.abhinavxt.newsforge.core.feed

import com.abhinavxt.newsforge.core.model.ParsedItem
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/** Thrown when a response is not usable XML. The caller records it as feed health. */
class FeedParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A parsed feed document. */
data class ParsedFeed(
    val title: String?,
    val items: List<ParsedItem>,
)

/**
 * Parses RSS 2.0, RSS 1.0 (RDF) and Atom into a single shape.
 *
 * DOM rather than `XmlPullParser` for two reasons: `javax.xml.parsers` exists on both
 * Android and the JVM, so this whole class is testable in plain local unit tests with no
 * Robolectric; and feeds are tens of kilobytes, where DOM's memory overhead is irrelevant.
 *
 * The parser is namespace-*unaware* on purpose. Feeds in the wild are inconsistent about
 * declaring namespaces correctly, and a namespace-aware parser rejects documents that
 * every feed reader on earth accepts. Element matching is done on the local name with any
 * prefix stripped, which handles `dc:date` and `a:entry` alike.
 */
object FeedParser {

    fun parse(xml: String): ParsedFeed = parse(xml.toByteArray(StandardCharsets.UTF_8))

    fun parse(bytes: ByteArray): ParsedFeed {
        val document = try {
            val builder = builderFactory().newDocumentBuilder()
            // The default handler prints parse errors straight to stderr, which on
            // Android means every malformed feed spams logcat. The exception below is
            // the report we actually want.
            builder.setErrorHandler(SilentErrorHandler)
            builder.parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw FeedParseException("Not parseable as XML: ${e.message}", e)
        }
        val root = document.documentElement
            ?: throw FeedParseException("Empty document")
        root.normalize()

        return when (localName(root).lowercase()) {
            "rss" -> parseRss(root)
            "rdf" -> parseRdf(root)
            "feed" -> parseAtom(root)
            // Some CDNs wrap the feed. Fall back to whatever item-like elements exist.
            else -> parseRdf(root).takeIf { it.items.isNotEmpty() }
                ?: throw FeedParseException("Unrecognised root element <${root.tagName}>")
        }
    }

    // ---------------------------------------------------------------- formats

    private fun parseRss(root: Element): ParsedFeed {
        val channel = childElement(root, "channel")
            ?: throw FeedParseException("<rss> with no <channel>")
        val items = childElements(channel, "item").mapNotNull { rssItem(it) }
        return ParsedFeed(title = textOf(channel, "title"), items = items)
    }

    /** RSS 1.0 puts `item` as a sibling of `channel`, not a child. */
    private fun parseRdf(root: Element): ParsedFeed {
        val channel = childElement(root, "channel")
        val items = descendantElements(root, "item").mapNotNull { rssItem(it) }
        return ParsedFeed(title = channel?.let { textOf(it, "title") }, items = items)
    }

    private fun parseAtom(root: Element): ParsedFeed {
        val items = childElements(root, "entry").mapNotNull { atomEntry(it) }
        return ParsedFeed(title = textOf(root, "title"), items = items)
    }

    // ---------------------------------------------------------------- entries

    private fun rssItem(item: Element): ParsedItem? {
        val guid = textOf(item, "guid")
        // Some feeds omit <link> and put the URL in a permalink guid.
        val link = textOf(item, "link")
            ?: guid?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: return null
        val rawTitle = textOf(item, "title") ?: return null
        val sourceName = textOf(item, "source")
        return ParsedItem(
            title = stripPublisherSuffix(rawTitle, sourceName),
            link = link,
            summary = textOf(item, "description") ?: textOf(item, "encoded"),
            publishedAtMillis = FeedDates.parse(
                textOf(item, "pubDate") ?: textOf(item, "date") ?: textOf(item, "updated")
            ),
            guid = guid,
            sourceName = sourceName,
        )
    }

    private fun atomEntry(entry: Element): ParsedItem? {
        val rawTitle = textOf(entry, "title") ?: return null
        val link = atomLink(entry) ?: return null
        val sourceName = childElement(entry, "source")?.let { textOf(it, "title") }
        return ParsedItem(
            title = stripPublisherSuffix(rawTitle, sourceName),
            link = link,
            summary = textOf(entry, "summary") ?: textOf(entry, "content"),
            publishedAtMillis = FeedDates.parse(
                textOf(entry, "published") ?: textOf(entry, "updated")
            ),
            guid = textOf(entry, "id"),
            sourceName = sourceName,
        )
    }

    /** Prefers `rel="alternate"`, then a link with no rel, and ignores `self`/`enclosure`. */
    private fun atomLink(entry: Element): String? {
        val links = childElements(entry, "link")
        val preferred = links.firstOrNull { it.getAttribute("rel") == "alternate" }
            ?: links.firstOrNull { it.getAttribute("rel").isEmpty() }
            ?: links.firstOrNull { it.getAttribute("rel") != "self" }
        val href = preferred?.getAttribute("href")?.trim()
        if (!href.isNullOrEmpty()) return href
        return textOf(entry, "link")
    }

    /**
     * Google News titles are `"Headline - Publisher"` with the publisher repeated in
     * `<source>`. Left in place the suffix pollutes both the displayed headline and the
     * dedupe tokens, and since every outlet carrying a story appends a *different*
     * suffix, it actively pushes near-identical headlines apart.
     */
    internal fun stripPublisherSuffix(title: String, sourceName: String?): String {
        val clean = title.trim()
        if (sourceName.isNullOrBlank()) return clean
        val suffix = " - ${sourceName.trim()}"
        return if (clean.length > suffix.length && clean.endsWith(suffix, ignoreCase = true)) {
            clean.dropLast(suffix.length).trim()
        } else {
            clean
        }
    }

    // ---------------------------------------------------------------- dom helpers

    private fun builderFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        // Block DTD and external entity resolution: feed bytes are untrusted, and a
        // DOCTYPE reference would otherwise trigger a blocking network fetch.
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        runCatching { factory.isXIncludeAware = false }
        return factory
    }

    private fun localName(element: Element): String = element.tagName.substringAfterLast(':')

    /** Swallows warnings and recoverable errors; fatal ones still abort the parse. */
    private object SilentErrorHandler : org.xml.sax.ErrorHandler {
        override fun warning(exception: org.xml.sax.SAXParseException) = Unit
        override fun error(exception: org.xml.sax.SAXParseException) = Unit
        override fun fatalError(exception: org.xml.sax.SAXParseException) {
            throw exception
        }
    }

    private fun childElements(parent: Element, name: String): List<Element> {
        val result = ArrayList<Element>()
        var node: Node? = parent.firstChild
        while (node != null) {
            if (node is Element && localName(node).equals(name, ignoreCase = true)) {
                result.add(node)
            }
            node = node.nextSibling
        }
        return result
    }

    private fun childElement(parent: Element, name: String): Element? =
        childElements(parent, name).firstOrNull()

    private fun descendantElements(parent: Element, name: String): List<Element> {
        val result = ArrayList<Element>()
        fun walk(node: Node) {
            var child: Node? = node.firstChild
            while (child != null) {
                if (child is Element) {
                    if (localName(child).equals(name, ignoreCase = true)) result.add(child)
                    walk(child)
                }
                child = child.nextSibling
            }
        }
        walk(parent)
        return result
    }

    private fun textOf(parent: Element, name: String): String? {
        val element = childElement(parent, name) ?: return null
        return Html.toPlainText(element.textContent).takeIf { it.isNotEmpty() }
    }
}
