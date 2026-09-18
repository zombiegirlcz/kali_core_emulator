package com.linux_core.core

import rikka.shizuku.ShizukuProvider

/**
 * Tenký wrapper nad [ShizukuProvider], který vypne automatickou Sui inicializaci.
 *
 * Proč: `ShizukuProvider.onCreate()` defaultně volá `Sui.init(packageName)`, což
 * jde přes `SystemServiceHelper.getSystemService("activity")` — reflexe na
 * `android.os.ServiceManager.getService`, která je od Androidu 9 na hidden-API
 * blacklistu. Když reflexe selže, `getService` zůstane null a `invoke()` vyhodí
 * NPE, kterou `SystemServiceHelper` nechytá → crash při inicializaci provideru
 * (app se nespustí).
 *
 * Sui je Magisk modul (injektuje server do system_serveru) — my ho nepoužíváme,
 * máme vlastní bundlovaný Shizuku server. Vypnutí je bezpečné a je to i
 * doporučený postup (stejně to dělá oficiální Shizuku manager).
 *
 * Manifest musí odkazovat na tuhle třídu, ne přímo na `rikka.shizuku.ShizukuProvider`.
 */
class LinuxCoreShizukuProvider : ShizukuProvider() {
    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }
}