package com.linux_core.core

import android.net.VpnService
import android.util.Log
import com.linux_core.security.RootCaInstaller
import com.linux_core.security.TlsClientHelloParser
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.security.cert.X509Certificate
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLContext

object TlsMitmEngine {

    private const val TAG = "TlsMitmEngine"
    private val sessions = ConcurrentHashMap<Int, TlsMitmSession>()
    private var executor: ExecutorService? = null
    private var selector: Selector? = null

    fun init(executor: ExecutorService, selector: Selector) {
        this.executor = executor
        this.selector = selector
    }

    fun onClientData(
        vpnService: VpnService,
        session: VpnNatEngine.TcpSession,
        payload: ByteArray,
        writeToTun: (ByteArray, Int) -> Unit
    ) {
        val handler = sessions[session.clientPort]
        if (handler != null) {
            handler.handleClientData(payload)
            return
        }
        if (!TlsClientHelloParser.isTlsClientHello(payload)) return
        selector?.let { sel ->
            session.socketChannel?.keyFor(sel)?.cancel()
        }
        session.socketChannel?.close()
        session.socketChannel = null
        val mitm = TlsMitmSession(vpnService, session, payload, writeToTun)
        sessions[session.clientPort] = mitm
        session.isTlsMitm = true
        session.tlsMitmHandler = mitm
        executor?.submit { mitm.start() }
        Log.i(TAG, "TLS MITM started for port ${session.clientPort}")
    }

    fun removeSession(clientPort: Int) {
        sessions.remove(clientPort)
    }
}

