package com.linux_core.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Strážce protokolu shell_daemonu.
 *
 * Wire formát mezi nativním `shell_daemon` (C) a `ShellDaemonClient` (Kotlin)
 * je binární a tichý: magic + mode + token/cmd/cwd bloby. Když se jedno číslo
 * změní jen na jedné straně, klient dostane "spatny magic" až za běhu na
 * zařízení (a bez logu to vypadá jako "daemon neběží"). Tenhle test to
 * zachytí už v unit testech tím, že parsuje oba zdroje a porovná konstanty.
 *
 * Pozn.: běží z modulu `app` (cwd = app/), stejně jako DeployAssetPathsTest.
 */
class ShellDaemonProtocolTest {

    private fun cSource(): String = File("src/main/cpp/shell_daemon.c").readText()

    private fun ktSource(): String =
        File("src/main/java/com/linux_core/core/terminal/ShellDaemonClient.kt").readText()

    /** Vytáhne `#define NAZEV 0x...u` / `#define NAZEV 0u` z C zdroje. */
    private fun cDefine(src: String, name: String): Long {
        val re = Regex("""#define\s+$name\s+(0x[0-9A-Fa-f]+|\d+)u?""")
        val m = re.find(src) ?: error("C: #define $name nenalezen")
        return m.groupValues[1].let {
            if (it.startsWith("0x") || it.startsWith("0X")) it.substring(2).toLong(16)
            else it.toLong()
        }
    }

    /** Vytáhne `private const val NAZEV = 0x...` / `= 0` z Kotlin zdroje. */
    private fun ktConst(src: String, name: String): Long {
        val re = Regex("""const\s+val\s+$name\s*=\s*(0x[0-9A-Fa-f]+|\d+)""")
        val m = re.find(src) ?: error("Kotlin: const val $name nenalezen")
        return m.groupValues[1].let {
            if (it.startsWith("0x") || it.startsWith("0X")) it.substring(2).toLong(16)
            else it.toLong()
        }
    }

    @Test
    fun `magic SHLL je stejny v C i Kotlinu`() {
        assertEquals(
            "SH_MAGIC se rozesel mezi shell_daemon.c a ShellDaemonClient.kt",
            cDefine(cSource(), "SH_MAGIC"),
            ktConst(ktSource(), "SH_MAGIC")
        )
    }

    @Test
    fun `mode exec a attach jsou stejne v C i Kotlinu`() {
        val c = cSource()
        val kt = ktSource()
        assertEquals("SH_MODE_EXEC", cDefine(c, "SH_MODE_EXEC"), ktConst(kt, "SH_MODE_EXEC"))
        assertEquals("SH_MODE_ATTACH", cDefine(c, "SH_MODE_ATTACH"), ktConst(kt, "SH_MODE_ATTACH"))
    }

    @Test
    fun `mode stop je stejny v C i Kotlinu`() {
        // SH_MODE_STOP umoznuje `ashell adb stop` ukoncit daemona bez adb
        // (bez wireless debugingu stary `adb shell pkill` tise selhal).
        assertEquals(
            "SH_MODE_STOP se rozesel mezi shell_daemon.c a ShellDaemonClient.kt",
            cDefine(cSource(), "SH_MODE_STOP"),
            ktConst(ktSource(), "SH_MODE_STOP")
        )
    }

    @Test
    fun `stop rezim posila mode byte za magic`() {
        val kt = ktSource()
        val magicIdx = kt.indexOf("out.writeInt(SH_MAGIC)", kt.indexOf("fun stopDaemon"))
        val modeIdx = kt.indexOf("out.writeByte(SH_MODE_STOP)", kt.indexOf("fun stopDaemon"))
        assertTrue("stopDaemon: writeInt(SH_MAGIC) chybi", magicIdx >= 0)
        assertTrue("stopDaemon: writeByte(SH_MODE_STOP) chybi", modeIdx >= 0)
        assertTrue("stopDaemon: mode byte musi nasledovat az za magic", modeIdx > magicIdx)
    }

    @Test
    fun `C daemon ma SIGCHLD reaper proti zombie`() {
        // Fork-per-connection bez reaperu hromadi zombie workery (Z libshelldaemon.).
        val c = cSource()
        assertTrue("sigchld_reaper chybi", c.contains("sigchld_reaper"))
        assertTrue("SIGCHLD handler se neinstaluje", c.contains("sigaction(SIGCHLD"))
        assertTrue("reaper musi volat waitpid", c.contains("waitpid(-1, NULL, WNOHANG)"))
    }

    @Test
    fun `C daemon uklidi PID file pri SIGTERM`() {
        val c = cSource()
        assertTrue("sigterm_cleanup chybi", c.contains("sigterm_cleanup"))
        assertTrue("SIGTERM handler se neinstaluje", c.contains("sigaction(SIGTERM"))
    }

    @Test
    fun `ashell stop a status nevyzaduji adb`() {
        // Regrese: `ashell adb stop` drive volal `adb shell kill` — bez
        // pripojeneho adb tise selhal a daemon bezel dal. Stop i zjisteni
        // stavu musi primarne jit pres LocalApiServer.
        val sh = File("src/main/assets/ashell").readText()
        assertTrue(
            "ashell stop musi volat API /shelldaemon/stop",
            sh.contains("POST \"/shelldaemon/stop\"")
        )
        assertTrue(
            "daemon_alive musi primarne pouzit API /shelldaemon/status",
            sh.contains("daemon_api_json") && sh.contains("/shelldaemon/status")
        )
    }

    @Test
    fun `exec rezim posila mode byte za magic`() {
        val kt = ktSource()
        val magicIdx = kt.indexOf("out.writeInt(SH_MAGIC)")
        val modeIdx = kt.indexOf("out.writeByte(SH_MODE_EXEC)")
        assertTrue("writeInt(SH_MAGIC) chybi", magicIdx >= 0)
        assertTrue("writeByte(SH_MODE_EXEC) chybi", modeIdx >= 0)
        assertTrue("mode byte musi nasledovat az za magic", modeIdx > magicIdx)
    }

    @Test
    fun `C daemon zna --attach klientsky rezim`() {
        val c = cSource()
        assertTrue("main() neparsuje --attach", c.contains("\"--attach\""))
        assertTrue("run_attach_client chybi", c.contains("run_attach_client"))
    }

    @Test
    fun `klient binduje jen na loopback`() {
        val c = cSource()
        assertTrue("daemon musi bindovat INADDR_LOOPBACK, ne 0.0.0.0", c.contains("INADDR_LOOPBACK"))
        assertTrue("0.0.0.0 je v shell_daemon.c zakazano", !c.contains("INADDR_ANY"))
    }
}