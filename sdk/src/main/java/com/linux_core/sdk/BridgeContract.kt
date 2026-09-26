package com.linux_core.sdk

/**
 * Verze `ICoreBridge` AIDL kontraktu. Zvyš při KAŽDÉ breaking change (přidání/odebrání/
 * změna signatury metody). Plugin deklaruje `NH_PLUGIN_BRIDGE_API` jako min. verzi, kterou
 * potřebuje — `CoreBridgeService.getStatus()` ji vrací v `bridge_version` poli.
 */
object BridgeContract {
    const val VERSION = 1
}
