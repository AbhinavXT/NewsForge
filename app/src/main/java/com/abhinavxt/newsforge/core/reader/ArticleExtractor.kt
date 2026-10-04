package com.abhinavxt.newsforge.core.reader

import com.abhinavxt.newsforge.core.feed.Html
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI

/** An article reduced to what a reader needs: words, in order, and where they came from. */
data class ReaderArticle(
    val url: String,
    val title: String,
    val siteName: String?,
    val byline: String?,
    val blocks: List<ReaderBlock>,
    /** The page's own share image, shown above the headline. */
    val leadImage: String? = null,
) {
    /** Rough reading time at 230 words a minute, never less than one. */
    val minutes: Int
        get() {
            val words = blocks
                .filterNot { it is ReaderBlock.Image }
                .sumOf { block -> block.text.split(' ').count { it.isNotBlank() } }
            return maxOf(1, (words + 115) / 230)
        }
}

sealed interface ReaderBlock {
    val text: String

    data class Heading(override val text: String) : ReaderBlock
    data class Paragraph(override val text: String) : ReaderBlock
    data class Quote(override val text: String) : ReaderBlock

    /** @param marker "•" for an unordered list, "3." for the third item of an ordered one. */
    data class ListItem(override val text: String, val marker: String) : ReaderBlock

    /** An image from the body. [text] is its caption, often empty. */
    data class Image(val url: String, override val text: String = "") : ReaderBlock
}

/**
 * Pulls the article body out of a publisher's page.
 *
 * A cut-down Readability: strip what is never content, score each container by the
 * paragraphs directly inside it, take the best one, then walk it in document order. Not
 * a port of the real thing — that is several thousand lines tuned against the whole web,
 * and the sites this app reads are a dozen business desks with conventional markup.
 *
 * Kept free of Android types so the scoring can be pinned down by JVM tests against
 * captured pages rather than discovered on a device.
 */
object ArticleExtractor {

    /**
     * @return null when the page does not look like an article — a paywall stub, a
     *   consent wall, a live blog shell rendered by script. The caller falls back to the
     *   browser, which is better than a reader showing two sentences as if that were all.
     */
    fun extract(html: String, url: String, structuredBody: String? = null): ReaderArticle? =
        extract(Jsoup.parse(html, url), url, structuredBody)

    /**
     * @param structuredBody the page's own `articleBody` from its JSON-LD, when it has
     *   one. Used when the markup yields much less than it: some publishers render the
     *   body by script, and the structured copy is then the only one in the HTML.
     */
    fun extract(document: Document, url: String, structuredBody: String? = null): ReaderArticle? {
        val siteName = meta(document, "og:site_name")
        val title = titleOf(document)?.let { withoutSiteSuffix(it, siteName) }
        val byline = bylineOf(document)
        val leadImage = meta(document, "og:image")?.let { usableImage(document.location(), it) }

        strip(document)
        val fromMarkup = contentRoot(document)
            ?.let { root -> ArrayList<ReaderBlock>().also { collect(root, it) } }
            ?.let { tidy(it, title, leadImage) }
            .orEmpty()
        val fromStructure = structuredBody?.let { tidy(paragraphsOf(it), title, leadImage) }.orEmpty()

        val useStructure = structuredBody != null && (
            bodyLength(fromStructure) > 2 * bodyLength(fromMarkup) ||
                !agrees(fromMarkup, structuredBody)
            )
        val blocks = if (useStructure) fromStructure else fromMarkup
        if (bodyLength(blocks) < MIN_BODY_CHARS) return null

        return ReaderArticle(
            url = url,
            title = title ?: siteName ?: "",
            siteName = siteName,
            byline = byline,
            blocks = blocks,
            leadImage = leadImage,
        )
    }

    /** `<script type="application/ld+json">` bodies, for the caller to look for an articleBody in. */
    fun structuredData(document: Document): List<String> =
        document.select("script[type=application/ld+json]").map { it.data() }

    /**
     * Whether most of what the markup produced is text the structured body also has.
     *
     * The structured copy is the publisher's own statement of what the article is, so a
     * markup pick that disagrees with it took the wrong block — on Livemint, a long author
     * bio below a story whose body is mostly lists.
     */
    private fun agrees(blocks: List<ReaderBlock>, structuredBody: String): Boolean {
        val reference = comparable(Html.toPlainText(structuredBody))
        val paragraphs = blocks.filterIsInstance<ReaderBlock.Paragraph>()
        val total = paragraphs.sumOf { it.text.length }
        if (total == 0) return false
        val found = paragraphs
            .filter { comparable(it.text).take(60) in reference }
            .sumOf { it.text.length }
        return found * 2 >= total
    }