class TlsMitmSession(
    private val vpnService: VpnService,
    private val session: VpnNatEngine.TcpSession,
    private val initialClientHello: ByteArray,
    private val writeToTun: (ByteArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "TlsMitmSession"
    }

    private val clientPort = session.clientPort
    private var serverChannel: SocketChannel? = null
    private var clientEngine: SSLEngine? = null
    private var serverEngine: SSLEngine? = null
    private var serverCert: X509Certificate? = null
    var sni: String? = null
    private var running = true

    private val clientQueue = ArrayDeque<ByteArray>()
    private val clientQueueLock = Any()

    @Volatile private var clientSeqNum: Long = session.clientSeqNum
    @Volatile private var serverSeqNum: Long = session.serverSeqNum

    init {
        session.clientSeqNum = clientSeqNum
        session.serverSeqNum = serverSeqNum
    }

    fun handleClientData(data: ByteArray) {
        synchronized(clientQueueLock) {
            if (clientQueue.size >= 64) {
                clientQueue.removeFirst()
            }
            clientQueue.add(data)
        }
    }

    fun start() {
        try {
            sni = TlsClientHelloParser.extractSni(initialClientHello)
            Log.i(TAG, "MITM SNI=$sni for port $clientPort")

            val mitm = RootCaInstaller(vpnService)
            if (!mitm.isAvailable()) {
                Log.e(TAG, "MITM CA not available, cannot intercept TLS")
                close()
                return
            }

            serverChannel = connectServer(session.destinationAddress, session.destinationPort)
            if (serverChannel == null) {
                Log.e(TAG, "Failed to connect to server for MITM")
                close()
                return
            }

            val serverCtx = SSLContext.getInstance("TLS")
            serverCtx.init(null, null, null)
            serverEngine = serverCtx.createSSLEngine(
                intToIp(session.destinationAddress), session.destinationPort
            )
            serverEngine!!.setUseClientMode(true)
            serverEngine!!.beginHandshake()

            val serverNetIn = ByteBuffer.allocate(32768)
            val serverNetOut = ByteBuffer.allocate(32768)
            serverNetIn.put(initialClientHello)
            serverNetIn.flip()

            val serverOk = runEngineHandshake(
                engine = serverEngine!!,
                netIn = serverNetIn,
                netOut = serverNetOut,
                appOut = ByteBuffer.allocate(16384),
                readFromTransport = { readFromServer() },
                writeToTransport = { buf -> writeToServer(buf) }
            )

            if (!serverOk) {
                Log.e(TAG, "Server-side handshake failed")
                close()
                return
            }

            serverCert = serverEngine!!.session.peerCertificates.firstOrNull() as? X509Certificate
            if (serverCert == null) {
                Log.e(TAG, "No server certificate received")
                close()
                return
            }
            Log.i(TAG, "Server cert subject: ${serverCert!!.subjectX500Principal.name}")

            val serial = System.currentTimeMillis()
            val clientSslContext = mitm.createServerSslContext(serverCert!!, serial)
            if (clientSslContext == null) {
                Log.e(TAG, "Failed to create client SSL context with forged cert")
                close()
                return
            }
            clientEngine = clientSslContext.createSSLEngine()
            clientEngine!!.setUseClientMode(false)
            clientEngine!!.beginHandshake()

            val clientNetIn = ByteBuffer.allocate(32768)
            val clientNetOut = ByteBuffer.allocate(32768)
            val clientAppOut = ByteBuffer.allocate(16384)
            clientNetIn.put(initialClientHello)
            clientNetIn.flip()

            val clientDone = runEngineHandshake(
                engine = clientEngine!!,
                netIn = clientNetIn,
                netOut = clientNetOut,
                appOut = clientAppOut,
                readFromTransport = { drainClientQueue() },
                writeToTransport = { buf -> writeToTunClient(buf) }
            )
            Log.i(TAG, "Client-side handshake done=$clientDone, status=${clientEngine?.handshakeStatus}")

            if (!clientDone) {
                Log.w(TAG, "Client-side handshake incomplete, continuing anyway")
            }

            Log.i(TAG, "TLS MITM established for port $clientPort (SNI=$sni)")
            VpnLogManager.logConnection(
                vpnService,
                "TCP",
                "10.0.0.2",
                session.clientPort,
                intToIp(session.destinationAddress),
                session.destinationPort,
                0,
                VpnLogManager.AuditCategory.VERBOSE,
                "TLS MITM active • SNI=$sni"
            )
            proxyLoop()

        } catch (e: Exception) {
            Log.e(TAG, "TLS MITM error: ${e.message}", e)
            close()
        }
    }

    private fun proxyLoop() {
        val clientAppDataOut = ByteBuffer.allocate(16384)
        val clientAppDataIn = ByteBuffer.allocate(16384)
        val clientNetDataOut = ByteBuffer.allocate(32768)
        val serverAppDataOut = ByteBuffer.allocate(16384)
        val serverNetDataOut = ByteBuffer.allocate(32768)
        val serverNetDataIn = ByteBuffer.allocate(32768)

        while (running) {
            val clientStatus = clientEngine?.handshakeStatus ?: break
            val serverStatus = serverEngine?.handshakeStatus ?: break

            if (clientStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
                serverStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                handleAppData(clientAppDataIn, clientNetDataOut, serverAppDataOut, serverNetDataOut, serverNetDataIn)
            } else {
                if (clientStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                    driveClientHandshake(clientAppDataIn, clientNetDataOut)
                }
                if (serverStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                    driveServerHandshake(serverAppDataOut, serverNetDataOut, serverNetDataIn)
                }
            }

            synchronized(clientQueueLock) {
                if (!running) break
            }
            Thread.sleep(1)
        }
    }

    private fun handleAppData(
        clientAppIn: ByteBuffer,
        clientNetOut: ByteBuffer,
        serverAppOut: ByteBuffer,
        serverNetOut: ByteBuffer,
        serverNetIn: ByteBuffer
    ) {
        synchronized(clientQueueLock) {
            var item: ByteArray?
            while (clientQueue.poll().also { item = it } != null) {
                if (item!!.isNotEmpty()) {
                    clientAppIn.put(item!!)
                }
            }
        }
        if (clientAppIn.position() > 0) {
            clientAppIn.flip()
            while (clientAppIn.hasRemaining()) {
                clientNetOut.clear()
                val result = clientEngine!!.unwrap(clientAppIn, clientNetOut)
                if (result.status == SSLEngineResult.Status.OK) {
                    if (result.bytesProduced() > 0) {
                        clientNetOut.flip()
                        writeToServer(clientNetOut)
                        clientNetOut.clear()
                    }
                } else if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    clientAppIn.position(clientAppIn.limit())
                    break
                } else {
                    Log.w(TAG, "Client unwrap (app) status: ${result.status}")
                    clientNetOut.clear()
                    break
                }
            }
            clientAppIn.compact()
        }

        val sc = serverChannel
        if (sc != null && sc.isConnected) {
            serverNetIn.clear()
            var read = try { sc.read(serverNetIn) } catch (e: Exception) { -1 }
            if (read == -1) {
                running = false
                return
            }
        }
        if (serverNetIn.position() > 0) {
            serverNetIn.flip()
            while (serverNetIn.hasRemaining()) {
                serverAppOut.clear()
                val result = serverEngine!!.unwrap(serverNetIn, serverAppOut)
                if (result.status == SSLEngineResult.Status.OK) {
                    if (result.bytesProduced() > 0) {
                        serverAppOut.flip()
                        val plain = ByteArray(serverAppOut.remaining())
                        serverAppOut.get(plain)
                        serverAppOut.clear()
                        clientNetOut.clear()
                        val wrapResult = clientEngine!!.wrap(ByteBuffer.wrap(plain), clientNetOut)
                        if (wrapResult.bytesProduced() > 0) {
                            clientNetOut.flip()
                            writeToTunClient(clientNetOut)
                            clientNetOut.clear()
                        }
                    }
                } else if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    serverNetIn.position(serverNetIn.limit())
                    break
                } else {
                    Log.w(TAG, "Server unwrap (app) status: ${result.status}")
                    break
                }
            }
            serverNetIn.compact()
        }

        if (!running) return

        synchronized(clientQueueLock) {
            var item: ByteArray?
            while (clientQueue.poll().also { item = it } != null) {
                if (item!!.isNotEmpty()) {
                    clientAppIn.put(item!!)
                }
            }
        }
        if (clientAppIn.position() > 0) {
            clientAppIn.flip()
            while (clientAppIn.hasRemaining()) {
                clientNetOut.clear()
                val result = clientEngine!!.unwrap(clientAppIn, clientNetOut)
                if (result.status == SSLEngineResult.Status.OK) {
                    if (result.bytesProduced() > 0) {
                        clientNetOut.flip()
                        writeToServer(clientNetOut)
                        clientNetOut.clear()
                    }
                } else if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    clientAppIn.position(clientAppIn.limit())
                    break
                } else {
                    Log.w(TAG, "Client unwrap (app2) status: ${result.status}")
                    clientNetOut.clear()
                    break
                }
            }
            clientAppIn.compact()
        }
    }

    private fun driveClientHandshake(appOut: ByteBuffer, netOut: ByteBuffer) {
        val engine = clientEngine ?: return
        var needMore = true
        while (needMore && running) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(ByteBuffer.allocate(0), netOut)
                    if (result.bytesProduced() > 0) {
                        netOut.flip()
                        writeToTunClient(netOut)
                        netOut.clear()
                    }
                    needMore = when (result.status) {
                        SSLEngineResult.Status.OK,
                        SSLEngineResult.Status.CLOSED -> false
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> true
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            Log.w(TAG, "Client BUFFER_OVERFLOW")
                            false
                        }
                        else -> false
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks(engine)
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val peerData = drainClientQueue()
                    if (peerData != null) {
                        val netIn = ByteBuffer.wrap(peerData)
                        netOut.clear()
                        appOut.clear()
                        val result = engine.unwrap(netIn, appOut)
                        if (result.bytesProduced() > 0 && appOut.position() > 0) {
                            appOut.flip()
                            writeToServer(appOut)
                            appOut.clear()
                        }
                        needMore = when (result.status) {
                            SSLEngineResult.Status.OK -> false
                            SSLEngineResult.Status.BUFFER_UNDERFLOW -> true
                            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                                Log.w(TAG, "Client unwrap BUFFER_OVERFLOW")
                                true
                            }
                            SSLEngineResult.Status.CLOSED -> {
                                Log.w(TAG, "Client engine closed")
                                false
                            }
                            else -> false
                        }
                    } else {
                        Thread.sleep(5)
                    }
                }
                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                    needMore = false
                }
            }
        }
        runDelegatedTasks(engine)
    }

    private fun driveServerHandshake(appOut: ByteBuffer, netOut: ByteBuffer, netIn: ByteBuffer) {
        val engine = serverEngine ?: return
        var needMore = true
        while (needMore && running) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(ByteBuffer.allocate(0), netOut)
                    if (result.bytesProduced() > 0) {
                        netOut.flip()
                        writeToServer(netOut)
                        netOut.clear()
                    }
                    needMore = when (result.status) {
                        SSLEngineResult.Status.OK,
                        SSLEngineResult.Status.CLOSED -> false
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> true
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            Log.w(TAG, "Server BUFFER_OVERFLOW")
                            false
                        }
                        else -> false
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks(engine)
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val sc = serverChannel
                    if (sc != null && sc.isConnected && netIn.hasRemaining()) {
                        netOut.clear()
                        val result = engine.unwrap(netIn, appOut)
                        if (result.bytesProduced() > 0 && appOut.position() > 0) {
                            appOut.flip()
                            writeToServer(appOut)
                            appOut.clear()
                        }
                        needMore = when (result.status) {
                            SSLEngineResult.Status.OK -> false
                            SSLEngineResult.Status.BUFFER_UNDERFLOW -> true
                            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                                Log.w(TAG, "Server unwrap BUFFER_OVERFLOW")
                                true
                            }
                            SSLEngineResult.Status.CLOSED -> {
                                Log.w(TAG, "Server engine closed")
                                false
                            }
                            else -> false
                        }
                    } else {
                        val peerData = readFromServer()
                        if (peerData != null) {
                            netIn.clear()
                            netIn.put(peerData)
                            netIn.flip()
                        } else {
                            Thread.sleep(5)
                        }
                    }
                }
                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                    needMore = false
                }
            }
        }
        runDelegatedTasks(engine)
    }

    private fun runEngineHandshake(
        engine: SSLEngine,
        netIn: ByteBuffer,
        netOut: ByteBuffer,
        appOut: ByteBuffer,
        readFromTransport: () -> ByteArray?,
        writeToTransport: (ByteBuffer) -> Unit
    ): Boolean {
        var iterations = 0
        var needMore = true
        while (needMore && iterations < 800) {
            iterations++
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(ByteBuffer.allocate(0), netOut)
                    if (result.bytesProduced() > 0) {
                        netOut.flip()
                        writeToTransport(netOut)
                        netOut.clear()
                    }
                    needMore = when (result.status) {
                        SSLEngineResult.Status.OK,
                        SSLEngineResult.Status.CLOSED -> false
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> true
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            Log.w(TAG, "Handshake wrap BUFFER_OVERFLOW")
                            val newBuf = ByteBuffer.allocate(netOut.capacity() * 2)
                            netOut.flip()
                            newBuf.put(netOut)
                            netOut.clear()
                            true
                        }
                        else -> false
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val peerData = readFromTransport()
                    if (peerData != null) {
                        netIn.clear()
                        netIn.put(peerData)
                        netIn.flip()
                        appOut.clear()
                        val result = engine.unwrap(netIn, appOut)
                        if (result.bytesProduced() > 0 && appOut.position() > 0) {
                            appOut.flip()
                            Log.d(TAG, "Handshake produced ${appOut.remaining()} app bytes")
                            appOut.clear()
                        }
                        needMore = when (result.status) {
                            SSLEngineResult.Status.OK -> false
                            SSLEngineResult.Status.BUFFER_UNDERFLOW -> true
                            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                                Log.w(TAG, "Handshake unwrap BUFFER_OVERFLOW")
                                true
                            }
                            SSLEngineResult.Status.CLOSED -> {
                                Log.w(TAG, "Handshake transport closed")
                                false
                            }
                            else -> false
                        }
                    } else {
                        Thread.sleep(5)
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks(engine)
                }
                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                    needMore = false
                }
            }
        }
        if (iterations >= 800) {
            Log.w(TAG, "Handshake iteration limit reached")
        }
        return !needMore || engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
    }

    private fun runDelegatedTasks(engine: SSLEngine) {
        var task: Runnable?
        while (engine.delegatedTask.also { task = it } != null) {
            try {
                task!!.run()
            } catch (e: Exception) {
                Log.w(TAG, "Delegated task failed: ${e.message}")
            }
        }
    }

    private fun drainClientQueue(): ByteArray? {
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        synchronized(clientQueueLock) {
            val it = clientQueue.iterator()
            while (it.hasNext() && total < 65536) {
                val chunk = it.next()
                if (chunk.isNotEmpty()) {
                    chunks.add(chunk)
                    total += chunk.size
                }
                it.remove()
            }
        }
        if (chunks.isEmpty()) return null
        val merged = ByteArray(total)
        var off = 0
        for (c in chunks) {
            System.arraycopy(c, 0, merged, off, c.size)
            off += c.size
        }
        return merged
    }

    private fun readFromServer(): ByteArray? {
        val sc = serverChannel ?: return null
        val buf = ByteBuffer.allocate(32768)
        return try {
            val read = sc.read(buf)
            if (read > 0) {
                buf.flip()
                val data = ByteArray(read)
                buf.get(data)
                data
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Server read failed: ${e.message}")
            running = false
            null
        }
    }

    private fun writeToServer(buf: ByteBuffer) {
        val sc = serverChannel ?: return
        try {
            buf.flip()
            var written = 0L
            while (buf.hasRemaining()) {
                val w = sc.write(buf)
                if (w <= 0) break
                written += w
            }
            buf.clear()
            serverSeqNum += written
            session.serverSeqNum = serverSeqNum
        } catch (e: Exception) {
            Log.w(TAG, "Write to server failed: ${e.message}")
            running = false
        }
    }

    private fun writeToTunClient(buf: ByteBuffer) {
        val data = ByteArray(buf.remaining())
        buf.get(data)
        buf.clear()
        val prevSeq = session.serverSeqNum
        try {
            val totalLength = 40 + data.size
            val response = ByteBuffer.allocate(totalLength)
            val ip = IpHeader(response, 0)
            response.put(0, 0x45.toByte())
            ip.totalLength = totalLength
            response.put(8, 64.toByte())
            response.put(9, 6.toByte())
            ip.sourceAddress = session.destinationAddress
            ip.destinationAddress = VpnNatEngine.LOCAL_IP_INT
            ip.computeChecksum()
            val tcp = TcpHeader(response, 20)
            tcp.sourcePort = session.destinationPort
            tcp.destinationPort = session.clientPort
            tcp.seqNum = session.serverSeqNum
            tcp.ackNum = session.clientSeqNum
            response.put(20 + 12, 0x50.toByte())
            tcp.flags = 0x18 // ACK | PSH
            response.putShort(20 + 14, 0xFFFF.toShort())
            response.position(40)
            response.put(data)
            tcp.computeChecksum(ip)
            writeToTun(response.array(), totalLength)
            session.serverSeqNum = prevSeq + data.size
        } catch (e: Exception) {
            Log.w(TAG, "writeToTunClient failed: ${e.message}")
        }
    }

    private fun connectServer(dstIp: Int, dstPort: Int): SocketChannel? {
        return try {
            val ch = SocketChannel.open()
            ch.configureBlocking(true)
            ch.socket().tcpNoDelay = true
            ch.socket().soTimeout = 15000
            ch.connect(InetSocketAddress(intToInetAddress(dstIp), dstPort))
            ch.configureBlocking(false)
            ch.socket().soTimeout = 0
            Log.i(TAG, "Connected to ${intToIp(dstIp)}:$dstPort for MITM")
            ch
        } catch (e: Exception) {
            Log.e(TAG, "Server connect failed: ${e.message}")
            null
        }
    }

    fun close() {
        running = false
        try { serverChannel?.close() } catch (_: Exception) {}
        clientEngine = null
        serverEngine = null
        serverChannel = null
        TlsMitmEngine.removeSession(clientPort)
        try { session.socketChannel?.close() } catch (_: Exception) {}
        session.isTlsMitm = false
        session.tlsMitmHandler = null
        Log.i(TAG, "TLS MITM closed for port $clientPort")
    }

    private fun intToIp(ip: Int): String = String.format(
        "%d.%d.%d.%d",
        (ip shr 24) and 0xFF,
        (ip shr 16) and 0xFF,
        (ip shr 8) and 0xFF,
        ip and 0xFF
    )

    private fun intToInetAddress(ip: Int): java.net.InetAddress {
        val buf = ByteBuffer.allocate(4).putInt(ip)
        return java.net.InetAddress.getByAddress(buf.array())
    }
}
