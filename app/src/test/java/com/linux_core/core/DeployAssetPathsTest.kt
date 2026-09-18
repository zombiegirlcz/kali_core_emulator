package com.linux_core.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Strážce cest k assetům, které ProotManager / ShizukuManager otevírají přes
 * `context.assets.open(...)`. Rozbitá cesta (přesun bez aktualizace deploye)
 * se projeví až na zařízení za běhu — tenhle test to zachytí už v unit testech.
 *
 * Cesty zrcadlí konstanty v ShizukuManager (ASSET_SERVER / ASSET_RISH_DEX /
 * ASSET_APK) a volání deployArchAsset/deployShizukuRish v ProotManager.
 *
 * Pozn.: běží z modulu `app` (cwd = app/), stejně jako SymbolScannerTest
 * používající `src/main/jniLibs/...`.
 */
class DeployAssetPathsTest {

    private fun assetFile(rel: String): File = File("src/main/assets/$rel")

    private fun assertAsset(rel: String) {
        val f = assetFile(rel)
        assertNotNull("asset chybí: $rel", f)
        assertTrue("asset neexistuje: $rel (${f.absolutePath})", f.isFile)
        assertTrue("asset je prázdný: $rel", f.length() > 0L)
    }

    @Test
    fun `shizuku rish shell a dex jsou v usr bin`() {
        assertAsset("usr/bin/rish.sh")
        assertAsset("usr/bin/rish_shizuku.dex")
    }

    @Test
    fun `shizuku nativni knihovny jsou v usr lib`() {
        assertAsset("usr/lib/libshizuku.so")
        assertAsset("usr/lib/librish.so")
        assertAsset("usr/lib/libadb.so")
    }

    @Test
    fun `bundled shizuku apk je v usr lib`() {
        assertAsset("usr/lib/shizuku.apk")
    }

    @Test
    fun `proot a loader static jsou v usr bin pro vsechny ABI`() {
        for (abi in listOf("aarch64", "arm", "i686", "x86_64")) {
            assertAsset("usr/bin/proot-static-$abi")
            assertAsset("usr/bin/loader-static-$abi")
        }
    }
}