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
    fun `boot passes CPU_ALL from environment`() {
        assertTrue(
            "boot nepředává CPU_ALL z env",
            boot.contains("CPU_ALL=\$CPU_ALL"),
        )
    }

    @Test
    fun `boot does not contain inline CPU_ALL whitelist`() {
        assertFalse(
            "boot obsahuje hardcoded CPU_ALL whitelist — měl by být v cpu_all.conf",
            boot.contains("CPU_ALL=make cargo"),
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