    private fun comparable(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private fun bodyLength(blocks: List<ReaderBlock>): Int =
        blocks.filterIsInstance<ReaderBlock.Paragraph>().sumOf { it.text.length }

    /**
     * Splits a structured body into paragraphs.
     *
     * Publishers serialise it by concatenating paragraph text, sometimes with newlines and
     * sometimes with nothing at all — "...30, 2026.The company had..." — so a sentence end
     * run straight into a capital is read as a paragraph break too.
     */
    private fun paragraphsOf(body: String): List<ReaderBlock> =
        body.split('\n')
            .flatMap { line -> line.split(GLUED_PARAGRAPHS) }
            .map { Html.toPlainText(it) }
            .filter { it.isNotEmpty() }
            .map { ReaderBlock.Paragraph(it) }

    // -- Metadata --------------------------------------------------------------------

    private fun titleOf(document: Document): String? {
        val social = meta(document, "og:title") ?: meta(document, "twitter:title")
        val heading = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        // The on-page headline, when the social title is that plus a section name
        // ("... | Stock Market News") that no site-name check would recognise.
        if (social != null && heading != null && heading.length >= 15 &&
            social.length > heading.length && social.startsWith(heading)
        ) {
            return heading
        }
        return social ?: heading ?: document.title().takeIf { it.isNotBlank() }
    }

    private fun bylineOf(document: Document): String? {
        val raw = meta(document, "author")
            ?: meta(document, "article:author")
            ?: document.selectFirst("[rel=author], [itemprop=author], .author, .byline")
                ?.text()
        // article:author is often a profile URL rather than a name, which reads worse
        // than no byline at all.
        // BusinessLine appends its CMS id to the name: "Gurumurthy K _11553".
        return raw?.replace(CMS_ID_SUFFIX, "")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 80 && !it.startsWith("http") }
    }

    /** "Story headline - Moneycontrol.com" → "Story headline". */
    private fun withoutSiteSuffix(title: String, siteName: String?): String {
        val site = siteName?.lowercase()?.substringBefore('.')?.takeIf { it.length >= 3 }
            ?: return title
        val match = TITLE_SUFFIX.find(title) ?: return title
        return if (site in match.value.lowercase()) title.substring(0, match.range.first) else title
    }

