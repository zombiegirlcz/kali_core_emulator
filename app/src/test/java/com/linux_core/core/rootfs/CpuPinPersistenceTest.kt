package com.linux_core.core.rootfs

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuPinPersistenceTest {

    @Test
    fun `cpuPinKey for kali returns kali`() {
        assertEquals("kali", cpuPinKey("kali"))
    }

    @Test
    fun `cpuPinKey for parrot returns parrot`() {
        assertEquals("parrot", cpuPinKey("parrot"))
    }

    @Test
    fun `cpuPinKey for docker images all share one key`() {
        assertEquals("docker", cpuPinKey("docker"))
        assertEquals("docker", cpuPinKey("docker:alpine"))
        assertEquals("docker", cpuPinKey("docker:ubuntu:22.04"))
    }

    @Test
    fun `cpuPinKey for custom distro returns as-is`() {
        assertEquals("custom", cpuPinKey("custom"))
    }
}
