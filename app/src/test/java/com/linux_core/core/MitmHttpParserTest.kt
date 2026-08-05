package com.linux_core.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MitmHttpParserTest {

    @Test
    fun parsesSimpleGetRequest() {
        val messages = mutableListOf<ParsedHttpMessage>()
        val parser = Http1StreamParser { messages.add(it) }
        parser.feed(
            ("GET /api/v1/user HTTP/1.1\r\n" +
                "Host: api.example.com\r\n" +
                "Accept: application/json\r\n" +
                "\r\n").toByteArray()
        )
        assertEquals(1, messages.size)
        val msg = messages[0]
        assertTrue(msg.isRequest)
        assertEquals("GET", msg.method)
        assertEquals("/api/v1/user", msg.path)
        assertEquals("api.example.com", msg.headers["host"])
        assertFalse(msg.bodyTruncated)
    }

    @Test
    fun parsesPostWithContentLength() {
        val messages = mutableListOf<ParsedHttpMessage>()
        val parser = Http1StreamParser { messages.add(it) }
        val body = """{"name":"test"}"""
        parser.feed(
            ("POST /login HTTP/1.1\r\n" +
                "Host: auth.example.com\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "\r\n" +
                body).toByteArray()
        )
        assertEquals(1, messages.size)
        assertEquals("POST", messages[0].method)
        assertEquals(body, String(messages[0].body))
    }

    @Test
    fun parsesHttpResponse() {
        val messages = mutableListOf<ParsedHttpMessage>()
        val parser = Http1StreamParser { messages.add(it) }
        parser.feed(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: 11\r\n" +
                "\r\n" +
                "{\"ok\":true}").toByteArray()
        )
        assertEquals(1, messages.size)
        val msg = messages[0]
        assertFalse(msg.isRequest)
        assertEquals(200, msg.status)
        assertEquals("{\"ok\":true}", String(msg.body))
    }

    @Test
    fun parsesMultipleMessagesInOneFeed() {
        val messages = mutableListOf<ParsedHttpMessage>()
        val parser = Http1StreamParser { messages.add(it) }
        parser.feed(
            "GET /a HTTP/1.1\r\nHost: a.test\r\n\r\nGET /b HTTP/1.1\r\nHost: b.test\r\n\r\n".toByteArray()
        )
        assertEquals(2, messages.size)
        assertEquals("/a", messages[0].path)
        assertEquals("/b", messages[1].path)
    }
}
