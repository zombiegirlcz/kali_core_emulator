package com.linux_core.security

import android.content.Context

object VpnSettings {

    private const val DEFAULT_SNI_FALLBACK = "www.google.com"

    fun getMitmSniFallback(context: Context): String? {
        val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        if (!prefs.contains("mitm_sni_fallback")) {
            return DEFAULT_SNI_FALLBACK
        }
        return prefs.getString("mitm_sni_fallback", null)?.takeIf { it.isNotBlank() }
    }

    fun setMitmSniFallback(context: Context, value: String?) {
        val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        if (value.isNullOrBlank()) {
            prefs.edit().remove("mitm_sni_fallback").apply()
        } else {
            prefs.edit().putString("mitm_sni_fallback", value.trim()).apply()
        }
    }
}
