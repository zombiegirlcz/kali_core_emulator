package com.linux_core.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detekce odkazů pro kontextové menu terminálu („more" → Kopírovat URL).
 *
 * Vstupem je transcript celého bufferu, tedy i prompty, ANSI-očištěné logy
 * a odkazy obalené interpunkcí/závorkami — proto ty případy níž.
 */
class UrlExtractorTest {

    @Test
    fun `najde http i https odkaz`() {
        val urls = UrlExtractor.extract("curl http://example.com/a\nwget https://example.org/b")
        assertTrue(urls.contains("http://example.com/a"))
        assertTrue(urls.contains("https://example.org/b"))
    }

    @Test
    fun `nejnovejsi odkaz je prvni`() {
        val urls = UrlExtractor.extract("https://first.example/1\nhttps://second.example/2")
        assertEquals("https://second.example/2", urls.first())
    }

    @Test
    fun `duplikaty se slouci`() {
        val urls = UrlExtractor.extract("https://a.example/x\nhttps://a.example/x\n")
        assertEquals(listOf("https://a.example/x"), urls)
    }

    @Test
    fun `koncova interpunkce se orizne`() {
        assertEquals("https://a.example/x", UrlExtractor.extract("viz https://a.example/x.").first())
        assertEquals("https://a.example/x", UrlExtractor.extract("(https://a.example/x)").first())
        assertEquals("https://a.example/x", UrlExtractor.extract("[https://a.example/x]").first())
    }

    @Test
    fun `parovane zavorky v odkazu zustanou`() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Foo_(bar)",
            UrlExtractor.extract("https://en.wikipedia.org/wiki/Foo_(bar)").first(),
        )
    }

    @Test
    fun `uvozovky a spicate zavorky odkaz ukonci`() {
        assertEquals("https://a.example/x", UrlExtractor.extract("<a href=\"https://a.example/x\">").first())
    }

    @Test
    fun `query string a fragment zustanou cele`() {
        val url = "https://a.example/s?q=1&r=2#frag"
        assertEquals(url, UrlExtractor.extract("open $url now").first())
    }

    @Test
    fun `text bez odkazu da prazdny seznam`() {
        assertTrue(UrlExtractor.extract("root@kali:~# ls -la\ntotal 0").isEmpty())
        assertTrue(UrlExtractor.extract("").isEmpty())
    }

    @Test
    fun `seznam je omezeny na MAX_URLS`() {
        val text = (1..(UrlExtractor.MAX_URLS + 50)).joinToString("\n") { "https://a.example/$it" }
        assertEquals(UrlExtractor.MAX_URLS, UrlExtractor.extract(text).size)
    }
}
