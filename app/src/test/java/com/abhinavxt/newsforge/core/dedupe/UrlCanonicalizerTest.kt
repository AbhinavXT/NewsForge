package com.abhinavxt.newsforge.core.dedupe

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlCanonicalizerTest {

    private fun canon(url: String) = UrlCanonicalizer.canonicalize(url)

    @Test
    fun stripsTrackingParameters() {
        assertEquals(
            "https://example.com/story/1",
            canon("https://example.com/story/1?utm_source=rss&utm_medium=feed&fbclid=abc"),
        )
    }

    @Test
    fun keepsMeaningfulParametersAndSortsThem() {
        assertEquals(
            "https://example.com/s?id=42&page=2",
            canon("https://example.com/s?page=2&id=42&utm_campaign=x"),
        )
    }

    @Test
    fun normalisesSchemeHostAndTrailingSlash() {
        val expected = "https://example.com/story/1"
        assertEquals(expected, canon("http://www.example.com/story/1/"))
        assertEquals(expected, canon("HTTPS://WWW.Example.COM/story/1"))
        assertEquals(expected, canon("https://m.example.com/story/1"))
    }

    @Test
    fun dropsFragments() {
        assertEquals(
            "https://example.com/story/1",
            canon("https://example.com/story/1#comments"),
        )
    }

    @Test
    fun collapsesAmpVariantsOntoTheCanonicalArticle() {
        val expected = "https://example.com/story/1"
        assertEquals(expected, canon("https://example.com/story/1/amp"))
        assertEquals(expected, canon("https://example.com/story/1.amp"))
        assertEquals(expected, canon("https://amp.example.com/story/1"))
        assertEquals(expected, canon("https://example.com/amp/story/1"))
    }

    @Test
    fun googleNewsRedirectsKeepTheirOpaqueIdButLoseLocaleParams() {
        assertEquals(
            "https://news.google.com/rss/articles/CBMiXWh0dHBz",
            canon("https://news.google.com/rss/articles/CBMiXWh0dHBz?oc=5&hl=en-IN&gl=IN&ceid=IN:en"),
        )
    }

    @Test
    fun rootPathSurvivesSlashStripping() {
        assertEquals("https://example.com/", canon("https://example.com/"))
        assertEquals("https://example.com/", canon("https://example.com"))
    }

    @Test
    fun leavesUnparseableOrNonHttpInputUntouched() {
        assertEquals("not a url", canon("not a url"))
        assertEquals("mailto:desk@example.com", canon("mailto:desk@example.com"))
        assertEquals("", canon("   "))
    }

    @Test
    fun differentTrackingOnTheSameArticleProducesOneKey() {
        val a = canon("https://www.example.com/news/x-1.html?utm_source=google_news")
        val b = canon("http://example.com/news/x-1.html/?fbclid=zzz#top")
        assertEquals(a, b)
    }
}
