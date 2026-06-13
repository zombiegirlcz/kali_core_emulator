package com.linux_core.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.linux_core.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VpnCaptureService : VpnService() {

    companion object {
        private const val TAG = "VpnCaptureService"
        const val CHANNEL_ID = "vpn_capture_sessions"
        const val NOTIFICATION_ID = 2

        const val ACTION_START = "com.linux_core.ACTION_START"
        const val ACTION_STOP = "com.linux_core.ACTION_STOP"

        private const val MTU = 1500
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_DNS = "8.8.8.8"

        @Volatile
        private var instance: VpnCaptureService? = null

        var onStateChangeListener: ((Boolean) -> Unit)? = null

        fun isRunning(): Boolean = instance?.isServiceRunning?.get() ?: false

        fun getCapturedPacketCount(): Long = instance?.packetCount?.get() ?: 0L
        fun getCapturedByteCount(): Long = instance?.byteCount?.get() ?: 0L

        @JvmStatic
        fun protectSocket(socketFd: Int): Boolean {
            return try {
                instance?.protect(socketFd) ?: false
            } catch (e: Exception) {
                Log.e(TAG, "protectSocket(Int) failed: ${e.message}")
                false
            }
        }

        @JvmStatic
        fun protectSocket(socket: java.net.Socket): Boolean {
            return try {
                val inst = instance ?: return false
                inst.protect(socket)
                true
            } catch (e: Exception) {
                Log.e(TAG, "protectSocket(Socket) failed: ${e.message}")
                false
            }
        }

        @JvmStatic
        fun protectSocket(socket: java.net.DatagramSocket): Boolean {
            return try {
                val inst = instance ?: return false
                inst.protect(socket)
                true
            } catch (e: Exception) {
                Log.e(TAG, "protectSocket(DatagramSocket) failed: ${e.message}")
                false
            }
        }

        @JvmStatic
        fun getConnectionOwnerUid(protocolStr: String, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
            val inst = instance ?: return -1
            return try {
                val protocol = if (protocolStr.equals("TCP", ignoreCase = true)) 6 else 17
                val srcAddr = java.net.InetAddress.getByName(srcIp)
                val dstAddr = java.net.InetAddress.getByName(dstIp)
                val method = VpnService::class.java.getMethod(
                    "checkConnectionOwner",
                    Int::class.javaPrimitiveType,
                    java.net.InetAddress::class.java,
                    Int::class.javaPrimitiveType,
                    java.net.InetAddress::class.java,
                    Int::class.javaPrimitiveType
                )
                method.invoke(inst, protocol, srcAddr, srcPort, dstAddr, dstPort) as Int
            } catch (e: Exception) {
                Log.e(TAG, "getConnectionOwnerUid reflection failed: ${e.message}")
                -1
            }
        }

        @JvmStatic
        fun getActiveSockets(context: Context): List<ActiveSocket> {
            return instance?.natEngine?.getActiveSockets(context) ?: emptyList()
        }
    }

    private val isServiceRunning = AtomicBoolean(false)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnOutput: FileOutputStream? = null
    private var natEngine: VpnNatEngine? = null
    private var vpnThread: Thread? = null

    private val packetCount = AtomicLong(0L)
    private val byteCount = AtomicLong(0L)

    private val handler = Handler(Looper.getMainLooper())
    private val statsUpdater = object : Runnable {
        override fun run() {
            if (isServiceRunning.get()) {
                updateNotification()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val vpnSync = Any()
    private val writeLock = Any()

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "NetHunter:VpnCaptureServiceWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "WakeLock acquired for VpnCaptureService")
            }
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NetHunter:VpnCaptureServiceWifiLock")
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL, "NetHunter:VpnCaptureServiceWifiLock")
                }.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "WifiLock acquired for VpnCaptureService")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
            Log.i(TAG, "WakeLock released for VpnCaptureService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock: ${e.message}")
        }
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wifiLock = null
            Log.i(TAG, "WifiLock released for VpnCaptureService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WifiLock: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        acquireLocks()
        createNotificationChannel()
        VpnLogManager.loadCustomBlocklist(this)
        Log.i(TAG, "Service created")

        VpnProxyManager.onProxyChangedListener = {
            if (isServiceRunning.get()) {
                Log.i(TAG, "Proxy changed, restarting VPN engine...")
                handler.post {
                    synchronized(vpnSync) {
                        stopVpn()
                        startVpn()
                    }
                }
            }
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            synchronized(vpnSync) {
                stopVpn()
            }
            return START_NOT_STICKY
        }

        // Default to starting the VPN for any other actions (including ACTION_START, null, or system bindings)
        if (!isServiceRunning.get()) {
            synchronized(vpnSync) {
                startVpn()
            }
        }

        return START_STICKY
    }

    private fun startVpn() {
        Log.i(TAG, "Starting VPN with Java Userspace NAT Engine")
        if (isServiceRunning.getAndSet(true)) {
            Log.w(TAG, "VPN already running, skipping start")
            return
        }
        onStateChangeListener?.invoke(true)

        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            val sharedPrefs = getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
            val customMtu = sharedPrefs.getString("vpn_mtu", MTU.toString())?.toIntOrNull() ?: MTU
            val customDns = sharedPrefs.getString("vpn_dns", VPN_DNS) ?: VPN_DNS
            val dnsServers = mutableListOf<String>()
            if (customDns != VPN_DNS && customDns.isNotBlank()) {
                dnsServers.add(customDns)
            } else {
                val underlying = getUnderlyingDnsServers()
                if (underlying.isNotEmpty()) {
                    dnsServers.addAll(underlying)
                } else {
                    dnsServers.add(VPN_DNS)
                }
            }
            Log.i(TAG, "Configured VPN DNS servers: $dnsServers")
            
            val builder = Builder()
                .setSession("NetHunter VPN")
                .setMtu(customMtu)
                .addAddress(VPN_ADDRESS, 32)
                .addAddress("2001:db8:1::2", 128)
                .addRoute("::", 0)
            
            if (VpnPeerManager.isEnabled()) {
                builder.addAddress("10.9.0.${VpnPeerManager.getLocalPeerId()}", 24)
            }

            val isAdbActive = isWirelessAdbActive()
            if (isAdbActive) {
                Log.i(TAG, "Wireless ADB connection detected. Excluding LAN from VPN routes.")
            } else {
                Log.i(TAG, "Wireless ADB not active. VPN will capture all LAN traffic.")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.addRoute("0.0.0.0", 0)
                dnsServers.forEach { dns ->
                    try {
                        builder.addDnsServer(dns)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add DNS: $dns: ${e.message}")
                    }
                }
                builder.allowBypass()
                if (isAdbActive) {
                    try {
                        builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("172.16.0.0"), 12))
                        builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("192.168.0.0"), 16))
                        Log.i(TAG, "Excluded non-conflicting private subnets from VPN")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not exclude routes: ${e.message}")
                    }
                }
            } else {
                dnsServers.forEach { dns ->
                    try {
                        builder.addDnsServer(dns)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add DNS: $dns: ${e.message}")
                    }
                }
                builder.allowBypass()
                
                if (isAdbActive) {
                    // For API < 33, add routes that cover the IPv4 space except private ranges (192.168.0.0/16 and 172.16.0.0/12)
                    val bypassRanges = listOf(
                        "0.0.0.0" to 1,        // 0.0.0.0 - 127.255.255.255
                        "128.0.0.0" to 3,      // 128.0.0.0 - 159.255.255.255
                        "160.0.0.0" to 5,      // 160.0.0.0 - 167.255.255.255
                        "168.0.0.0" to 6,      // 168.0.0.0 - 171.255.255.255
                        "172.0.0.0" to 12,     // 172.0.0.0 - 172.15.255.255
                        "172.32.0.0" to 11,    // 172.32.0.0 - 172.63.255.255
                        "172.64.0.0" to 10,    // 172.64.0.0 - 172.127.255.255
                        "172.128.0.0" to 9,    // 172.128.0.0 - 172.255.255.255
                        "173.0.0.0" to 8,      // 173.0.0.0 - 173.255.255.255
                        "174.0.0.0" to 7,      // 174.0.0.0 - 175.255.255.255
                        "176.0.0.0" to 4,      // 176.0.0.0 - 191.255.255.255
                        "192.0.0.0" to 9,      // 192.0.0.0 - 192.127.255.255
                        "192.128.0.0" to 11,   // 192.128.0.0 - 192.159.255.255
                        "192.160.0.0" to 13,   // 192.160.0.0 - 192.167.255.255
                        "192.169.0.0" to 16,   // 192.169.0.0 - 192.169.255.255
                        "192.170.0.0" to 15,   // 192.170.0.0 - 192.171.255.255
                        "192.172.0.0" to 14,   // 192.172.0.0 - 192.175.255.255
                        "192.176.0.0" to 12,   // 192.176.0.0 - 192.191.255.255
                        "192.192.0.0" to 10,   // 192.192.0.0 - 192.255.255.255
                        "193.0.0.0" to 8,      // 193.0.0.0 - 193.255.255.255
                        "194.0.0.0" to 7,      // 194.0.0.0 - 195.255.255.255
                        "196.0.0.0" to 6,      // 196.0.0.0 - 199.255.255.255
                        "200.0.0.0" to 5,      // 200.0.0.0 - 207.255.255.255
                        "208.0.0.0" to 4       // 208.0.0.0 - 223.255.255.255
                    )
                    for ((ip, prefix) in bypassRanges) {
                        try {
                            builder.addRoute(ip, prefix)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to add route: $ip/$prefix", e)
                        }
                    }
                } else {
                    // Capture everything including LAN
                    builder.addRoute("0.0.0.0", 0)
                }
            }

            // Prevent VPN from routing ADB, loopback API traffic and user disallowed packages
            try {
                builder.addDisallowedApplication(packageName)
                builder.addDisallowedApplication("com.android.shell")

                val disallowedPackages = sharedPrefs.getStringSet("disallowed_packages", emptySet()) ?: emptySet()
                disallowedPackages.forEach { pkg ->
                    try {
                        builder.addDisallowedApplication(pkg)
                        Log.d(TAG, "App bypassed from VPN: $pkg")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not disallow app: $pkg")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not disallow core apps: ${e.message}")
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopVpn()
                return
            }
            vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)

            // Initialize Java NAT engine
            val writeToTun: (ByteArray, Int) -> Unit = { data, _ ->
                try {
                    synchronized(writeLock) {
                        vpnOutput?.write(data, 0, data.size)
                    }
                    packetCount.incrementAndGet()
                    byteCount.addAndGet(data.size.toLong())
                } catch (e: IOException) {
                    if (isServiceRunning.get()) {
                        Log.e(TAG, "Error writing to TUN: ${e.message}")
                    }
                }
            }

            if (VpnPeerManager.isEnabled()) {
                VpnPeerManager.initCallbacks { decryptedBytes ->
                    try {
                        synchronized(writeLock) {
                            vpnOutput?.write(decryptedBytes, 0, decryptedBytes.size)
                        }
                        packetCount.incrementAndGet()
                        byteCount.addAndGet(decryptedBytes.size.toLong())
                    } catch (e: IOException) {
                        Log.e(TAG, "Error writing decrypted P2P packet to TUN: ${e.message}")
                    }
                }
                // Re-trigger setEnabled to start UDP listeners
                VpnPeerManager.setEnabled(true)
            }

            natEngine = VpnNatEngine(this, writeToTun)

            // Start packet forwarding loop on background thread
            vpnThread = Thread({
                val buffer = ByteArray(customMtu)
                try {
                    val pfd = vpnInterface
                    if (pfd != null) {
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            while (isServiceRunning.get()) {
                                val length = input.read(buffer)
                                if (length > 0) {
                                    packetCount.incrementAndGet()
                                    byteCount.addAndGet(length.toLong())
                                    natEngine?.handlePacketFromTun(ByteBuffer.wrap(buffer, 0, length), length)
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (isServiceRunning.get()) {
                        Log.e(TAG, "VPN read loop error: ${e.message}")
                    }
                }
            }, "VpnNioThread").apply { start() }

            handler.post(statsUpdater)
            scheduleHealthCheck()
            Log.i(TAG, "VPN started successfully with Java NAT Engine")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN")
        if (!isServiceRunning.getAndSet(false)) {
            Log.w(TAG, "VPN already stopped, skipping stop")
            return
        }
        onStateChangeListener?.invoke(false)
        handler.removeCallbacks(statsUpdater)

        // Disable P2P and release sockets
        VpnPeerManager.setEnabled(false)

        vpnThread?.interrupt()
        try {
            vpnThread?.join(1500)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for VPN thread to stop: ${e.message}")
        }
        vpnThread = null

        natEngine?.stop()
        natEngine = null

        try {
            vpnOutput?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing output stream: ${e.message}")
        }
        vpnOutput = null

        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN descriptor: ${e.message}")
        }
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleHealthCheck() {
        val checkCount = intArrayOf(0)
        val healthChecker = object : Runnable {
            override fun run() {
                if (!isServiceRunning.get()) return
                checkCount[0]++
                val threadAlive = vpnThread?.isAlive == true
                val engineActive = natEngine != null
                val pkts = packetCount.get()
                val bytes = byteCount.get()
                Log.i(TAG, "HEALTH CHECK #${checkCount[0]}: thread_alive=$threadAlive, engine=$engineActive, packets=$pkts, bytes=$bytes")
                if (!threadAlive) {
                    Log.e(TAG, "HEALTH CHECK: VPN read thread has DIED! Traffic will not flow.")
                }
                if (checkCount[0] <= 6) {
                    handler.postDelayed(this, 5000)
                }
            }
        }
        handler.postDelayed(healthChecker, 5000)
    }

    private fun isWirelessAdbActive(): Boolean {
        try {
            if (android.provider.Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1) {
                return true
            }
            val process = Runtime.getRuntime().exec("getprop service.adb.tcp.port")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val port = reader.readLine()?.toIntOrNull() ?: -1
            process.destroy()
            if (port > 0) return true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ADB status: ${e.message}")
        }
        return false
    }

    private fun buildNotification(): android.app.Notification {
        val count = packetCount.get()
        val formattedBytes = formatByteCount(byteCount.get())
        val contentText = "Forwarded: $count packets ($formattedBytes)"

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )

        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, VpnCaptureService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NetHunter VPN")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop VPN",
                stopIntent
            )
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun formatByteCount(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %cBs", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Packet Captures",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows VPN stats and status"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun pendingIntentImmutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    override fun onDestroy() {
        releaseLocks()
        isServiceRunning.set(false)
        stopVpn()
        instance = null
        VpnProxyManager.onProxyChangedListener = null
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    private fun getUnderlyingDnsServers(): List<String> {
        val dnsList = mutableListOf<String>()
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (connectivityManager != null) {
                for (network in connectivityManager.allNetworks) {
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    if (capabilities != null && (
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                    )) {
                        val lp = connectivityManager.getLinkProperties(network)
                        if (lp != null) {
                            for (dns in lp.dnsServers) {
                                val ip = dns.hostAddress
                                if (!ip.isNullOrEmpty() && !ip.contains(":")) {
                                    dnsList.add(ip)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting underlying DNS servers: ${e.message}")
        }
        return dnsList
    }

}
