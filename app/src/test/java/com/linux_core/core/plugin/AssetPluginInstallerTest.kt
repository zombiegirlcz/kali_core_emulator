package com.linux_core.core.plugin

import com.linux_core.sdk.plugin.PluginKind
import com.linux_core.sdk.plugin.PluginManifest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class AssetPluginInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manifest = PluginManifest(
        name = "cpu",
        version = "1.2.0",
        kind = PluginKind.ASSET,
        needsRoot = false,
        deps = emptyList(),
        minCore = 20,
        maxCore = null,
        bridgeApi = 1,
        abi = "any",
        files = listOf("nh.d/cpu", "boot.d/cpu.sh"),
        nhDispatch = "cpu",
        hookInstall = null,
        hookRemove = null,
        pkg = null,
        apiProbe = null,
        magiskId = null,
    )

    private fun buildTarXzFixture(dest: File, entries: Map<String, String>) {
        FileOutputStream(dest).use { fos ->
            XZCompressorOutputStream(fos).use { xz ->
                TarArchiveOutputStream(xz).use { tar ->
                    for ((name, content) in entries) {
                        val bytes = content.toByteArray()
                        val entry = TarArchiveEntry(name)
                        entry.size = bytes.size.toLong()
                        entry.mode = 493 // 0o755 rwxr-xr-x
                        tar.putArchiveEntry(entry)
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.readBytes())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `installs valid tar xz payload and preserves executable bit`() {
        val payload = tmp.newFile("cpu-1.2.0.tar.xz")
        buildTarXzFixture(
            payload,
            mapOf(
                "nh.d/cpu" to "cpu_dispatch() { echo hi; }\n",
                "boot.d/cpu.sh" to "#!/bin/sh\necho pin\n",
            ),
        )
        val pluginsDir = tmp.newFolder("plugins")

        val result = AssetPluginInstaller.install(
            manifest = manifest,
            payload = payload,
            expectedSha256 = sha256(payload),
            pluginsDir = pluginsDir,
        )

        val success = result as? AssetPluginInstallResult.Success
        assertTrue("expected Success, got $result", success != null)
        val installDir = success!!.installDir
        assertEquals(File(pluginsDir, "cpu"), installDir)

        val nhFragment = File(installDir, "nh.d/cpu")
        assertTrue(nhFragment.exists())
        assertEquals("cpu_dispatch() { echo hi; }\n", nhFragment.readText())
        assertTrue("nh.d/cpu should be executable", nhFragment.canExecute())

        val bootFragment = File(installDir, "boot.d/cpu.sh")
        assertTrue(bootFragment.exists())
    }

    @Test
    fun `rejects payload with sha256 mismatch and leaves no partial install`() {
        val payload = tmp.newFile("cpu-1.2.0.tar.xz")
        buildTarXzFixture(payload, mapOf("nh.d/cpu" to "irrelevant"))
        val pluginsDir = tmp.newFolder("plugins")

        val result = AssetPluginInstaller.install(
            manifest = manifest,
            payload = payload,
            expectedSha256 = "0".repeat(64),
            pluginsDir = pluginsDir,
        )

        assertTrue(result is AssetPluginInstallResult.Failure)
        assertFalse(File(pluginsDir, "cpu").exists())
    }

    @Test
    fun `zip-slip entry never lands outside install dir, whatever the outcome`() {
        val payload = tmp.newFile("evil.tar.xz")
        buildTarXzFixture(
            payload,
            mapOf(
                "../../evil.sh" to "rm -rf /\n",
                "nh.d/cpu" to "ok",
            ),
        )
        val pluginsDir = tmp.newFolder("plugins")

        // Ať extrakce dopadne jakkoli (Success s odfiltrovaným entry, nebo Failure
        // pokud to commons-compress odmítne rovnou) — hlavní invariant je, že
        // ".." entry NIKDY nepřistane mimo pluginsDir.
        AssetPluginInstaller.install(
            manifest = manifest,
            payload = payload,
            expectedSha256 = sha256(payload),
            pluginsDir = pluginsDir,
        )

        assertFalse(File(pluginsDir.parentFile, "evil.sh").exists())
        assertFalse(File(tmp.root, "evil.sh").exists())
    }

    @Test
    fun `files not declared in manifest are dropped even with correct sha256`() {
        val payload = tmp.newFile("cpu-1.2.0.tar.xz")
        buildTarXzFixture(
            payload,
            mapOf(
                "nh.d/cpu" to "cpu_dispatch() { echo hi; }\n",
                "boot.d/cpu.sh" to "#!/bin/sh\necho pin\n",
                "sneaky/extra.sh" to "not declared in manifest.files\n",
            ),
        )
        val pluginsDir = tmp.newFolder("plugins")

        val result = AssetPluginInstaller.install(
            manifest = manifest,
            payload = payload,
            expectedSha256 = sha256(payload),
            pluginsDir = pluginsDir,
        )

        val success = result as? AssetPluginInstallResult.Success
        assertTrue("expected Success, got $result", success != null)
        assertTrue(File(success!!.installDir, "nh.d/cpu").exists())
        assertFalse(File(success.installDir, "sneaky/extra.sh").exists())
    }

    @Test
    fun `sha256Of matches independently computed digest`() {
        val payload = tmp.newFile("data.bin")
        payload.writeBytes(ByteArrayOutputStream().apply { write(byteArrayOf(1, 2, 3, 4, 5)) }.toByteArray())
        assertEquals(sha256(payload), AssetPluginInstaller.sha256Of(payload))
    }
}