    private fun meta(document: Document, key: String): String? =
        document.selectFirst("meta[property=$key], meta[name=$key]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    // -- Cleaning --------------------------------------------------------------------

    private fun strip(document: Document) {
        document.select(NEVER_CONTENT).remove()
        // Collected first and removed after: removing while walking skips siblings.
        val unlikely = document.body().allElements.filter { element ->
            element.tagName() !in PROTECTED_TAGS && isUnlikely(element) && !holdsProse(element)
        }
        for (element in unlikely) element.remove()
    }

    private fun isUnlikely(element: Element): Boolean {
        val hint = "${element.className()} ${element.id()}".lowercase()
        if (hint.isBlank()) return false
        return UNLIKELY.containsMatchIn(hint) && !LIKELY.containsMatchIn(hint)
    }

    /**
     * Whether an element is carrying the story despite its name.
     *
     * Class names are a hint, not a verdict: Livemint wraps its entire body in
     * `taboola-readmore`, for the widget that hangs off its end, and stripping on the name
     * alone threw the whole article away.
     */
    private fun holdsProse(element: Element): Boolean {
        val prose = element.select("p").sumOf { it.text().length }
        return prose >= MIN_BODY_CHARS && linkDensity(element) < 0.3
    }

    // -- Choosing the body -----------------------------------------------------------

    /**
     * The element holding the most paragraph text, discounted for links.
     *
     * Each paragraph credits its parent in full and its grandparent by half, so a body
     * whose paragraphs are each wrapped in their own div still accumulates on the
     * container rather than spreading thin across the wrappers.
     */
    private fun contentRoot(document: Document): Element? {
        val scores = HashMap<Element, Double>()
        for (paragraph in document.body().select("p, pre, td")) {
            val text = paragraph.text()
            if (text.length < MIN_PARAGRAPH_CHARS) continue
            // Commas capped, so one very long paragraph cannot outvote a whole body.
            val score = 1.0 + minOf(text.count { it == ',' }, 10) + minOf(text.length / 100, 3)
            paragraph.parent()?.let { parent ->
                scores.merge(parent, score, Double::plus)
                parent.parent()?.let { scores.merge(it, score / 2, Double::plus) }
            }
        }
        if (scores.isEmpty()) return null
        return scores.entries
            .maxByOrNull { (element, score) ->
                (score + classWeight(element)) * (1 - linkDensity(element))
            }
            ?.key
    }

    /**
     * What the element's name says about it, as Readability weighs it.
     *
     * Needed because comma counts alone can be won by the wrong paragraph: a single long
     * author bio, full of commas, outscored Livemint's body, whose paragraphs each sit in
     * a wrapper of their own and so reach the shared container only at half weight.
     */
    private fun classWeight(element: Element): Int {
        val hint = "${element.className()} ${element.id()}".lowercase()
        if (hint.isBlank()) return 0
        var weight = 0
        if (LIKELY.containsMatchIn(hint)) weight += 25
        if (UNLIKELY.containsMatchIn(hint) || NEGATIVE.containsMatchIn(hint)) weight -= 25
        return weight
    }

    private fun linkDensity(element: Element): Double {
        val total = element.text().length
        if (total == 0) return 0.0
        val linked = element.select("a").sumOf { it.text().length }
        return linked.toDouble() / total
    }

    // -- Walking the body ------------------------------------------------------------

    private fun collect(element: Element, out: MutableList<ReaderBlock>) {
        for (node in element.childNodes()) {
            when (node) {
                // Some publishers put body text straight into a div separated by <br>s.
                is TextNode -> node.text().trim()
                    .takeIf { it.length >= MIN_PARAGRAPH_CHARS }
                    ?.let { out += ReaderBlock.Paragraph(it) }

                is Element -> when (node.tagName()) {
                    "p" -> paragraphOf(node)?.let { out += it }
                    "h1", "h2", "h3", "h4", "h5", "h6" ->
                        node.text().trim().takeIf { it.isNotEmpty() }
                            ?.let { out += ReaderBlock.Heading(it) }
                    "blockquote" -> node.text().trim().takeIf { it.isNotEmpty() }
                        ?.let { out += ReaderBlock.Quote(it) }
                    "ul", "ol" -> collectList(node, out)
                    "pre" -> node.wholeText().trimEnd().takeIf { it.isNotBlank() }
                        ?.let { out += ReaderBlock.Paragraph(it) }
                    // A figure is its image and its caption, and nothing inside it is
                    // walked further, so the caption is not repeated as a paragraph.
                    "figure" -> node.selectFirst("img")?.let { imageOf(it) }?.let { image ->
                        val caption = node.selectFirst("figcaption")?.text()?.trim().orEmpty()
                        out += image.copy(text = caption)
                    }
                    "img" -> imageOf(node)?.let { out += it }
                    else -> collect(node, out)
                }
            }
        }
    }

    private fun paragraphOf(element: Element): ReaderBlock? {
        val text = element.text().trim()
        if (text.isEmpty()) return null
        // A paragraph that is nothing but a link is a "read also" teaser.
        if (linkDensity(element) > 0.8) return null
        return ReaderBlock.Paragraph(text)
    }

    private fun collectList(list: Element, out: MutableList<ReaderBlock>) {
        // A list that is mostly links is navigation or a related-stories block.
        if (linkDensity(list) > 0.5) return
        val ordered = list.tagName() == "ol"
        list.children().filter { it.tagName() == "li" }.forEachIndexed { index, item ->
            val text = item.text().trim()
            if (text.isNotEmpty()) {
                out += ReaderBlock.ListItem(text, if (ordered) "${index + 1}." else "•")
            }
        }
    }

    private fun imageOf(img: Element): ReaderBlock.Image? {
        // Spacer pixels and icons declare themselves small; real photographs rarely do.
        val declared = listOf("width", "height").mapNotNull { img.attr(it).toIntOrNull() }
        if (declared.any { it in 1 until MIN_IMAGE_PX }) return null
        for (attribute in IMAGE_SOURCES) {
            val raw = img.attr(attribute).trim()
            if (raw.isEmpty()) continue
            // The last candidate of a srcset, which by convention is the widest.
            val candidate = if (attribute.endsWith("srcset")) {
                raw.split(',').map { it.trim().substringBefore(' ') }.lastOrNull { it.isNotEmpty() }
            } else {
                raw
            } ?: continue
            usableImage(img.baseUri(), candidate)?.let { return ReaderBlock.Image(it) }
        }
        return null
    }

    /** Absolute http(s), and not a format that is never a photograph. */
    private fun usableImage(base: String, raw: String): String? {
        val absolute = try {
            if (base.isBlank()) URI(raw) else URI(base).resolve(raw.replace(" ", "%20"))
        } catch (e: Exception) {
            return null
        }.toString()
        if (!absolute.startsWith("http://") && !absolute.startsWith("https://")) return null
        val path = absolute.substringBefore('?').lowercase()
        if (path.endsWith(".svg") || path.endsWith(".gif")) return null
        return absolute
    }

    /** Ignores the query, which CDNs use for sizing: the same picture at two widths is one. */
    private fun sameImage(a: String, b: String?): Boolean =
        b != null && a.substringBefore('?') == b.substringBefore('?')

    /**
     * Drops the title repeated as a heading, boilerplate lines, exact duplicates, and the
     * lead image repeated in the body.
     */
    private fun tidy(blocks: List<ReaderBlock>, title: String?, leadImage: String?): List<ReaderBlock> {
        val seen = HashSet<String>()
        val images = HashSet<String>()
        return blocks.filter { block ->
            if (block is ReaderBlock.Image) {
                return@filter !sameImage(block.url, leadImage) &&
                    images.add(block.url.substringBefore('?'))
            }
            val key = block.text.lowercase()
            when {
                block is ReaderBlock.Heading && title != null && key == title.lowercase() -> false
                BOILERPLATE.matches(key) -> false
                block !is ReaderBlock.ListItem && !seen.add(key) -> false
                else -> true
            }
        }
    }

    private const val MIN_PARAGRAPH_CHARS = 25

    private const val MIN_IMAGE_PX = 120

    /** Lazy-loading attributes first: on those pages `src` is a placeholder. */
    private val IMAGE_SOURCES =
        listOf("data-src", "data-lazy-src", "data-original", "data-srcset", "srcset", "src")

    /** Below this, what was found is a teaser or a wall, not the story. */
    private const val MIN_BODY_CHARS = 300

    /** A sentence end followed directly by a capital or an opening quote, with no space. */
    private val GLUED_PARAGRAPHS =
        Regex("(?<=[a-z0-9%)][.!?])(?=[\"\u201C]?[A-Z])|(?<=[.!?]\u201D)(?=[A-Z])")

    private val CMS_ID_SUFFIX = Regex("\\s*_\\d+$")

    /** A trailing " - Site", " | Site" or " : Site" on a title. */
    private val TITLE_SUFFIX = Regex("\\s*[-|\u2013\u2014:]\\s*[^-|\u2013\u2014:]{2,40}$")

    private const val NEVER_CONTENT =
        "script, style, noscript, iframe, form, nav, header, footer, aside, button, " +
            "svg, canvas, select, input, video, audio, object, embed"

    /** Never removed on a class-name hint: the hint is about a wrapper, not the page. */
    private val PROTECTED_TAGS = setOf("html", "body", "article", "main")

    private val UNLIKELY = Regex(
        "comment|share|social|related|sidebar|footer|header|menu|nav|breadcrumb|" +
            "promo|newsletter|subscribe|signup|advert|\\bads?\\b|ad-|-ad\\b|banner|" +
            "popup|modal|cookie|consent|recommend|trending|also-?read|read-?more|" +
            "tags|author-?bio|disclaimer|outbrain|taboola|widget"
    )

    /** Names that mark a block as about the article rather than of it. Score only, never stripped. */
    private val NEGATIVE = Regex("author|bio|profile|byline|caption|meta|credit|footnote")

    private val LIKELY = Regex("article|body|content|story|main|post|entry|text")

    private val BOILERPLATE = Regex(
        "advertisement|story continues below.*|also read.*|read more.*|catch all the .*|" +
            "published on .{0,40}|\u00A9.{0,60}|more stories like this .*|" +
            "(download|subscribe to) .{0,60}|follow us on .{0,60}|" +
            "\\(this story has not been edited.*"
    )
}
