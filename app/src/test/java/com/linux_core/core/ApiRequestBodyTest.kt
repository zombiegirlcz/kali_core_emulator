package com.linux_core.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * `Content-Length` je v bajtech, `Reader` vrací znaky.
 *
 * Regrese: tělo s diakritikou mělo méně znaků než bajtů, takže čtení čekalo
 * na znaky, které nikdy nepřišly → `printf 'řádek\n' | nh clip` zamrzl na
 * curlu až do timeoutu (2026-10-29). Tenhle test to drží opravené.
 */
class ApiRequestBodyTest {

    private fun read(body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reader = BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8))
        return LocalApiServer.readRequestBody(reader, bytes.size)
    }

    @Test
    fun `ascii telo se precte cele`() {
        assertEquals("https://example.com/a?q=1", read("https://example.com/a?q=1"))
    }

    @Test
    fun `telo s diakritikou se precte cele a neblokuje`() {
        val body = "řádek jeden\nřádek dvě\n"
        assertEquals(body, read(body))
    }

    @Test
    fun `emoji mimo BMP se precte cele`() {
        val body = "📋 schránka 🛡️"
        assertEquals(body, read(body))
    }

    @Test
    fun `prazdne telo da prazdny string`() {
        assertEquals("", LocalApiServer.readRequestBody(BufferedReader(InputStreamReader(ByteArrayInputStream(ByteArray(0)))), 0))
    }

    @Test
    fun `kratsi stream nez Content-Length skonci na EOF`() {
        val bytes = "abc".toByteArray(Charsets.UTF_8)
        val reader = BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8))
        assertEquals("abc", LocalApiServer.readRequestBody(reader, 100))
    }

    @Test
    fun `dlouhe telo nad velikost bufferu`() {
        val body = "á".repeat(9000)
        assertEquals(body, read(body))
    }
}
