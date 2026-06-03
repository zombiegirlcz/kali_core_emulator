package com.linux_core.core

import android.content.Context
import android.content.SharedPreferences

object VpnFirewallManager {
    private const val PREFS_NAME = "vpn_settings"
    private const val KEY_BLOCKED_IPS = "blocked_ips"
    
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

    fun isIpBlocked(ip: String): Boolean {
        synchronized(blockedIps) {
            return blockedIps.contains(ip)
        }
    }

    fun blockIp(ip: String) {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) return
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
