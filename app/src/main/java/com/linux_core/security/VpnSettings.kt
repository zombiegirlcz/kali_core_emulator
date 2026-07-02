package com.linux_core.security

import android.content.Context

object VpnSettings {

    fun getMitmSniFallback(context: Context): String? {
        val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
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
