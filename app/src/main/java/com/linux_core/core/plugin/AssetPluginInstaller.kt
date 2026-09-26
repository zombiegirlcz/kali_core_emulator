package com.linux_core.core.plugin

import com.linux_core.sdk.plugin.PluginManifest
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

sealed class AssetPluginInstallResult {
    data class Success(val installDir: File) : AssetPluginInstallResult()
    data class Failure(val reason: String) : AssetPluginInstallResult()
}

/**
 * Instaluje asset plugin (`.tar.xz` payload, viz `PluginManifest`/`fdroid/index.json`)
 * do `<pluginsDir>/<name>/`. Fáze 3 z docs/plans/2026-09-26-plugin-system-design.md.
 *
 * Vzor extrakce/SHA256 ověření je stejný jako `RootfsManager` (bez sdílené abstrakce —
 * `RootfsManager` je bezpečnostně citlivý existující kód, viz `AGENTS.md`; tento installer
 * je nezávislý nový kód, aby se nemusel upravovat).
 *
 * Co ZATÍM NEDĚLÁ (mimo rozsah této třídy):
 * - fetch payloadu ze sítě (to dělá volající kód, viz `RemotePluginCatalog`),
 * - ověření podpisu `fdroid/index.json` (TODO Fáze 9),
 * - symlink extrahovaných [PluginManifest.files] do `nh.d`/`boot.d` a guest deploy
 *   (vyžaduje `ProotManager` — TODO, dosud nezapojeno).
 */
object AssetPluginInstaller {

    fun install(
        manifest: PluginManifest,
        payload: File,
        expectedSha256: String,
        pluginsDir: File,
    ): AssetPluginInstallResult {
        if (!payload.exists()) {
            return AssetPluginInstallResult.Failure("Payload neexistuje: $payload")
        }

        val actualSha = sha256Of(payload)
        if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
            return AssetPluginInstallResult.Failure(
                "SHA256 mismatch: expected $expectedSha256, got $actualSha",
            )
        }

        val installDir = File(pluginsDir, manifest.name)
        return try {
            if (installDir.exists()) installDir.deleteRecursively()
            installDir.mkdirs()
            extractTarXz(payload, installDir, allowedFiles = manifest.files)
            AssetPluginInstallResult.Success(installDir)
        } catch (e: Exception) {
            installDir.deleteRecursively()
            AssetPluginInstallResult.Failure("Extrakce selhala: ${e.message}")
        }
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Zip-slip pojistka: entry mimo cílový strom se zahodí, ne rozbalí.
     *
     * Pokud [allowedFiles] není null (manifest deklaruje `NH_PLUGIN_FILES`), rozbalí se
     * jen adresáře a přesně tyto soubory — payload nemůže propašovat nic navíc, ani
     * kdyby prošel SHA256 kontrolou (obrana do hloubky, ne náhrada za podpis z Fáze 9).
     */
    private fun extractTarXz(source: File, targetDir: File, allowedFiles: List<String>?) {
        val canonicalBase = targetDir.canonicalPath
        val allowedSet = allowedFiles?.toSet()
        FileInputStream(source).use { fis ->
            BufferedInputStream(fis, 512 * 1024).use { bis ->
                XZCompressorInputStream(bis).use { xzIn ->
                    TarArchiveInputStream(xzIn).use { tarIn ->
                        var entry: ArchiveEntry? = tarIn.nextEntry
                        while (entry != null) {
                            val tarEntry = entry as TarArchiveEntry
                            val entryFile = File(targetDir, tarEntry.name)
                            val canonicalDest = entryFile.canonicalPath
                            val insideTarget =
                                canonicalDest == canonicalBase ||
                                    canonicalDest.startsWith(canonicalBase + File.separator)
                            val allowedByManifest =
                                allowedSet == null || tarEntry.isDirectory || allowedSet.contains(tarEntry.name)
                            if (!insideTarget || !allowedByManifest) {
                                entry = tarIn.nextEntry
                                continue
                            }
                            if (tarEntry.isDirectory) {
                                entryFile.mkdirs()
                            } else {
                                entryFile.parentFile?.mkdirs()
                                FileOutputStream(entryFile).use { out -> tarIn.copyTo(out) }
                                val ownerExecuteBit = 64 // 0o100
                                if (tarEntry.mode and ownerExecuteBit != 0) {
                                    entryFile.setExecutable(true)
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
        }
    }
}
