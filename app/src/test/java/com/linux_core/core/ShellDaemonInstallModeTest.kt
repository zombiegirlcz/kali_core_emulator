package com.linux_core.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Strážce streamovaného install režimu a toku tokenu.
 *
 * Dvě věci, které se snadno rozbijí a projeví se až na zařízení:
 *
 * 1. SH_MODE_INSTALL — binární protokol mezi C daemonem a Kotlin klientem.
 *    Když se číslo rozjede na jedné straně, `ashell adb install` tiše spadne
 *    do fallbacku (nebo vrátí "spatny magic").
 *
 * 2. Token daemona — `nativeLibraryDir` je READ-ONLY (system:system 755),
 *    takže tam appka token zapsat NEMŮŽE. Žije ve `filesDir` a vydává se
 *    přes `GET /shelldaemon/info`. Kdyby se někdo vrátil k zápisu do
 *    nativeLibraryDir, `ashell adb start` zase přestane fungovat (EACCES
 *    v logcat, token zůstane "placeholder").
 *
 * Běží z modulu `app` (cwd = app/), stejně jako ShellDaemonProtocolTest.
 */
class ShellDaemonInstallModeTest {

    private fun cSource(): String = File("src/main/cpp/shell_daemon.c").readText()

    private fun ktSource(): String =
        File("src/main/java/com/linux_core/core/ShellDaemonClient.kt").readText()

    private fun apiServer(): String =
        File("src/main/java/com/linux_core/core/LocalApiServer.kt").readText()

    private fun ashell(): String = File("src/main/assets/ashell").readText()

    private fun cDefine(src: String, name: String): Long {
        val re = Regex("""#define\s+$name\s+(0x[0-9A-Fa-f]+|\d+)u?""")
        val m = re.find(src) ?: error("C: #define $name nenalezen")
        return m.groupValues[1].let {
            if (it.startsWith("0x") || it.startsWith("0X")) it.substring(2).toLong(16)
            else it.toLong()
        }
    }

    private fun ktConst(src: String, name: String): Long {
        val re = Regex("""const\s+val\s+$name\s*=\s*(0x[0-9A-Fa-f]+|\d+)""")
        val m = re.find(src) ?: error("Kotlin: const val $name nenalezen")
        return m.groupValues[1].let {
            if (it.startsWith("0x") || it.startsWith("0X")) it.substring(2).toLong(16)
            else it.toLong()
        }
    }

    // ── SH_MODE_INSTALL protokol ──────────────────────────────────────────

    @Test
    fun `SH_MODE_INSTALL je stejny v C i Kotlinu`() {
        assertEquals(
            "SH_MODE_INSTALL se rozesel mezi shell_daemon.c a ShellDaemonClient.kt",
            cDefine(cSource(), "SH_MODE_INSTALL"),
            ktConst(ktSource(), "SH_MODE_INSTALL")
        )
    }

    @Test
    fun `C daemon ma handler handle_install a dispatchuje na nej`() {
        val c = cSource()
        assertTrue("handle_install chybi v shell_daemon.c", c.contains("handle_install"))
        assertTrue(
            "handle_client nedispatchuje SH_MODE_INSTALL",
            Regex("""if\s*\(\s*mode\s*==\s*SH_MODE_INSTALL\s*\)""").containsMatchIn(c)
        )
    }

    @Test
    fun `handle_install spousti cmd package install -S`() {
        val c = cSource()
        assertTrue(
            "install musi jit pres `cmd package install -S <size>` (jako adb)",
            c.contains("cmd package install -S")
        )
    }

    @Test
    fun `klient posle mode byte a velikost APK`() {
        val kt = ktSource()
        assertTrue("klient neposila SH_MODE_INSTALL", kt.contains("out.writeByte(SH_MODE_INSTALL)"))
        assertTrue(
            "klient musi poslat uint64 velikost APK pred streamem",
            Regex("""out\.writeLong\s*\(\s*apkFile\.length\s*\(\s*\)\s*\)""").containsMatchIn(kt)
        )
    }

    @Test
    fun `API ma endpoint POST shelldaemon install`() {
        val api = apiServer()
        assertTrue(
            "/shelldaemon/install route chybi",
            api.contains("\"/shelldaemon/install\"")
        )
        assertTrue(
            "handleShellDaemonInstall chybi",
            api.contains("handleShellDaemonInstall")
        )
    }

    @Test
    fun `ashell adb install jde pres streamovany endpoint`() {
        val a = ashell()
        assertTrue(
            "ashell adb install musi volat /shelldaemon/install",
            a.contains("/shelldaemon/install")
        )
    }

    // ── Token flow ────────────────────────────────────────────────────────

    @Test
    fun `token se zapisuje do filesDir, ne do nativeLibraryDir`() {
        val kt = ktSource()
        assertTrue(
            "tokenFile musi byt ve filesDir (nativeLibraryDir je read-only)",
            Regex("""File\s*\(\s*context\.filesDir\s*,""").containsMatchIn(kt)
        )
        assertFalse(
            "token uz nesmi byt v nativeLibraryDir (EACCES pri zapisu)",
            Regex("""File\s*\(\s*context\.applicationInfo\.nativeLibraryDir\s*,\s*TOKEN""")
                .containsMatchIn(kt)
        )
    }

    @Test
    fun `API vydava token a cestu pres GET shelldaemon info`() {
        val api = apiServer()
        assertTrue(
            "/shelldaemon/info route chybi",
            api.contains("\"/shelldaemon/info\"")
        )
        assertTrue(
            "handleShellDaemonInfo chybi",
            api.contains("handleShellDaemonInfo")
        )
    }

    @Test
    fun `ashell bere token z API, ne jen z libtoken_so`() {
        val a = ashell()
        assertTrue(
            "daemon_token musi zkouset /shelldaemon/info",
            a.contains("/shelldaemon/info")
        )
        assertTrue(
            "daemon_token musi mit rucni override SHELLDAEMON_TOKEN",
            a.contains("SHELLDAEMON_TOKEN")
        )
    }

    @Test
    fun `ashell nepouziva stary mnt app bind pro cestu k daemonu`() {
        val a = ashell()
        assertFalse(
            "cesta k daemonu uz nesmi zaviset na bind_app (/mnt/app)",
            a.contains("/mnt/app/shelldaemon.path")
        )
    }
}