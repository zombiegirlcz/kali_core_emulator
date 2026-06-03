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

        fun protectSocket(socket: java.net.DatagramSocket): Boolean {
            val inst = instance ?: return false
            inst.protect(socket)
            return true
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        VpnFirewallManager.init(applicationContext)
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START) {
            if (!isServiceRunning.get()) {
                startVpn()
            }
        }

        return START_STICKY
    }

    private fun startVpn() {
        Log.i(TAG, "Starting VPN with Java NAT engine")
        isServiceRunning.set(true)
        onStateChangeListener?.invoke(true)

        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            val sharedPrefs = getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
            val customMtu = sharedPrefs.getString("vpn_mtu", MTU.toString())?.toIntOrNull() ?: MTU
            val customDns = sharedPrefs.getString("vpn_dns", VPN_DNS) ?: VPN_DNS
            
            val builder = Builder()
                .setSession("NetHunter VPN")
                .setMtu(customMtu)
                .addAddress(VPN_ADDRESS, 32)
            
            if (VpnPeerManager.isEnabled()) {
                builder.addAddress("10.9.0.${VpnPeerManager.getLocalPeerId()}", 24)
            }

            builder.addRoute("0.0.0.0", 0)
                .addDnsServer(customDns)
                .allowBypass()

            // Exclude private subnets ONLY if they don't conflict with our VPN range
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    // We avoid excluding 10.0.0.0/8 because our VPN is 10.0.0.2
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("172.16.0.0"), 12))
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("192.168.0.0"), 16))
                    Log.i(TAG, "Excluded non-conflicting private subnets from VPN")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not exclude private subnets: ${e.message}")
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
                    vpnOutput?.write(data, 0, data.size)
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
                        vpnOutput?.write(decryptedBytes, 0, decryptedBytes.size)
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
                val buffer = ByteArray(MTU)
                try {
                    while (isServiceRunning.get()) {
                        val pfd = vpnInterface ?: break
                        val input = FileInputStream(pfd.fileDescriptor)
                        val length = input.read(buffer)
                        if (length > 0) {
                            natEngine?.handlePacketFromTun(ByteBuffer.wrap(buffer, 0, length), length)
                        }
                    }
                } catch (e: IOException) {
                    if (isServiceRunning.get()) {
                        Log.e(TAG, "VPN read loop error: ${e.message}")
                    }
                }
            }, "VpnNioThread").apply { start() }

            handler.post(statsUpdater)
            Log.i(TAG, "VPN started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN")
        isServiceRunning.set(false)
        onStateChangeListener?.invoke(false)
        handler.removeCallbacks(statsUpdater)

        // Disable P2P and release sockets
        VpnPeerManager.setEnabled(false)

        vpnThread?.interrupt()
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

    private fun buildNotification(): android.app.Notification {
        val count = packetCount.get()
        val bytes = byteCount.get()
        val formattedBytes = formatByteCount(bytes)

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
        isServiceRunning.set(false)
        stopVpn()
        instance = null
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }
}
