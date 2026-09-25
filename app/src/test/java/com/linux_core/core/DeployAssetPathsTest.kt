package com.linux_core.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Strážce cest k assetům, které ProotManager otevírá přes `context.assets.open(...)`.
 * Rozbitá cesta (přesun bez aktualizace deploye) se projeví až na zařízení za
 * běhu — tenhle test to zachytí už v unit testech.
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
    fun `shizuku assety uz nejsou v APK`() {
        for (rel in listOf(
            "usr/bin/rish.sh",
            "usr/bin/rish_shizuku.dex",
            "usr/lib/librish.so",
            "usr/lib/libshizuku.so",
            "usr/lib/shizuku.apk",
        )) {
            assertTrue("shizuku asset se nesmi vracet: $rel", !assetFile(rel).exists())
        }
    }

    @Test
    fun `proot a loader static jsou v usr bin pro vsechny ABI`() {
        for (abi in listOf("aarch64", "arm", "i686", "x86_64")) {
            assertAsset("usr/bin/proot-static-$abi")
            assertAsset("usr/bin/loader-static-$abi")
        }
    }
}
