package com.abhinavxt.newsforge.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleExtractorTest {

    private val url = "https://www.example.com/markets/story-123"

    private val paragraph =
        "Shares of the company rose sharply in early trade on Tuesday, after it reported " +
            "a quarterly profit well ahead of estimates, driven by margins, volumes and pricing."

    private fun page(body: String, head: String = "") = """
        <html><head>
          <title>Fallback title | Example</title>
          <meta property="og:title" content="Profit beats estimates">
          <meta property="og:site_name" content="Example Times">
          <meta name="author" content="A Reporter _11553">
          $head
        </head><body>$body</body></html>
    """.trimIndent()

    @Test
    fun picksTheArticleBodyOverTheChromeAroundIt() {
        val html = page(
            """
            <nav><ul><li><a href="/">Home</a></li><li><a href="/m">Markets</a></li></ul></nav>
            <div class="sidebar"><p>$paragraph Sidebar.</p></div>
            <div class="story-content">
              <h1>Profit beats estimates</h1>
              <p>$paragraph One.</p>
              <h2>What analysts said</h2>
              <p>$paragraph Two.</p>
              <blockquote>We are confident about the year ahead, said the CEO.</blockquote>
              <ol><li>Revenue up 12%</li><li>Margin up 80 bps</li></ol>
              <p>$paragraph Three.</p>
            </div>
            <footer><p>$paragraph Footer.</p></footer>
            """
        )

        val article = ArticleExtractor.extract(html, url)

        assertNotNull(article)
        article!!
        assertEquals("Profit beats estimates", article.title)
        assertEquals("Example Times", article.siteName)
        assertEquals("A Reporter", article.byline)
        assertEquals(
            listOf(
                ReaderBlock.Paragraph("$paragraph One."),
                ReaderBlock.Heading("What analysts said"),
                ReaderBlock.Paragraph("$paragraph Two."),
                ReaderBlock.Quote("We are confident about the year ahead, said the CEO."),
                ReaderBlock.ListItem("Revenue up 12%", "1."),
                ReaderBlock.ListItem("Margin up 80 bps", "2."),
                ReaderBlock.Paragraph("$paragraph Three."),
            ),
            article.blocks,
        )
    }

    @Test
    fun aStubPageIsNotReadable() {
        // A paywall: one teaser paragraph and a prompt to subscribe.
        val html = page(
            """
            <article><p>$paragraph</p><p><a href="/subscribe">Subscribe to continue</a></p></article>
            """
        )
        assertNull(ArticleExtractor.extract(html, url))
    }

    @Test
    fun dropsTeasersAndBoilerplate() {
        val html = page(
            """
            <article>
              <p>$paragraph One.</p>
              <p><a href="/other">Also read: another story about another company entirely</a></p>
              <p>Advertisement</p>
              <p>$paragraph Two.</p>
              <p>$paragraph Two.</p>
              <p>$paragraph Three.</p>
            </article>
            """
        )

        val texts = ArticleExtractor.extract(html, url)!!.blocks.map { it.text }

        assertEquals(listOf("$paragraph One.", "$paragraph Two.", "$paragraph Three."), texts)
    }

    @Test
    fun readsBodyTextSetDirectlyInADiv() {
        val html = page(
            """
            <div class="content">$paragraph One.<br><br>$paragraph Two.<br><br>$paragraph Three.<p>$paragraph Four.</p></div>
            """
        )

        val texts = ArticleExtractor.extract(html, url)!!.blocks.map { it.text }

        assertEquals(4, texts.size)
        assertTrue(texts.first().endsWith("One."))
    }

    @Test
    fun fallsBackToThePageTitleAndIgnoresAUrlByline() {
        val html = """
            <html><head><title>Plain title</title>
            <meta property="article:author" content="https://facebook.com/someone">
            </head><body><article>
            <p>$paragraph One.</p><p>$paragraph Two.</p><p>$paragraph Three.</p>
            </article></body></html>
        """.trimIndent()

        val article = ArticleExtractor.extract(html, url)!!

        assertEquals("Plain title", article.title)
        assertNull(article.byline)
        assertFalse(article.blocks.isEmpty())
    }

    @Test
    fun usesTheStructuredBodyWhenTheMarkupHasNoArticle() {
        // Economic Times: the body is rendered by script, so only JSON-LD carries it.
        val html = page("<div><p>Top Trending Stocks: SBI Share Price, Axis Bank Share Price</p></div>")
        val structured = "$paragraph One.$paragraph Two.\n$paragraph Three."

        val texts = ArticleExtractor.extract(html, url, structured)!!.blocks.map { it.text }

        assertEquals(listOf("$paragraph One.", "$paragraph Two.", "$paragraph Three."), texts)
    }

    @Test
    fun splitsAGluedStructuredBodyBeforeAnOpeningQuote() {
        val structured = "$paragraph One.\"We are pleased,\" the CEO said, $paragraph"

        val texts = ArticleExtractor.extract(page(""), url, structured)!!.blocks.map { it.text }

        assertEquals(2, texts.size)
        assertTrue(texts[1].startsWith("\"We are pleased"))
    }

    @Test
    fun prefersTheStructuredBodyWhenTheMarkupPickDisagreesWithIt() {
        // Livemint: one long author bio outscores a body that is mostly lists.
        val bio = "For about a decade, the desk has been a credible source of news, " +
            "analysis, opinion, features, data, explainers, guides, tools, newsletters, " +
            "podcasts, videos, events, awards, rankings, and much, much more besides. "
        val html = page("<div class=\"author\"><p>${bio.repeat(4)}</p></div>")
        val structured = "$paragraph One.$paragraph Two.$paragraph Three."

        val article = ArticleExtractor.extract(html, url, structured)!!

        assertTrue(article.blocks.first().text.endsWith("One."))
    }

    @Test
    fun keepsTheMarkupWhenItAgreesWithTheStructuredBody() {
        // The markup carries headings the flat structured copy cannot.
        val html = page(
            "<article><p>$paragraph One.</p><h2>Outlook</h2><p>$paragraph Two.</p>" +
                "<p>$paragraph Three.</p></article>"
        )
        val structured = "$paragraph One.$paragraph Two.$paragraph Three."

        val blocks = ArticleExtractor.extract(html, url, structured)!!.blocks

        assertTrue(blocks.contains(ReaderBlock.Heading("Outlook")))
    }

    @Test
    fun stripsASectionOrSiteSuffixFromTheTitle() {
        val body = "<article><h1>Profit beats estimates at the bank</h1>" +
            "<p>$paragraph One.</p><p>$paragraph Two.</p><p>$paragraph Three.</p></article>"
        val sectioned = """
            <html><head><meta property="og:title" content="Profit beats estimates at the bank | Stock Market News">
            </head><body>$body</body></html>
        """.trimIndent()
        val branded = """
            <html><head><meta property="og:title" content="Profit beats estimates- Moneycontrol.com">
            <meta property="og:site_name" content="Moneycontrol"></head><body>$body</body></html>
        """.trimIndent()

        assertEquals("Profit beats estimates at the bank", ArticleExtractor.extract(sectioned, url)!!.title)
        assertEquals("Profit beats estimates", ArticleExtractor.extract(branded, url)!!.title)
    }

    @Test
    fun keepsTheLeadImageAndBodyFiguresButNotIconsOrDuplicates() {
        val html = page(
            body = """
            <article>
              <figure><img src="https://cdn.example.com/lead.jpg?w=300"><figcaption>Dup of lead</figcaption></figure>
              <p>$paragraph One.</p>
              <figure><img data-src="/img/plant.jpg" src="data:image/gif;base64,R0lG"><figcaption>The plant in Pune</figcaption></figure>
              <p>$paragraph Two.</p>
              <img src="/icons/share.png" width="24" height="24">
              <img srcset="/img/chart-400.jpg 400w, /img/chart-800.jpg 800w">
              <p>$paragraph Three.</p>
            </article>
            """,
            head = """<meta property="og:image" content="https://cdn.example.com/lead.jpg?w=1200">""",
        )

        val article = ArticleExtractor.extract(html, url)!!

        assertEquals("https://cdn.example.com/lead.jpg?w=1200", article.leadImage)
        assertEquals(
            listOf(
                ReaderBlock.Image("https://www.example.com/img/plant.jpg", "The plant in Pune"),
                ReaderBlock.Image("https://www.example.com/img/chart-800.jpg"),
            ),
            article.blocks.filterIsInstance<ReaderBlock.Image>(),
        )
    }
}
