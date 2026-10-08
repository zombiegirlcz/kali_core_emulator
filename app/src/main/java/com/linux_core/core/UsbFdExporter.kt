package com.linux_core.core

import android.os.ParcelFileDescriptor
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

    /** Max pokusů o odeslání jednoho fd — pak se export zahodí (dřív se re-queue točil donekonečna). */
    private const val MAX_ATTEMPTS = 5

    // Pending exports: (deviceName, fd) waiting for a PRoot client.
    // fd je VLASTNÍ dup() exportéru — nezávislý na UsbDeviceConnection, takže zavření
    // spojení (releaseInterface/detach) ho nezneplatní ani nepodstrčí recyklované číslo fd.
    private data class PendingExport(val deviceName: String, val fd: Int, val attempts: Int = 0)

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

        // If already running but with a different path, restart with new path
        if (running.get()) {
            if (udsPath != path) {
                Log.i(TAG, "Restarting UDS from $udsPath → $path")
                stopInternal()
            } else {
                Log.w(TAG, "Already running at $path — no change needed")
                return
            }
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
        // Vlastní kopie fd (dup) — exportér ji zavře po odeslání / zahození / zrušení.
        val ownFd = try {
            ParcelFileDescriptor.fromFd(fd).detachFd()
        } catch (e: Exception) {
            Log.e(TAG, "dup() failed for $deviceName fd=$fd: ${e.message}")
            return
        }
        pendingQueue.offer(PendingExport(deviceName, ownFd))
        Log.i(TAG, "Queued fd=$ownFd (dup of $fd, $deviceName) for export. Queue size: ${pendingQueue.size}")
    }

    /**
     * Zruší čekající exporty daného zařízení (volá UsbHostManager při zavření spojení
     * nebo odpojení zařízení) a zavře jejich dup fd.
     */
    fun cancelExports(deviceName: String) {
        val it = pendingQueue.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.deviceName == deviceName) {
                it.remove()
                closeOwnFd(p)
                Log.i(TAG, "Cancelled pending export for $deviceName")
            }
        }
    }

    private fun closeOwnFd(p: PendingExport) {
        try { ParcelFileDescriptor.adoptFd(p.fd).close() } catch (_: Exception) { /* best-effort */ }
    }

    /** Re-queue s počítadlem pokusů; po [MAX_ATTEMPTS] export zahodí a zavře fd. */
    private fun requeueOrDrop(p: PendingExport) {
        val next = p.copy(attempts = p.attempts + 1)
        if (next.attempts >= MAX_ATTEMPTS) {
            Log.e(TAG, "Dropping export for ${p.deviceName} after ${next.attempts} attempts")
            closeOwnFd(p)
        } else {
            pendingQueue.offer(next)
        }
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
                requeueOrDrop(pending)
                break
            }

            try {
                Log.i(TAG, "Waiting for PRoot client to connect for ${pending.deviceName} (fd=${pending.fd})...")
                val clientFd = nativeAcceptAndSendFd(serverFd, pending.fd)
                if (clientFd < 0) {
                    Log.e(TAG, "Failed to send fd for ${pending.deviceName}: errno=${-clientFd}")
                    // Re-queue the fd for retry (omezený počet pokusů)
                    requeueOrDrop(pending)
                    Thread.sleep(500)
                    continue
                }

                Log.i(TAG, "USB fd=${pending.fd} sent to client fd=$clientFd for ${pending.deviceName}")

                // SCM_RIGHTS předal klientovi vlastní kopii fd → naši dup zavřít.
                closeOwnFd(pending)
                // We keep the client connection open briefly in case the client
                // wants to send back status, but the bridge binary exits after receiving.
                // Close the client fd after a brief wait.
                Thread.sleep(100)
                nativeCloseSocket(clientFd)

            } catch (e: InterruptedException) {
                // InterruptedException přijde jen ze sleep — pending je už re-queued nebo zavřený
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in accept loop: ${e.message}")
                requeueOrDrop(pending) // retry (omezený počet pokusů)
            }
        }
        Log.i(TAG, "Accept loop exited")
    }

    // ── Close / shutdown ────────────────────────────────────────────────────

    private fun stopInternal() {
        thread?.interrupt()
        thread = null
        if (serverFd >= 0) {
            nativeCloseSocket(serverFd)
            serverFd = -1
        }
    }

    @Synchronized
    override fun close() {
        running.set(false)
        thread?.interrupt()
        thread = null

        if (serverFd >= 0) {
            nativeCloseSocket(serverFd)
            serverFd = -1
        }
        while (true) {
            val p = pendingQueue.poll() ?: break
            closeOwnFd(p)
        }
        Log.i(TAG, "Shutdown complete")
    }

    // ── JNI native methods ──────────────────────────────────────────────────

    private external fun nativeCreateServerSocket(path: String): Int

    private external fun nativeAcceptAndSendFd(serverFd: Int, usbFd: Int): Int

    private external fun nativeCloseSocket(fd: Int): Unit

    private external fun nativeReadClient(clientFd: Int, buf: ByteArray, offset: Int, len: Int): Int

    private external fun nativeWriteClient(clientFd: Int, buf: ByteArray, offset: Int, len: Int): Int
}
