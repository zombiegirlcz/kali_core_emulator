package com.linux_core.core.rootfs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CpuBootScriptTest {

    private val boot = File("src/main/assets/usr/bin/boot").readText()

    @Test
    fun `boot contains cpu_pin_apply function`() {
        assertTrue("cpu_pin_apply() chybí v boot", boot.contains("cpu_pin_apply()"))
    }

    @Test
    fun `boot contains cpu_pin_isolate_host function`() {
        assertTrue("cpu_pin_isolate_host() chybí v boot", boot.contains("cpu_pin_isolate_host()"))
    }

    @Test
    fun `boot propaguje CPU_ALL z env`() {
        assertTrue(
            "boot nereferencuje \$CPU_ALL v guest_env",
            boot.contains("CPU_ALL=\$(echo \"\$CPU_ALL\""),
        )
    }

    @Test
    fun `boot prevadi mezery na carky v CPU_ALL`() {
        assertTrue(
            "boot nekonvertuje mezery→čárky (guest_env se word-splittuje)",
            boot.contains("tr ' ' ','"),
        )
    }

    @Test
    fun `boot neobsahuje inline CPU_ALL whitelist`() {
        assertFalse(
            "boot obsahuje hardcoded CPU_ALL whitelist — měl by být v cpu_all.conf",
            boot.contains("CPU_ALL=make cargo") || boot.contains("CPU_ALL=make,cargo"),
        )
    }

    @Test
    fun `boot reads cpuctld heartbeat`() {
        assertTrue(
            "boot neobsahuje čtení cpuctld heartbeat",
            boot.contains("cpuctld"),
        )
    }

    @Test
    fun `boot uses NH_CPU_PIN env variable`() {
        assertTrue(
            "boot nepoužívá NH_CPU_PIN",
            boot.contains("NH_CPU_PIN"),
        )
    }
}
