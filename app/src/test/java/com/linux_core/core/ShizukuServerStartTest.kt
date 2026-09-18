package com.linux_core.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit testy pro strategii startu Shizuku serveru (non-root cesta, `nh shi start --none`).
 *
 * Bez tohoto by se nefunkční non-root cesta neprojevila jinak než na zařízení —
 * dřívější `startWithAdb()` jen zalogoval příkaz a vrátil true (nikdy nic nespustil),
 * takže rish v guestu dostával null binder a hlásil `Server is not running`.
 *
 * Testy pokrývají:
 *   - [ShizukuManager.planServerStart] — rozhodnutí podle dostupnosti su / adb
 *   - [ShizukuManager.buildSuServerStartCommands] — příkazy pro přenos do /data/local/tmp
 *   - [ShizukuManager.buildSuServerStartCommand] — spuštění serveru s --apk
 */
class ShizukuServerStartTest {

    @Test
    fun `already running short circuits`() {
        assertEquals(
            ServerStartPlan.ALREADY_RUNNING,
            ShizukuManager.planServerStart(running = true, suAvailable = true, adbAvailable = true),
        )
    }

    @Test
    fun `su available picks VIA_SU`() {
        assertEquals(
            ServerStartPlan.VIA_SU,
            ShizukuManager.planServerStart(running = false, suAvailable = true, adbAvailable = false),
        )
        assertEquals(
            ServerStartPlan.VIA_SU,
            ShizukuManager.planServerStart(running = false, suAvailable = true, adbAvailable = true),
        )
    }

    @Test
    fun `adb without su picks VIA_ADB`() {
        assertEquals(
            ServerStartPlan.VIA_ADB,
            ShizukuManager.planServerStart(running = false, suAvailable = false, adbAvailable = true),
        )
    }

    @Test
    fun `neither yields UNAVAILABLE`() {
        assertEquals(
            ServerStartPlan.UNAVAILABLE,
            ShizukuManager.planServerStart(running = false, suAvailable = false, adbAvailable = false),
        )
    }

    @Test
    fun `su commands copy server and apk into data local tmp`() {
        val cmds = ShizukuManager.buildSuServerStartCommands("/data/user/0/com.linux_core/files")
        assertEquals(4, cmds.size)
        assertTrue(cmds[0].startsWith("cp "))
        assertTrue(cmds[0].contains("/data/user/0/com.linux_core/files/shizuku-server"))
        assertTrue(cmds[0].contains("/data/local/tmp/shizuku-server"))
        assertTrue(cmds[1].startsWith("cp "))
        assertTrue(cmds[1].contains("/data/user/0/com.linux_core/files/shizuku.apk"))
        assertTrue(cmds[1].contains("/data/local/tmp/shizuku.apk"))
        assertTrue(cmds[2].startsWith("chmod 755 /data/local/tmp/shizuku-server"))
        assertTrue(cmds[3].startsWith("chmod 644 /data/local/tmp/shizuku.apk"))
    }

    @Test
    fun `su start command runs server with apk flag`() {
        val cmd = ShizukuManager.buildSuServerStartCommand()
        assertTrue(cmd.contains("/data/local/tmp/shizuku-server"))
        assertTrue(cmd.contains("--apk=/data/local/tmp/shizuku.apk"))
        // Server běží pod shell UID (ne root) — proto v startViaSu voláme `su 2000`.
        assertEquals(2000, ShizukuMode.SHELL.uid)
    }

    @Test
    fun `tmp dir override is honoured`() {
        val cmds = ShizukuManager.buildSuServerStartCommands("/x", tmpDir = "/opt/shz")
        assertTrue(cmds[0].endsWith("/opt/shz/shizuku-server"))
        assertTrue(cmds[1].endsWith("/opt/shz/shizuku.apk"))
        assertTrue(ShizukuManager.buildSuServerStartCommand("/opt/shz").contains("--apk=/opt/shz/shizuku.apk"))
    }
}