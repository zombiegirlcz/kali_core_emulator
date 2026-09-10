package com.linux_core.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Boot mode persistence.
 *
 * Current scheme:
 *   D = default (full)   -> NH_ISOLATED=0, NH_MINIMAL=0
 *   I = isolated         -> NH_ISOLATED=1, NH_MINIMAL=0
 *   M = minimal          -> NH_ISOLATED=1, NH_MINIMAL=1
 *
 * Legacy scheme (before the D/I/M correction) used M for the full default and
 * D for minimal. [migrateBootModeEntries] swaps the two once so an existing
 * user choice keeps the behavior it had before.
 */
private const val PREFS = "boot_modes"
private const val KEY_PREFIX = "mode_"
private const val SCHEME_VERSION_KEY = "scheme_version"

/** Version of the mode-letter scheme currently written by the app. */
internal const val BOOT_MODE_SCHEME_VERSION = 2

/** Default boot mode for a new install: full. */
const val DEFAULT_BOOT_MODE = "D"

/** The scheme the app wrote before D/I/M were corrected. */
private const val LEGACY_SCHEME_VERSION = 1

private fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/**
 * Compute the entries that a legacy -> current migration has to rewrite.
 *
 * Pure (no Android types) so it can be unit tested. Returns an empty map when
 * [schemeVersion] is already current or newer, meaning nothing has to change.
 */
internal fun migrateBootModeEntries(
    entries: Map<String, *>,
    schemeVersion: Int,
): Map<String, String> {
    if (schemeVersion >= BOOT_MODE_SCHEME_VERSION) return emptyMap()
    val migrated = LinkedHashMap<String, String>()
    for ((key, value) in entries) {
        if (!key.startsWith(KEY_PREFIX)) continue
        if (value !is String) continue
        migrated[key] =
            when (value) {
                "M" -> "D"
                "D" -> "M"
                else -> value
            }
    }
    return migrated
}

/**
 * Apply the one-time swap. Runs inside [loadBootMode], i.e. before the value
 * is handed to the launcher, so a stored legacy choice cannot be
 * misinterpreted in the meantime.
 */
private fun migrateScheme(p: SharedPreferences) {
    val stored = p.getInt(SCHEME_VERSION_KEY, LEGACY_SCHEME_VERSION)
    if (stored >= BOOT_MODE_SCHEME_VERSION) return
    val editor = p.edit()
    for ((key, value) in migrateBootModeEntries(p.all, stored)) {
        editor.putString(key, value)
    }
    editor.putInt(SCHEME_VERSION_KEY, BOOT_MODE_SCHEME_VERSION)
    editor.apply()
}

fun saveBootMode(context: Context, distroId: String, mode: String) {
    prefs(context).edit().putString("$KEY_PREFIX$distroId", mode).apply()
}

/**
 * Map a boot-mode letter to the `(NH_ISOLATED, NH_MINIMAL)` pair the launcher
 * reads. Single source of truth for the letter -> flag translation.
 *
 *   D (default, full) -> no isolation, no minimal stripping
 *   I (isolated)      -> isolation, fake /proc + /sys, no host paths
 *   M (minimal)       -> isolation + minimal config, only /dev /proc /sys
 *
 * Anything unknown falls back to the full default so a stale or malformed
 * stored value never silently downgrades the session.
 */
internal fun bootModeFlags(bootMode: String): Pair<String, String> =
    when (bootMode) {
        "I" -> "1" to "0"
        "M" -> "1" to "1"
        else -> "0" to "0"
    }

fun loadBootMode(context: Context, distroId: String, default: String = DEFAULT_BOOT_MODE): String {
    val p = prefs(context)
    migrateScheme(p)
    return p.getString("$KEY_PREFIX$distroId", default) ?: default
}
