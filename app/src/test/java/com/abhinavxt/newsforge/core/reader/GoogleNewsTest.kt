package com.abhinavxt.newsforge.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class GoogleNewsTest {

    @Test
    fun recognisesArticleLinksOnly() {
        assertTrue(GoogleNews.isArticleLink("https://news.google.com/rss/articles/CBMi?oc=5"))
        assertFalse(GoogleNews.isArticleLink("https://news.google.com/rss/search?q=x"))
        assertFalse(GoogleNews.isArticleLink("https://www.livemint.com/articles/x"))
    }

    @Test
    fun readsTheTokensOffTheArticlePage() {
        val html = """<c-wiz><div jscontroller="x" data-n-a-id="CBMiAbc" """ +
            """data-n-a-ts="1791056329" data-n-a-sg="AbIaSL_etp"></div></c-wiz>"""

        assertEquals(
            GoogleNews.Tokens("CBMiAbc", "1791056329", "AbIaSL_etp"),
            GoogleNews.tokensIn(html),
        )
        assertNull(GoogleNews.tokensIn("<html>consent wall</html>"))
    }

    @Test
    fun buildsTheRequestWithTheTokensInside() {
        val body = GoogleNews.requestBody(GoogleNews.Tokens("CBMiAbc", "123", "SIG"))
        val decoded = URLDecoder.decode(body.removePrefix("f.req="), "UTF-8")

        assertTrue(body.startsWith("f.req="))
        assertTrue(decoded.startsWith("[[[\"Fbv4je\",\"[\\\"garturlreq\\\""))
        assertTrue(decoded.contains("\\\"CBMiAbc\\\",123,\\\"SIG\\\"]"))
    }

    @Test
    fun readsThePublisherUrlFromTheReply() {
        // Captured from the live endpoint.
        val reply = ")]}'\n\n[[\"wrb.fr\",\"Fbv4je\",\"[\\\"garturlres\\\",\\\"" +
            "https://m.economictimes.com/markets/x/articleshow/134654760.cms\\\",1]\"," +
            "null,null,null,\"generic\"]]"

        assertEquals(
            "https://m.economictimes.com/markets/x/articleshow/134654760.cms",
            GoogleNews.urlIn(reply),
        )
        assertNull(GoogleNews.urlIn(")]}'\n\n[[\"er\",null,null,null,null,400]]"))
    }
}
