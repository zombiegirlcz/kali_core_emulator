package com.linux_core.core

import android.util.Log
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exports USB device file descriptors to PRoot processes via Unix Domain Socket
 * with SCM_RIGHTS fd passing.
 *
 * Architecture:
 *   App process                          PRoot guest process
 *   ┌────────────────────┐               ┌─────────────────────────┐
 *   │ UsbFdExporter       │  UDS connect  │ usb_bridge binary        │
 *   │  ↓ nativeCreate     │◄──────────────│  ↓ recvmsg(SCM_RIGHTS)   │
 *   │  ↓ nativeAcceptSend │─── fd ───────►│  ↓ ioctl(fd, USBDEVFS_*) │
 *   └────────────────────┘               └─────────────────────────┘
 *
 * Usage:
 *   UsbFdExporter.init()                  // once, loads JNI
 *   UsbFdExporter.start(udsPath)          // start UDS listener thread
 *   UsbFdExporter.exportFd(deviceName, fd) // enqueue fd for next PRoot client
 *   UsbFdExporter.stop()                  // shutdown
 */
object UsbFdExporter : Closeable {
    private const val TAG = "UsbFdExporter"

    // Pending exports: pairs of (deviceName, fd) waiting for a PRoot client
    private data class PendingExport(val deviceName: String, val fd: Int)

    private val pendingQueue = ConcurrentLinkedQueue<PendingExport>()
    private val running = AtomicBoolean(false)
    private var serverFd: Int = -1
    private var thread: Thread? = null
    private var udsPath: String = "/data/data/com.linux_core/usb_bridge.sock"

    private var initialized = false

    init {
        try {
            System.loadLibrary("usbfd_exporter")
            initialized = true
            Log.i(TAG, "JNI library loaded (init block)")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libusbfd_exporter.so NOT found in APK: ${e.message}")
            // Don't throw in init block — defer error to first start() call
        }
    }

    /**
     * Load the JNI library. Call once before start().
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    @Synchronized
    fun ensureLoaded() {
        if (initialized) return
        try {
            System.loadLibrary("usbfd_exporter")
            initialized = true
            Log.i(TAG, "JNI library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libusbfd_exporter.so: ${e.message}")
            throw RuntimeException("USB bridge JNI library not found. Ensure libusbfd_exporter.so is in jniLibs.", e)
        }
    }

    /**
     * Start the UDS server thread. Non-blocking.
     * @param path Unix Domain Socket path (default: /data/data/com.linux_core/usb_bridge.sock)
     */
    @Synchronized
    fun start(path: String = udsPath) {
        ensureLoaded()
        if (!initialized) {
            throw RuntimeException("libusbfd_exporter.so not available — USB bridge disabled")
        }
        if (running.get()) {
            Log.w(TAG, "Already running")
            return
        }
        udsPath = path
        running.set(true)

        serverFd = nativeCreateServerSocket(udsPath)
        if (serverFd < 0) {
            running.set(false)
            throw java.io.IOException("Failed to create UDS server at $udsPath: errno=${-serverFd}")
        }

        thread = Thread({
            acceptLoop()
        }, "UsbFdExporter").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "UDS server started at $udsPath (fd=$serverFd)")
    }

    /**
     * Enqueue a USB file descriptor to be passed to the next PRoot client that connects.
     * Non-blocking. The fd will be sent via SCM_RIGHTS when a client connects.
     *
     * @param deviceName USB device path (e.g. /dev/bus/usb/001/002) — for logging
     * @param fd         Raw file descriptor from UsbDeviceConnection (via reflection)
     */
    fun exportFd(deviceName: String, fd: Int) {
        if (!running.get()) {
            Log.w(TAG, "Not running, cannot export fd for $deviceName")
            return
        }
        if (fd < 0) {
            Log.e(TAG, "Invalid fd for $deviceName: $fd")
            return
        }
        pendingQueue.offer(PendingExport(deviceName, fd))
        Log.i(TAG, "Queued fd=$fd ($deviceName) for export. Queue size: ${pendingQueue.size}")
    }

    /**
     * Active fd exports currently waiting. Read-only snapshot.
     */
    fun pendingCount(): Int = pendingQueue.size

    fun isRunning(): Boolean = running.get()

    fun getUdsPath(): String = udsPath

    // ── Private accept loop (runs in background thread) ─────────────────────

    private fun acceptLoop() {
        Log.i(TAG, "Accept loop started")
        while (running.get()) {
            val pending = pendingQueue.poll()
            if (pending == null) {
                // No pending exports — brief sleep
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                continue
            }

            if (serverFd < 0) {
                Log.e(TAG, "Server fd invalid, re-queuing ${pending.deviceName}")
                pendingQueue.offer(pending)
                break
            }

            try {
                Log.i(TAG, "Waiting for PRoot client to connect for ${pending.deviceName} (fd=${pending.fd})...")
                val clientFd = nativeAcceptAndSendFd(serverFd, pending.fd)
                if (clientFd < 0) {
                    Log.e(TAG, "Failed to send fd for ${pending.deviceName}: errno=${-clientFd}")
                    // Re-queue the fd for retry
                    pendingQueue.offer(pending)
                    Thread.sleep(500)
                    continue
                }

                Log.i(TAG, "USB fd=${pending.fd} sent to client fd=$clientFd for ${pending.deviceName}")

                // The PRoot client now owns the USB fd and will close it.
                // We keep the client connection open briefly in case the client
                // wants to send back status, but the bridge binary exits after receiving.
                // Close the client fd after a brief wait.
                Thread.sleep(100)
                nativeCloseSocket(clientFd)

            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in accept loop: ${e.message}")
                pendingQueue.offer(pending) // retry
            }
        }
        Log.i(TAG, "Accept loop exited")
    }

    // ── Close / shutdown ────────────────────────────────────────────────────

    @Synchronized
    override fun close() {
        running.set(false)
        thread?.interrupt()
        thread = null

        if (serverFd >= 0) {
            nativeCloseSocket(serverFd)
            serverFd = -1
        }
        pendingQueue.clear()
        Log.i(TAG, "Shutdown complete")
    }

    // ── JNI native methods ──────────────────────────────────────────────────

    @JvmStatic
    private external fun nativeCreateServerSocket(path: String): Int

    @JvmStatic
    private external fun nativeAcceptAndSendFd(serverFd: Int, usbFd: Int): Int

    @JvmStatic
    private external fun nativeCloseSocket(fd: Int): Unit

    @JvmStatic
    private external fun nativeReadClient(clientFd: Int, buf: ByteArray, offset: Int, len: Int): Int

    @JvmStatic
    private external fun nativeWriteClient(clientFd: Int, buf: ByteArray, offset: Int, len: Int): Int
}
