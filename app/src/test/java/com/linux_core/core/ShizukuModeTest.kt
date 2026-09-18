package com.linux_core.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit testy pro ShizukuManager — režim privilege eskalace.
 *
 * `nh shi start --root|--shell` přepíná, pod jakým UID se spouštějí privilegované
 * příkazy z PRoot guesta:
 *   --root  → `su 0 -c <cmd>`      (uid 0, plná práva)
 *   --shell → `su 2000 -c <cmd>`   (uid 2000, stejná práva jako Shizuku/adb)
 *   (žádný) → Shizuku server       (non-root cesta přes rish; vyžaduje ShizukuProvider)
 *
 * Testy pokrývají:
 *   - buildExecCommand: přesné argv pro každý režim (žádná shell injection)
 *   - parseMode: mapování CLI přepínačů na režim
 *   - buildSuArgv: `su <uid> -c <cmd>` správně sestavené pro oba režimy
 */
class ShizukuModeTest {

    private val suBin = "/product/bin/su"

    @Test
    fun `root mode builds su 0 -c argv`() {
        val argv = ShizukuManager.buildSuArgv(suBin, ShizukuMode.ROOT, "pm list packages")
        assertEquals(listOf(suBin, "0", "-c", "pm list packages"), argv)
    }

    @Test
    fun `shell mode builds su 2000 -c argv`() {
        val argv = ShizukuManager.buildSuArgv(suBin, ShizukuMode.SHELL, "settings get global airplane_mode_on")
        assertEquals(listOf(suBin, "2000", "-c", "settings get global airplane_mode_on"), argv)
    }

    @Test
    fun `none mode yields no su argv`() {
        val argv = ShizukuManager.buildSuArgv(suBin, ShizukuMode.NONE, "id")
        assertEquals(emptyList<String>(), argv)
    }

    @Test
    fun `command with special chars is a single argv element`() {
        // Ověření, že se cmd NEsplitne po mezerách — su -c dostane celý string.
        val cmd = "echo 'hello world' && ls -la /data/system | head -3"
        val argv = ShizukuManager.buildSuArgv(suBin, ShizukuMode.SHELL, cmd)
        assertEquals(suBin, argv[0])
        assertEquals("2000", argv[1])
        assertEquals("-c", argv[2])
        assertEquals(cmd, argv[3])
        assertEquals(4, argv.size)
    }

    @Test
    fun `parseMode accepts --root`() {
        assertEquals(ShizukuMode.ROOT, ShizukuManager.parseMode("--root"))
        assertEquals(ShizukuMode.ROOT, ShizukuManager.parseMode("root"))
    }

    @Test
    fun `parseMode accepts --shell`() {
        assertEquals(ShizukuMode.SHELL, ShizukuManager.parseMode("--shell"))
        assertEquals(ShizukuMode.SHELL, ShizukuManager.parseMode("shell"))
    }

    @Test
    fun `parseMode returns null for unknown`() {
        assertNull(ShizukuManager.parseMode("--nonsense"))
        assertNull(ShizukuManager.parseMode(""))
    }

    @Test
    fun `describe gives human readable label`() {
        assertEquals("root (uid 0)", ShizukuMode.ROOT.describe())
        assertEquals("shell (uid 2000)", ShizukuMode.SHELL.describe())
        assertEquals("none (Shizuku server)", ShizukuMode.NONE.describe())
    }
}