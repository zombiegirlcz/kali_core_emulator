package com.linux_core.core

import android.content.Context
import android.content.SharedPreferences
import java.util.regex.Pattern

object VpnFirewallManager {
    private const val PREFS_NAME = "vpn_settings"
    private const val KEY_BLOCKED_IPS = "blocked_ips"

    // IPv4 and IPv6 regex patterns for validation
    private val IPV4_PATTERN = Pattern.compile(
        "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
    )
    private val IPV6_PATTERN = Pattern.compile(
        "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$"
    )

    private var sharedPreferences: SharedPreferences? = null
    private val blockedIps = HashSet<String>()

    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = sharedPreferences?.getStringSet(KEY_BLOCKED_IPS, emptySet()) ?: emptySet()
        synchronized(blockedIps) {
            blockedIps.clear()
            blockedIps.addAll(saved)
        }
    }

    private fun isValidIp(ip: String): Boolean {
        val baseIp = if (ip.contains('/')) ip.substringBefore('/') else ip
        return IPV4_PATTERN.matcher(baseIp).matches() || IPV6_PATTERN.matcher(baseIp).matches()
    }

    fun isIpBlocked(ip: String): Boolean {
        synchronized(blockedIps) {
            return blockedIps.contains(ip)
        }
    }

    fun blockIp(ip: String) {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) return
        if (!isValidIp(trimmed)) {
            android.util.Log.w("VpnFirewallManager", "Rejected invalid IP format: $trimmed")
            return
        }
        synchronized(blockedIps) {
            if (blockedIps.add(trimmed)) {
                save()
            }
        }
    }

    fun unblockIp(ip: String) {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) return
        synchronized(blockedIps) {
            if (blockedIps.remove(trimmed)) {
                save()
            }
        }
    }

    fun getBlockedIps(): Set<String> {
        synchronized(blockedIps) {
            return blockedIps.toSet()
        }
    }

    private fun save() {
        sharedPreferences?.edit()?.putStringSet(KEY_BLOCKED_IPS, blockedIps)?.apply()
    }
}
