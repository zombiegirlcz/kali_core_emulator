package com.linux_core.core.rootfs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CpuAllWhitelistTest {

    private val confFile = File("src/main/assets/usr/share/nh/cpu_all.conf")

    @Test
    fun `cpu_all conf exists and is not empty`() {
        assertTrue("cpu_all.conf chybí: ${confFile.absolutePath}", confFile.isFile)
        assertTrue("cpu_all.conf je prázdný", confFile.length() > 0L)
    }

    @Test
    fun `conf contains expected core programs`() {
        val entries = parseConf()
        for (prog in listOf("make", "cargo", "gcc", "john", "nmap", "python3", "node")) {
            assertTrue("chybí program: $prog", prog in entries)
        }
    }

    @Test
    fun `conf has no empty or whitespace-only entries`() {
        val lines = confFile.readLines()
            .filter { !it.startsWith("#") && it.isNotBlank() }
        for (line in lines) {
            assertFalse("řádek obsahuje mezery: '$line'", line.contains(" "))
            assertTrue("prázdný řádek prošel filtrem", line.trim().isNotEmpty())
        }
    }

    @Test
    fun `conf entries are unique`() {
        val entries = parseConf()
        val dupes = entries.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("duplikáty: $dupes", dupes.isEmpty())
    }

    @Test
    fun `zshrc kali loads cpu_all conf`() {
        val zshrc = File("src/main/assets/zshrc.kali").readText()
        assertTrue("zshrc.kali nenačítá cpu_all.conf", zshrc.contains("cpu_all.conf"))
        assertTrue("zshrc.kali neexportuje CPU_ALL", zshrc.contains("export CPU_ALL"))
    }

    @Test
    fun `zshrc parrot loads cpu_all conf`() {
        val zshrc = File("src/main/assets/zshrc.parrot").readText()
        assertTrue("zshrc.parrot nenačítá cpu_all.conf", zshrc.contains("cpu_all.conf"))
        assertTrue("zshrc.parrot neexportuje CPU_ALL", zshrc.contains("export CPU_ALL"))
    }

    @Test
    fun `boot guest_env does not hardcode CPU_ALL list`() {
        val boot = File("src/main/assets/usr/bin/boot").readText()
        assertFalse(
            "boot stále obsahuje inline CPU_ALL whitelist",
            boot.contains("CPU_ALL=make cargo"),
        )
    }

    private fun parseConf(): List<String> =
        confFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
}
