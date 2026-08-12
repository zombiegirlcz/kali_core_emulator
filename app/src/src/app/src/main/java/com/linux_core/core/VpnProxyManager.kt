package com.linux_core.core

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object VpnProxyManager {
    private const val TAG = "VpnProxyManager"

    data class ProxyNode(
        val ip: String,
        val port: Int
    )

    private val isProxyEnabled = AtomicBoolean(false)
    private val customProxy = AtomicReference<ProxyNode?>(null)

    @Volatile
    var onProxyChangedListener: (() -> Unit)? = null

    fun isEnabled(): Boolean = isProxyEnabled.get()

    fun setEnabled(enabled: Boolean) {
        isProxyEnabled.set(enabled)
        Log.i(TAG, "Custom proxy set to: $enabled")
        onProxyChangedListener?.invoke()
    }

    /** Set custom proxy IP and port (e.g. "192.168.1.100:8080"). Returns false if format is invalid. */
    fun setCustomProxy(ipPort: String): Boolean {
        val parts = ipPort.trim().split(":")
        if (parts.size != 2) return false
        val ip = parts[0].trim()
        val port = parts[1].trim().toIntOrNull() ?: return false
        if (port !in 1..65535) return false
        customProxy.set(ProxyNode(ip, port))
        Log.i(TAG, "Custom proxy set to $ip:$port")
        onProxyChangedListener?.invoke()
        return true
    }

    fun getCustomProxy(): String? {
        val node = customProxy.get() ?: return null
        return "${node.ip}:${node.port}"
    }

    fun getActiveProxy(): ProxyNode? {
        if (!isEnabled()) return null
        return customProxy.get()
    }

    fun stop() {
        isProxyEnabled.set(false)
        customProxy.set(null)
        Log.i(TAG, "Custom proxy stopped")
    }
}

