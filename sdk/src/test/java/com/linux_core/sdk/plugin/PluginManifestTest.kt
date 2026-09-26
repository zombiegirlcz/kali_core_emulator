package com.linux_core.sdk.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestTest {

    @Test
    fun `parses full app plugin manifest`() {
        val content = """
            NH_PLUGIN_NAME=vpn
            NH_PLUGIN_VERSION=4.5.0
            NH_PLUGIN_KIND=app
            NH_PLUGIN_NEEDS_ROOT=0
            NH_PLUGIN_DEPS=
            NH_PLUGIN_MIN_CORE=20
            NH_PLUGIN_MAX_CORE=
            NH_PLUGIN_BRIDGE_API=1
            NH_PLUGIN_ABI=aarch64
            NH_PLUGIN_PKG=com.linux_core.vpn
            NH_PLUGIN_API_PROBE=/vpn/status
        """.trimIndent()

        val m = PluginManifest.parse(content)
        requireNotNull(m)
        assertEquals("vpn", m.name)
        assertEquals("4.5.0", m.version)
        assertEquals(PluginKind.APP, m.kind)
        assertFalse(m.needsRoot)
        assertTrue(m.deps.isEmpty())
        assertEquals(20, m.minCore)
        assertNull(m.maxCore)
        assertEquals(1, m.bridgeApi)
        assertEquals("aarch64", m.abi)
        assertEquals("com.linux_core.vpn", m.pkg)
        assertEquals("/vpn/status", m.apiProbe)
        assertNull(m.files)
        assertNull(m.magiskId)
    }

    @Test
    fun `parses asset plugin manifest with files and deps`() {
        val content = """
            # cpu asset plugin
            NH_PLUGIN_NAME=cpu
            NH_PLUGIN_VERSION=1.2.0
            NH_PLUGIN_KIND=asset
            NH_PLUGIN_NEEDS_ROOT=0
            NH_PLUGIN_DEPS=usb fix
            NH_PLUGIN_MIN_CORE=20
            NH_PLUGIN_BRIDGE_API=1
            NH_PLUGIN_ABI=any
            NH_PLUGIN_FILES=nh.d/cpu boot.d/cpu.sh bin/cpuctl
            NH_PLUGIN_NH_DISPATCH=cpu
            NH_PLUGIN_HOOK_INSTALL=hooks/install.sh
            NH_PLUGIN_HOOK_REMOVE=hooks/remove.sh
        """.trimIndent()

        val m = PluginManifest.parse(content)
        requireNotNull(m)
        assertEquals(PluginKind.ASSET, m.kind)
        assertEquals(listOf("usb", "fix"), m.deps)
        assertEquals(listOf("nh.d/cpu", "boot.d/cpu.sh", "bin/cpuctl"), m.files)
        assertEquals("cpu", m.nhDispatch)
        assertEquals("hooks/install.sh", m.hookInstall)
        assertEquals("hooks/remove.sh", m.hookRemove)
    }

    @Test
    fun `parses root plugin manifest with magisk id`() {
        val content = """
            NH_PLUGIN_NAME=nh_cpuctl
            NH_PLUGIN_VERSION=1.0.0
            NH_PLUGIN_KIND=root
            NH_PLUGIN_NEEDS_ROOT=1
            NH_PLUGIN_MIN_CORE=20
            NH_PLUGIN_BRIDGE_API=1
            NH_PLUGIN_ABI=aarch64
            NH_PLUGIN_MAGISK_ID=nh_cpuctl
        """.trimIndent()

        val m = PluginManifest.parse(content)
        requireNotNull(m)
        assertEquals(PluginKind.ROOT, m.kind)
        assertTrue(m.needsRoot)
        assertEquals("nh_cpuctl", m.magiskId)
    }

    @Test
    fun `missing required field yields null`() {
        assertNull(PluginManifest.parse(""))
        assertNull(
            PluginManifest.parse(
                """
                NH_PLUGIN_NAME=vpn
                NH_PLUGIN_VERSION=4.5.0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `unknown kind yields null`() {
        val content = """
            NH_PLUGIN_NAME=vpn
            NH_PLUGIN_VERSION=4.5.0
            NH_PLUGIN_KIND=widget
            NH_PLUGIN_MIN_CORE=20
            NH_PLUGIN_BRIDGE_API=1
            NH_PLUGIN_ABI=any
        """.trimIndent()
        assertNull(PluginManifest.parse(content))
    }

    @Test
    fun `isCompatibleWith enforces min and max core plus bridge version`() {
        val m = PluginManifest(
            name = "vpn",
            version = "4.5.0",
            kind = PluginKind.APP,
            needsRoot = false,
            deps = emptyList(),
            minCore = 20,
            maxCore = 25,
            bridgeApi = 2,
            abi = "any",
            files = null,
            nhDispatch = null,
            hookInstall = null,
            hookRemove = null,
            pkg = "com.linux_core.vpn",
            apiProbe = null,
            magiskId = null,
        )

        assertFalse(m.isCompatibleWith(coreVersionCode = 19, coreBridgeVersion = 2))
        assertFalse(m.isCompatibleWith(coreVersionCode = 26, coreBridgeVersion = 2))
        assertFalse(m.isCompatibleWith(coreVersionCode = 20, coreBridgeVersion = 1))
        assertTrue(m.isCompatibleWith(coreVersionCode = 20, coreBridgeVersion = 2))
        assertTrue(m.isCompatibleWith(coreVersionCode = 25, coreBridgeVersion = 3))
    }
}
