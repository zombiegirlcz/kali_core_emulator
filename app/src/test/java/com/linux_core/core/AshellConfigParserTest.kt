package com.linux_core.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit testy pro parsování ashell.conf (ExecCore.parseAshellConfig).
 *
 * ashell.conf flip: /shell API už nepoužívá hardcoded allowlist, ale blocklist
 * + env řádky z tohoto configu. Parser musí správně rozlišit:
 *   - `block <cmd>` řádky → blocked set
 *   - komentáře/prázdné řádky → ignorovat
 *   - vše ostatní → envLines (aplikují se před každý příkaz)
 */
class AshellConfigParserTest {

    @Test
    fun `empty config yields empty result`() {
        val r = ExecCore.parseAshellConfig("")
        assertTrue(r.envLines.isEmpty())
        assertTrue(r.blocked.isEmpty())
    }

    @Test
    fun `default style config parses env lines and blocks`() {
        val cfg = """
            # ashell.conf
            export HOME=${'$'}{FILES_DIR}
            export USER=app
            unset LD_LIBRARY_PATH

            block reboot
            block shutdown
        """.trimIndent()
        val r = ExecCore.parseAshellConfig(cfg)
        assertEquals(3, r.envLines.size)
        assertEquals(setOf("reboot", "shutdown"), r.blocked)
        assertTrue(r.envLines.any { it.startsWith("export HOME=") })
        assertTrue(r.envLines.contains("unset LD_LIBRARY_PATH"))
    }

    @Test
    fun `comments and blank lines are skipped`() {
        val cfg = "# comment\n\n   \n# another\nblock x\ncmd --flag\n"
        val r = ExecCore.parseAshellConfig(cfg)
        assertEquals(listOf("cmd --flag"), r.envLines)
        assertEquals(setOf("x"), r.blocked)
    }

    @Test
    fun `block with extra whitespace and tabs`() {
        val r = ExecCore.parseAshellConfig("block   reboot\nblock\tmkfs\n  block  dd  ")
        // trailing spaces na 3. řádku: "block  dd  " -> trim -> "dd"
        assertEquals(setOf("reboot", "mkfs", "dd"), r.blocked)
    }

    @Test
    fun `bare block keyword without command is ignored`() {
        val r = ExecCore.parseAshellConfig("block\nblock ")
        assertTrue(r.blocked.isEmpty())
        assertTrue(r.envLines.isEmpty())
    }

    @Test
    fun `duplicate blocks deduplicate into set`() {
        val r = ExecCore.parseAshellConfig("block reboot\nblock reboot")
        assertEquals(setOf("reboot"), r.blocked)
    }

    @Test
    fun `blocked contains is the gate semantics`() {
        val cfg = "block rm\nexport FOO=1\n"
        val r = ExecCore.parseAshellConfig(cfg)
        // /shell gate: první token příkazu musí být v blocked setu
        val cmdName = "rm -rf /tmp/x".trim().substringBefore(" ").substringAfterLast("/")
        assertTrue(cmdName in r.blocked)
        assertFalse("ls" in r.blocked) // flip: cokoliv mimo blocklist je POVOLENO
    }

    @Test
    fun `path prefixed command still matches bare block`() {
        // Server extrahuje basename před porovnáním — config blokuje holé jméno
        val cfg = "block curl\n"
        val r = ExecCore.parseAshellConfig(cfg)
        val cmdName = "/usr/bin/curl http://evil".substringBefore(" ").substringAfterLast("/")
        assertTrue(cmdName in r.blocked)
    }
}
