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
import com.adguard.corelibs.tcpip.NativeTcpIpStackImpl
import com.adguard.corelibs.tcpip.NativeTcpIpStackListener
import com.adguard.corelibs.tcpip.TcpIpConnectionInfo
import com.adguard.corelibs.tcpip.ConnectionRequestResult
import com.adguard.corelibs.tcpip.ConnectionRequestResultType
import com.adguard.dnslibs.proxy.DnsProxy
import com.adguard.dnslibs.proxy.DnsProxySettings
import com.adguard.dnslibs.proxy.DnsProxyEvents
import com.adguard.dnslibs.proxy.DnsRequestProcessedEvent
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
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

        @Volatile
        private var instance: VpnCaptureService? = null

        var onStateChangeListener: ((Boolean) -> Unit)? = null

        fun isRunning(): Boolean = instance?.isServiceRunning?.get() ?: false
        
        fun getCapturedPacketCount(): Long = instance?.packetCount?.get() ?: 0L
        fun getCapturedByteCount(): Long = instance?.byteCount?.get() ?: 0L
    }

    private val isServiceRunning = AtomicBoolean(false)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tcpIpStack: NativeTcpIpStackImpl? = null
    private var dnsProxy: DnsProxy? = null
    
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
        Log.i(TAG, "Starting AdGuard Premium Native VPN")
        isServiceRunning.set(true)
        onStateChangeListener?.invoke(true)
        
        startForeground(NOTIFICATION_ID, buildNotification())
        
        try {
            // Establish the VpnService interface
            val builder = Builder()
                .setSession("NetHunter Premium AdGuard VPN")
                .setMtu(MTU)
                .addAddress("172.18.11.231", 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("198.18.53.53")
                .addRoute("198.18.53.53", 32)
            
            val sharedPrefs = getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
            val disallowedPackages = sharedPrefs.getStringSet("disallowed_packages", emptySet()) ?: emptySet()
            for (pkg in disallowedPackages) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not disallow app $pkg: ${e.message}")
                }
            }
            
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return
            }

            val cacheDir = File(cacheDir, "adguard")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            // 1. Initialize DNS Proxy
            val dnsSettings = DnsProxySettings().apply {
                isDetectSearchDomains = true
                fallbackDomains.add("8.8.8.8")
                fallbackDomains.add("1.1.1.1")
            }
            dnsProxy = DnsProxy(this, dnsSettings, object : DnsProxyEvents {
                override fun onRequestProcessed(event: DnsRequestProcessedEvent) {
                    Log.i(TAG, "DNS Query: ${event.domain} (rule=${event.rule})")
                    packetCount.incrementAndGet()
                }
            })

            // 2. Initialize Native TCP/IP Stack
            val executor = Executors.newCachedThreadPool()
            tcpIpStack = NativeTcpIpStackImpl(
                pfd = vpnInterface!!,
                mtu = MTU,
                cacheDir = cacheDir,
                proxyConfig = null,
                listener = object : NativeTcpIpStackListener {
                    override fun onConnectRequest(connectionInfo: TcpIpConnectionInfo): ConnectionRequestResult {
                        packetCount.incrementAndGet()
                        byteCount.addAndGet(100L) // Estimate byte throughput on request
                        return ConnectionRequestResult(ConnectionRequestResultType.ALLOW, null, false)
                    }
                },
                listenerExecutor = executor,
                vpnService = this
            )

            // 3. Start processing loop on background thread
            Thread({
                try {
                    tcpIpStack?.startProcessing()
                } catch (e: Exception) {
                    if (isServiceRunning.get()) {
                        Log.e(TAG, "Native TCP/IP Stack process loop error: ${e.message}", e)
                    }
                }
            }, "NativeTcpIpStackThread").apply { start() }
            
            // Periodic notification status updater
            handler.post(statsUpdater)
            
            Log.i(TAG, "Premium AdGuard Native VPN Stack successfully initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping Premium AdGuard Native VPN")
        isServiceRunning.set(false)
        onStateChangeListener?.invoke(false)
        handler.removeCallbacks(statsUpdater)
        
        try {
            tcpIpStack?.stop()
            tcpIpStack?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Native TCP/IP stack: ${e.message}")
        }
        tcpIpStack = null

        try {
            dnsProxy?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing DNS Proxy: ${e.message}")
        }
        dnsProxy = null
        
        // Close VPN interface
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
        
        val contentText = "Captured: $count packets ($formattedBytes)"
        
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
            .setContentTitle("NetHunter Premium VPN")
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
                description = "Shows premium VPN stats and status"
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
