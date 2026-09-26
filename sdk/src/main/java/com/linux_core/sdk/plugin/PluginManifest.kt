package com.linux_core.sdk.plugin

/** `NH_PLUGIN_KIND` — určuje instalační cestu (viz `nh plugin install`). */
enum class PluginKind {
    ASSET,
    APP,
    ROOT,
    ;

    companion object {
        fun fromWire(value: String): PluginKind? = when (value.trim().lowercase()) {
            "asset" -> ASSET
            "app" -> APP
            "root" -> ROOT
            else -> null
        }
    }
}

/**
 * Rozparsovaný `.nh/plugin` manifest (plochý KEY=VALUE, stejný styl jako `.nh/manifest`
 * u rootfs katalogu). Viz docs/plans/2026-09-26-plugin-system-design.md.
 *
 * Pole specifická pro `kind` (asset: [files], [nhDispatch], [hookInstall], [hookRemove];
 * app: [pkg], [apiProbe]; root: [magiskId]) jsou null, pokud manifest daný `kind` nemá.
 */
data class PluginManifest(
    val name: String,
    val version: String,
    val kind: PluginKind,
    val needsRoot: Boolean,
    val deps: List<String>,
    val minCore: Int,
    val maxCore: Int?,
    val bridgeApi: Int,
    val abi: String,
    val files: List<String>?,
    val nhDispatch: String?,
    val hookInstall: String?,
    val hookRemove: String?,
    val pkg: String?,
    val apiProbe: String?,
    val magiskId: String?,
) {
    companion object {
        private const val PREFIX = "NH_PLUGIN_"

        /**
         * Parsuje obsah `.nh/plugin`. Prázdné řádky a `#` komentáře se ignorují.
         * Vrací null, pokud manifestu chybí některé z povinných polí
         * (NAME/VERSION/KIND/MIN_CORE/BRIDGE_API/ABI) nebo KIND má neznámou hodnotu.
         */
        fun parse(content: String): PluginManifest? {
            val fields = mutableMapOf<String, String>()
            content.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEach
                val key = line.substring(0, eq).trim()
                if (!key.startsWith(PREFIX)) return@forEach
                val value = line.substring(eq + 1).trim()
                fields[key.removePrefix(PREFIX)] = value
            }

            val name = fields["NAME"]?.takeIf { it.isNotEmpty() } ?: return null
            val version = fields["VERSION"]?.takeIf { it.isNotEmpty() } ?: return null
            val kind = fields["KIND"]?.let { PluginKind.fromWire(it) } ?: return null
            val minCore = fields["MIN_CORE"]?.toIntOrNull() ?: return null
            val bridgeApi = fields["BRIDGE_API"]?.toIntOrNull() ?: return null
            val abi = fields["ABI"]?.takeIf { it.isNotEmpty() } ?: return null

            return PluginManifest(
                name = name,
                version = version,
                kind = kind,
                needsRoot = fields["NEEDS_ROOT"] == "1",
                deps = fields["DEPS"]?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                minCore = minCore,
                maxCore = fields["MAX_CORE"]?.toIntOrNull(),
                bridgeApi = bridgeApi,
                abi = abi,
                files = fields["FILES"]?.split(" ")?.filter { it.isNotBlank() },
                nhDispatch = fields["NH_DISPATCH"],
                hookInstall = fields["HOOK_INSTALL"],
                hookRemove = fields["HOOK_REMOVE"],
                pkg = fields["PKG"],
                apiProbe = fields["API_PROBE"],
                magiskId = fields["MAGISK_ID"],
            )
        }
    }

    /** Jádro s tímto `versionCode` a `bridgeVersion` umí tento plugin nainstalovat/spustit. */
    fun isCompatibleWith(coreVersionCode: Int, coreBridgeVersion: Int): Boolean {
        if (coreVersionCode < minCore) return false
        if (maxCore != null && coreVersionCode > maxCore) return false
        if (coreBridgeVersion < bridgeApi) return false
        return true
    }
}
