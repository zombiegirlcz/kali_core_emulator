package com.linux_core.core.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Strážce deploy cesty `shell_daemon` → jniLibs.
 *
 * Daemon se NESMÍ vozit jako asset (`assets/shell_daemon`), ale jako spustitelný
 * ELF v `jniLibs/arm64-v8a/libshelldaemon.so`. Android ho při instalaci
 * extrahuje do `applicationInfo.nativeLibraryDir` (díky `useLegacyPackaging=true`),
 * odkud ho guest spustí přes `adb shell` pod uid 2000 — žádný `su`, žádný push.
 *
 * Kdyby někdo vrátil build do assets nebo klienta zpět na `filesDir/shell_daemon`,
 * `ashell adb start` přestane fungovat až za běhu na zařízení. Tenhle test to
 * zachytí v unit testech. Běží z modulu `app` (cwd = app/).
 */
class ShellDaemonDeployTest {

    private fun cSource(): String = File("src/main/cpp/shell_daemon.c").readText()

    private fun clientSource(): String =
        File("src/main/java/com/linux_core/core/terminal/ShellDaemonClient.kt").readText()

    private fun modalBuild(): String = File("../tools/modal_build.py").readText()

    private fun terminalActivity(): String =
        File("src/main/java/com/linux_core/ui/terminal/TerminalActivity.kt").readText()

    @Test
    fun `shell_daemon ma main - je to spustitelny ELF`() {
        val c = cSource()
        assertTrue("shell_daemon.c musi mit int main()", Regex("int\\s+main\\s*\\(").containsMatchIn(c))
    }

    @Test
    fun `build miri do jniLibs pod jmenem libshelldaemon_so`() {
        val py = modalBuild()
        assertTrue(
            "modal_build.py musi produkovat jniLibs/arm64-v8a/libshelldaemon.so",
            py.contains("libshelldaemon.so")
        )
        assertFalse(
            "build shell_daemonu uz nesmi koncit v assets/shell_daemon",
            py.contains("app/src/main/assets/shell_daemon")
        )
    }

    @Test
    fun `klient bere binarku z nativeLibraryDir, ne z filesDir`() {
        val kt = clientSource()
        assertTrue(
            "ShellDaemonClient musi znat nativeLibraryDir",
            kt.contains("nativeLibraryDir")
        )
        assertTrue(
            "ShellDaemonClient musi mit konstantu SO_NAME = libshelldaemon.so",
            kt.contains("libshelldaemon.so")
        )
        assertFalse(
            "ShellDaemonClient uz nesmi odkazovat na ASSET_BIN shell_daemon",
            kt.contains("ASSET_BIN")
        )
    }

    @Test
    fun `klient nezkousi su cestu pro start`() {
        val kt = clientSource()
        assertFalse(
            "startDaemon nesmi volat su (uid 2000 spousti guest pres adb)",
            kt.contains("ShizukuManager.suPath()")
        )
    }

    @Test
    fun `terminal spousti attach z nativeLibraryDir`() {
        val ta = terminalActivity()
        assertTrue(
            "TerminalActivity musi brat daemon binarku z ShellDaemonClient.binaryPath",
            ta.contains("ShellDaemonClient.binaryPath")
        )
        assertFalse(
            "TerminalActivity uz nesmi hledat filesDir/shell_daemon",
            ta.contains("File(filesDir, \"shell_daemon\")")
        )
    }
}
