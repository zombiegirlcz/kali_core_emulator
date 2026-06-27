package com.linux_core.core

import android.content.Context
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object VpnProxyManager {
    private const val TAG = "VpnProxyManager"

    data class ProxyNode(
        val country: String,
        val flag: String,
        val ip: String,
        val port: Int,
        var pingMs: Int = -1
    )

    // Pre-configured public test SOCKS5/HTTP proxies from top geographic regions
    val proxyPool = listOf(
        ProxyNode("United States", "🇺🇸", "45.72.63.1", 1080),
        ProxyNode("Germany", "🇩🇪", "82.165.143.2", 1080),
        ProxyNode("Japan", "🇯🇵", "153.121.43.3", 1080),
        ProxyNode("Singapore", "🇸🇬", "128.199.112.4", 1080),
        ProxyNode("Netherlands", "🇳🇱", "188.166.115.5", 1080),
        ProxyNode("United Kingdom", "🇬🇧", "178.62.80.6", 1080)
    )

    private val isProxyEnabled = AtomicBoolean(false)
    private val rotationMode = AtomicInteger(0) // 0: Static Country, 1: Random Session, 2: Time-based Loop
    private val selectedNodeIndex = AtomicInteger(0)
    private val rotationIntervalSeconds = AtomicInteger(30)

    @Volatile
    private var lastRotationTimeMs: Long = System.currentTimeMillis()

    private val executor = Executors.newFixedThreadPool(2)
    private var rotationThread: Thread? = null

    @Volatile
    var onProxyChangedListener: (() -> Unit)? = null


    init {
        // Start time-based rotation background watcher
        startRotationLoop()
    }

    fun isEnabled(): Boolean = isProxyEnabled.get()

    fun setEnabled(enabled: Boolean) {
        isProxyEnabled.set(enabled)
        if (enabled) {
            lastRotationTimeMs = System.currentTimeMillis()
        }
        Log.i(TAG, "Proxy sniffer redirection set to: $enabled")
        onProxyChangedListener?.invoke()
    }


    fun getRotationMode(): Int = rotationMode.get()

    fun setRotationMode(mode: Int) {
        rotationMode.set(mode)
        if (mode == 2) {
            lastRotationTimeMs = System.currentTimeMillis()
        }
        Log.i(TAG, "Proxy rotation mode changed to: $mode")
    }

    fun getSelectedNodeIndex(): Int = selectedNodeIndex.get()

    fun setSelectedNodeIndex(index: Int) {
        if (index in proxyPool.indices) {
            selectedNodeIndex.set(index)
            Log.i(TAG, "Selected static proxy node: ${proxyPool[index].country}")
            onProxyChangedListener?.invoke()
        }
    }


    fun getRotationInterval(): Int = rotationIntervalSeconds.get()

    fun setRotationInterval(seconds: Int) {
        rotationIntervalSeconds.set(seconds.coerceIn(5, 300))
    }

    fun getSecondsRemaining(): Int {
        if (!isEnabled() || rotationMode.get() != 2) return 0
        val elapsed = System.currentTimeMillis() - lastRotationTimeMs
        val total = rotationIntervalSeconds.get() * 1000L
        val remaining = (total - elapsed) / 1000
        return remaining.toInt().coerceAtLeast(0)
    }

    fun getActiveProxy(): ProxyNode? {
        if (!isEnabled()) return null
        val idx = selectedNodeIndex.get()
        return proxyPool.getOrNull(idx)
    }

    fun triggerRandomRotation() {
        if (proxyPool.isNotEmpty()) {
            val nextIdx = (0 until proxyPool.size).random()
            selectedNodeIndex.set(nextIdx)
            lastRotationTimeMs = System.currentTimeMillis()
            Log.d(TAG, "Random proxy rotated to: ${proxyPool[nextIdx].country}")
            onProxyChangedListener?.invoke()
        }
    }


    fun measureProxyLatencies(callback: () -> Unit) {
        executor.submit {
            proxyPool.forEach { node ->
                val start = System.currentTimeMillis()
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(node.ip, node.port), 1500)
                        node.pingMs = (System.currentTimeMillis() - start).toInt()
                    }
                } catch (e: Exception) {
                    node.pingMs = -1
                }
            }
            callback()
        }
    }

    fun stop() {
        isProxyEnabled.set(false)
        rotationThread?.interrupt()
        rotationThread = null
        Log.i(TAG, "Proxy rotation stopped")
    }

    private fun startRotationLoop() {
        rotationThread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(500)
                    if (isEnabled() && rotationMode.get() == 2) {
                        val intervalMs = rotationIntervalSeconds.get() * 1000L
                        val currentMs = System.currentTimeMillis()
                        if (currentMs - lastRotationTimeMs >= intervalMs) {
                            triggerRandomRotation()
                        }
                    } else {
                        // Keep updating when loop is not active so it doesn't rotate instantly when turned on
                        lastRotationTimeMs = System.currentTimeMillis()
                    }
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "Proxy rotation worker interrupted")
            }
        }.apply { start() }
    }
}

