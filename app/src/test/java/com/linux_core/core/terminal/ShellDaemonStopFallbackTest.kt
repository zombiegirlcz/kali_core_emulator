package com.linux_core.core.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Strážce fallbacku pro zastavení shell_daemonu (uid 2000), NON-ROOT.
 *
 * Kontext (incident 2026-09-20): `ashell adb stop` na zařízení hlásil
 * "shell_daemon stopped", ale proces i listener na 13341 běžely dál, protože:
 *   - starý démon neznal `SH_MODE_STOP` (mode=3 propadl do handle_exec a
 *     socket se resetoval), a
 *   - když API `/shelldaemon/stop` vrátilo, kód jen vypsal "stale bezi"
 *     a skončil — ŽÁDNÝ fallback se nespustil, i když byl adb připojený.
 *
 * Tyhle testy čtou ZDROJÁKY (jako ShellDaemonProtocolTest) a hlídají, že:
 *   1. `ashell` stop po neúspěšném API skutečně zabije daemona přes `adb shell`
 *      (uid 2000 → dosáhne na uid 2000 proces) a neskončí jen hláškou,
 *   2. `ShellDaemonClient.stopDaemon` má fallback přes SH_MODE_EXEC
 *      (`kill <pid>`), který funguje i pro starý démon bez SH_MODE_STOP,
 *   3. celá cesta je NON-ROOT (žádné `su`).
 *
 * Pozn.: běží z modulu `app` (cwd = app/), stejně jako AshellConfigParserTest.
 */
class ShellDaemonStopFallbackTest {

    private fun ashell(): String = File("src/main/assets/ashell").readText()

    private fun client(): String =
        File("src/main/java/com/linux_core/core/terminal/ShellDaemonClient.kt").readText()

    /** Vytáhne blok `stop)` .. `;;` z case v ashellu. */
    private fun ashellStopBranch(): String {
        val src = ashell()
        val start = src.indexOf("        stop)")
        assertTrue("ashell: vetev 'stop)' nenalezena", start >= 0)
        val end = src.indexOf(";;", start)
        assertTrue("ashell: konec vetve 'stop)' (';;') nenalezen", end > start)
        return src.substring(start, end)
    }

    /** Vytáhne tělo funkce stopDaemon (od `fun stopDaemon` po další @JvmStatic). */
    private fun stopDaemonBody(): String {
        val src = client()
        val start = src.indexOf("fun stopDaemon")
        assertTrue("ShellDaemonClient: stopDaemon() nenalezena", start >= 0)
        val end = src.indexOf("\n    @JvmStatic", start + 1)
            .let { if (it < 0) src.length else it }
        return src.substring(start, end)
    }

    @Test
    fun `ashell stop po neuspesnem API nezustane jen u hlasky`() {
        val stop = ashellStopBranch()
        // Regrese: dřív tu byl jen `echo stale` + `exit 0` bez fallbacku.
        assertFalse(
            "ashell stop nesmi skoncit jen hláškou 'stale bezi' s exit 0",
            stop.contains("API stop nedoběhl") && !stop.contains("pkill")
        )
        assertTrue(
            "ashell stop musí po neúspěchu skončit nenulovým kódem, když daemon pořád běží",
            stop.contains("exit 1")
        )
    }

    @Test
    fun `ashell stop ma adb fallback kill a pkill`() {
        val stop = ashellStopBranch()
        assertTrue(
            "ashell stop musí umět kill PID z PID file přes adb shell",
            stop.contains("adb shell") && (stop.contains("kill \$_p") || stop.contains("kill -9 \$_p"))
        )
        assertTrue(
            "ashell stop musí mít pkill -f libshelldaemon jako poslední záchranu",
            stop.contains("pkill -f libshelldaemon")
        )
        assertTrue(
            "ashell stop musí ověřit, že daemon po fallbacku skutečně zmizel",
            stop.contains("daemon_alive")
        )
    }

    @Test
    fun `ashell stop pouziva adb shell jen kdyz je adb pripojeny`() {
        val stop = ashellStopBranch()
        // adb shell kill má smysl jen s připojeným adb (device$ v `adb devices`).
        assertTrue(
            "ashell stop musí adb fallback gatovat na připojené zařízení",
            stop.contains("adb devices") && stop.contains("device")
        )
    }

    @Test
    fun `ShellDaemonClient stopDaemon ma EXEC kill fallback pro stary daemon`() {
        val body = stopDaemonBody()
        assertTrue(
            "stopDaemon musí poslat SH_MODE_STOP (nový démon)",
            body.contains("SH_MODE_STOP")
        )
        assertTrue(
            "stopDaemon musí mít fallback `kill <pid>` přes EXEC pro starý démon bez SH_MODE_STOP",
            body.contains("kill \$pid")
        )
        assertTrue(
            "stopDaemon fallback musí použít exec() (SH_MODE_EXEC), ne adb",
            body.contains("exec(context")
        )
    }

    @Test
    fun `stop cesta je non-root`() {
        val stop = ashellStopBranch()
        val body = stopDaemonBody()
        // Cíl je non-root: uid 2000 (adb shell) i EXEC kill (worker = stejné uid)
        // dosáhnou na démona bez `su`.
        assertFalse(
            "ashell stop nesmí používat su (cíl je non-root)",
            stop.contains("su -c") || stop.contains(" su ")
        )
        assertFalse("stopDaemon nesmí používat su (cíl je non-root)", body.contains("su -c"))
    }
}
