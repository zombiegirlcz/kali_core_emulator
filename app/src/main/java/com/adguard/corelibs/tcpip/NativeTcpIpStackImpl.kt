package com.adguard.corelibs.tcpip

import android.util.Log

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.adguard.corelibs.logger.NativeLogger
import com.adguard.corelibs.network.OutboundProxyConfig
import com.adguard.corelibs.network.Protocol
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

class NativeTcpIpStackImpl(
    pfd: ParcelFileDescriptor,
    mtu: Int,
    cacheDir: File?,
    proxyConfig: OutboundProxyConfig?,
    listener: NativeTcpIpStackListener,
    listenerExecutor: ExecutorService,
    vpnService: VpnService
) : NativeTcpIpStack {

    companion object {
        private val LOG = NativeLogger.getFacade(NativeTcpIpStackImpl::class.java)

        init {
            try {
                // System.loadLibrary("a")
                System.loadLibrary("io_utils")
                System.loadLibrary("common_native_jni")
                System.loadLibrary("adguard-core")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("NativeTcpIpStackImpl", "Failed to load libraries: ${e.message}", e)
                throw e
            }
        }


        @JvmStatic
        private external fun completeTcpConnectRequest(nativePtr: Long, reqId: Long, result: Int, redirectAddr: ByteArray, redirectPort: Int, forceDirectConnection: Boolean)

        @JvmStatic
        private external fun completeUdpConnectRequest(nativePtr: Long, reqId: Long, result: Int, redirectAddr: ByteArray, redirectPort: Int, forceDirectConnection: Boolean)
    }

    private var nativePtr: Long = 0
    private val raisedConnections = ConcurrentHashMap<Long, Int>()
    private val syncRoot = Any()

    @JvmField
    val callbacks: Callbacks

    init {
        callbacks = Callbacks(this, listener, listenerExecutor, vpnService)
        val path = cacheDir?.absolutePath
        val fd = pfd.fd
        nativePtr = init(fd, mtu, path, proxyConfig)
        if (nativePtr == 0L) {
            throw IOException("Failed to initialize native TCP/IP stack")
        }
        pfd.detachFd() // Native code takes ownership of the fd
    }

    // Finalizer removed to prevent pthread_mutex_lock on destroyed mutex

    private fun checkOpen() {
        synchronized(syncRoot) {
            if (nativePtr == 0L) {
                throw IllegalStateException("This TCP/IP stack instance is closed")
            }
        }
    }

    private fun handleInterruptedException() {
        stop()
    }

    private fun constructConnectionInfo(protoCode: Int, srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): TcpIpConnectionInfo {
        val srcAddr = InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort)
        val dstAddr = InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort)
        return TcpIpConnectionInfo(Protocol.getByCode(protoCode), srcAddr, dstAddr)
    }

    private fun completeTcpConnectRequest(reqId: Long, result: ConnectionRequestResult) {
        synchronized(syncRoot) {
            if (nativePtr != 0L) {
                val redirect = result.redirectAddress
                val addrBytes = redirect?.address?.address ?: ByteArray(0)
                val port = redirect?.port ?: 0
                completeTcpConnectRequest(nativePtr, reqId, result.resultType.code, addrBytes, port, result.isForceDirectConnection)
            }
        }
    }

    private fun completeUdpConnectRequest(reqId: Long, result: ConnectionRequestResult) {
        synchronized(syncRoot) {
            if (nativePtr != 0L) {
                val redirect = result.redirectAddress
                val addrBytes = redirect?.address?.address ?: ByteArray(0)
                val port = redirect?.port ?: 0
                completeUdpConnectRequest(nativePtr, reqId, result.resultType.code, addrBytes, port, result.isForceDirectConnection)
            }
        }
    }

    // JNI Native Methods
    private external fun init(fd: Int, mtu: Int, path: String?, proxyConfig: OutboundProxyConfig?): Long
    private external fun run(nativePtr: Long)
    private external fun stop(nativePtr: Long)
    private external fun close(nativePtr: Long)
    private external fun reset(nativePtr: Long)
    private external fun getTcpConnectionIdByAddrPort(nativePtr: Long, addr: ByteArray, port: Int): Long
    private external fun getUdpConnectionIdByPort(nativePtr: Long, port: Int): Long

    fun startProcessing() {
        checkOpen()
        run(nativePtr)
    }

    override fun stop() {
        synchronized(syncRoot) {
            if (nativePtr != 0L) {
                stop(nativePtr)
            }
        }
    }

    override fun reset() {
        synchronized(syncRoot) {
            if (nativePtr != 0L) {
                reset(nativePtr)
            }
        }
    }

    override fun close() {
        synchronized(syncRoot) {
            if (nativePtr != 0L) {
                close(nativePtr)
                nativePtr = 0L
            }
        }
    }

    override fun getTcpConnectionIdBySocketAddress(socketAddress: InetSocketAddress?): Long? {
        synchronized(syncRoot) {
            checkOpen()
            if (socketAddress == null) {
                LOG.error("null address")
                return null
            }
            return try {
                val addr = socketAddress.address.address
                val port = socketAddress.port
                getTcpConnectionIdByAddrPort(nativePtr, addr, port)
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun getUdpConnectionIdBySocketAddress(socketAddress: InetSocketAddress?): Long? {
        synchronized(syncRoot) {
            checkOpen()
            if (socketAddress == null) {
                LOG.error("null address")
                return null
            }
            return try {
                getUdpConnectionIdByPort(nativePtr, socketAddress.port)
            } catch (e: Exception) {
                null
            }
        }
    }

    // Callbacks helper class matching Native JNI calls
    inner class Callbacks(
        private val stack: NativeTcpIpStackImpl,
        private val listener: NativeTcpIpStackListener,
        private val listenerExecutor: ExecutorService,
        private val vpnService: VpnService
    ) {
        fun protect(socketFd: Int): Boolean {
            val result = vpnService.protect(socketFd)
            Log.d("NativeTcpIpCallbacks", "protect(fd=$socketFd) -> $result")
            if (!result) {
                Log.e("NativeTcpIpCallbacks", "CRITICAL: VpnService.protect() returned false for fd=$socketFd — outbound socket will loop back into VPN tunnel!")
            }
            return result
        }

        fun onTcpConnectRequest(reqId: Long, srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int) {
            try {
                val info = stack.constructConnectionInfo(Protocol.TCP.code, srcIp, srcPort, dstIp, dstPort)
                Log.i("NativeTcpIpCallbacks", "TCP connect #$reqId: ${info.source} -> ${info.destination}")
                raisedConnections[reqId] = 0
                listenerExecutor.execute {
                    try {
                        val result = listener.onTcpConnectRequest(reqId, info)
                        Log.d("NativeTcpIpCallbacks", "TCP #$reqId decision: ${result.resultType}, redirect=${result.redirectAddress}")
                        stack.completeTcpConnectRequest(reqId, result)
                    } catch (e: Throwable) {
                        Log.e("NativeTcpIpCallbacks", "TCP #$reqId listener error: ${e.message}", e)
                        stack.completeTcpConnectRequest(reqId, ConnectionRequestResult.REJECT)
                    }
                }
            } catch (e: Throwable) {
                Log.e("NativeTcpIpCallbacks", "TCP connect #$reqId fatal error: ${e.message}", e)
                stack.completeTcpConnectRequest(reqId, ConnectionRequestResult.REJECT)
            }
        }

        fun onTcpClosed(reqId: Long) {
            raisedConnections.remove(reqId) ?: return
            listenerExecutor.execute {
                try {
                    listener.onConnectionClosed(reqId)
                } catch (e: Throwable) {
                }
            }
        }

        fun onTcpStatistics(reqId: Long, txBytes: Long, rxBytes: Long) {
            listenerExecutor.execute {
                try {
                    listener.onConnectionStats(reqId, txBytes, rxBytes)
                } catch (e: Throwable) {
                }
            }
        }

        fun onUdpConnectRequest(reqId: Long, protoCode: Int, srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int) {
            try {
                val info = stack.constructConnectionInfo(protoCode, srcIp, srcPort, dstIp, dstPort)
                Log.i("NativeTcpIpCallbacks", "UDP connect #$reqId: ${info.source} -> ${info.destination} (proto=$protoCode)")
                raisedConnections[reqId] = 0
                listenerExecutor.execute {
                    try {
                        val result = listener.onUdpConnectRequest(reqId, info)
                        Log.d("NativeTcpIpCallbacks", "UDP #$reqId decision: ${result.resultType}, redirect=${result.redirectAddress}")
                        stack.completeUdpConnectRequest(reqId, result)
                    } catch (e: Throwable) {
                        Log.e("NativeTcpIpCallbacks", "UDP #$reqId listener error: ${e.message}", e)
                        stack.completeUdpConnectRequest(reqId, ConnectionRequestResult.REJECT)
                    }
                }
            } catch (e: Throwable) {
                Log.e("NativeTcpIpCallbacks", "UDP connect #$reqId fatal error: ${e.message}", e)
                stack.completeUdpConnectRequest(reqId, ConnectionRequestResult.REJECT)
            }
        }

        fun onUdpClosed(reqId: Long) {
            raisedConnections.remove(reqId) ?: return
            listenerExecutor.execute {
                try {
                    listener.onUdpConnectionClosed(reqId)
                } catch (e: Throwable) {
                }
            }
        }

        fun onUdpStatistics(reqId: Long, txBytes: Long, rxBytes: Long) {
            listenerExecutor.execute {
                try {
                    listener.onUdpConnectionStats(reqId, txBytes, rxBytes)
                } catch (e: Throwable) {
                }
            }
        }
    }

}

