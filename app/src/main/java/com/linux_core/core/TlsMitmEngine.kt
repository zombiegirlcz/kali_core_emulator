package com.linux_core.core

import android.net.VpnService
import android.os.Build
import android.util.Log
import com.linux_core.security.RootCaInstaller
import com.linux_core.security.TlsClientHelloParser
import com.linux_core.security.VpnSettings
import java.lang.reflect.Constructor
import java.io.IOException
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
import javax.net.ssl.StandardConstants

object TlsMitmEngine {

    private const val TAG = "TlsMitmEngine"
    private val sessions = ConcurrentHashMap<Int, TlsMitmSession>()
    private var executor: ExecutorService? = null
    private var selector: Selector? = null
    @Volatile private var appContext: android.content.Context? = null

    data class MitmSessionInfo(
        val clientPort: Int,
        val sni: String?,
        val alpn: String?,
        val cipherSuite: String?,
        val certSubject: String?,
        val certIssuer: String?,
        val certNotBefore: String?,
        val certNotAfter: String?,
        val isActivelyDecrypting: Boolean,
        val startTime: Long
    )

    fun init(executor: ExecutorService, selector: Selector, context: android.content.Context) {
        this.executor = executor
        this.selector = selector
        this.appContext = context.applicationContext
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
        val captureOnly = VpnSettings.isMitmCaptureOnly(vpnService)
        val mitm = TlsMitmSession(vpnService, session, payload, writeToTun, captureOnly)
        sessions[session.clientPort] = mitm
        session.isTlsMitm = true
        session.tlsMitmHandler = mitm
        executor?.submit { mitm.start() }
        Log.i(TAG, "TLS MITM started for port ${session.clientPort} (captureOnly=$captureOnly)")
    }

    fun removeSession(clientPort: Int) {
        sessions.remove(clientPort)
    }

    fun getSessionSnapshots(): List<Pair<Int, String>> {
        val ctx = appContext ?: return emptyList()
        val ports = sessions.keys.toSet()
        return MitmTrafficStore.get(ctx).queryForSessionSnapshots(ports)
    }

    fun getActiveSessionPorts(): Set<Int> = sessions.keys.toSet()

    fun getSessionInfo(port: Int): MitmSessionInfo? {
        val session = sessions[port] ?: return null
        return MitmSessionInfo(
            clientPort = session.clientPort,
            sni = session.sni,
            alpn = session.sessionAlpn,
            cipherSuite = session.serverEngine?.session?.cipherSuite,
            certSubject = session.serverCert?.subjectX500Principal?.name,
            certIssuer = session.serverCert?.issuerX500Principal?.name,
            certNotBefore = session.serverCert?.notBefore?.toString(),
            certNotAfter = session.serverCert?.notAfter?.toString(),
            isActivelyDecrypting = session.isActivelyDecrypting,
            startTime = session.startTime
        )
    }

    fun getTrafficRecords(port: Int, limit: Int = 50): List<MitmTrafficStore.Record> {
        val ctx = appContext ?: return emptyList()
        return MitmTrafficStore.get(ctx).query(limit = limit, sessionPort = port)
    }

    /** True when at least one session reached proxyLoop (actively decrypting TLS). */
    fun shouldBlockQuic(): Boolean = sessions.values.any { it.isActivelyDecrypting }
}

class TlsMitmSession(
    private val vpnService: VpnService,
    private val session: VpnNatEngine.TcpSession,
    private val initialClientHello: ByteArray,
    private val writeToTun: (ByteArray, Int) -> Unit,
    private val captureOnly: Boolean = false
) {
    companion object {
        private const val TAG = "TlsMitmSession"
        @Volatile private var sniServerNameFactory: ((String) -> javax.net.ssl.SNIServerName)? = null
    }

    @Volatile var clientPort = session.clientPort
    private var serverChannel: SocketChannel? = null
    private var clientEngine: SSLEngine? = null
    @Volatile var serverEngine: SSLEngine? = null
    @Volatile var serverCert: X509Certificate? = null
    var sni: String? = null
    @Volatile var sessionAlpn: String? = null
    private var running = true

    private val trafficStore = MitmTrafficStore.get(vpnService)
    private val clientHttpParser = Http1StreamParser { msg ->
        trafficStore.logMessage(clientPort, sni ?: msg.headers["host"], msg, sessionAlpn)
    }
    private val serverHttpParser = Http1StreamParser { msg ->
        trafficStore.logMessage(clientPort, sni ?: msg.headers["host"], msg, sessionAlpn)
    }

    private val clientQueue = ArrayDeque<ByteArray>()
    private val clientQueueLock = Any()

    @Volatile private var clientSeqNum: Long = session.clientSeqNum
    @Volatile private var serverSeqNum: Long = session.serverSeqNum
    @Volatile private var closed = false
    @Volatile private var lastActivityTime = System.currentTimeMillis()
    @Volatile var isActivelyDecrypting = false
    @Volatile var startTime = System.currentTimeMillis()

    private val PROXY_LOOP_TIMEOUT_MS = 60_000L

    init {
        session.clientSeqNum = clientSeqNum
        session.serverSeqNum = serverSeqNum
    }

    private fun feedPlaintext(direction: String, plain: ByteArray) {
        if (plain.isEmpty()) return
        when (direction) {
            "CLIENT->SERVER" -> clientHttpParser.feed(plain)
            "SERVER->CLIENT" -> serverHttpParser.feed(plain)
        }
    }

    fun handleClientData(data: ByteArray) {
        synchronized(clientQueueLock) {
            if (clientQueue.size >= 64) {
                clientQueue.removeFirst()
            }
            clientQueue.add(data)
        }
        lastActivityTime = System.currentTimeMillis()
    }

    fun start() {
        try {
            sni = TlsClientHelloParser.extractSni(initialClientHello)
            Log.i(TAG, "MITM SNI=$sni for port $clientPort (captureOnly=$captureOnly)")

            if (captureOnly) {
                startCaptureOnly()
                return
            }

            val mitm = RootCaInstaller(vpnService)
            if (!mitm.isAvailable()) {
                Log.w(TAG, "MITM CA not available for $sni — falling back to passthrough")
                fallingBackToPassthrough()
                return
            }

            serverChannel = connectServer(session.destinationAddress, session.destinationPort)
            if (serverChannel == null) {
                Log.w(TAG, "Cannot connect to server for $sni — falling back to passthrough")
                fallingBackToPassthrough()
                return
            }

            val serverCtx = SSLContext.getInstance("TLS")
            serverCtx.init(null, null, null)
            serverEngine = serverCtx.createSSLEngine(
                intToIp(session.destinationAddress), session.destinationPort
            )
            serverEngine!!.setUseClientMode(true)

            val fallbackFromPrefs = VpnSettings.getMitmSniFallback(vpnService)
            val destinationHost = intToIp(session.destinationAddress)
            val effectiveSni = sni ?: fallbackFromPrefs ?: destinationHost
            val serverParams = serverEngine!!.sslParameters
            serverParams.serverNames = buildSniList(effectiveSni)
            serverEngine!!.sslParameters = serverParams
            Log.i(TAG, "serverEngine configured with SNI=$effectiveSni, originalSNI=$sni, fallbackPref=$fallbackFromPrefs")

            serverEngine!!.beginHandshake()

            val serverNetIn = ByteBuffer.allocate(32768)
            val serverNetOut = ByteBuffer.allocate(32768)

            val serverOk = runEngineHandshake(
                engine = serverEngine!!,
                netIn = serverNetIn,
                netOut = serverNetOut,
                appOut = ByteBuffer.allocate(16384),
                readFromTransport = { readFromServer() },
                writeToTransport = { buf ->
                    buf.flip()
                    writeToServer(buf)
                },
                maxIterations = 50
            )

            if (!serverOk) {
                Log.w(TAG, "Server handshake failed: status=${serverEngine?.handshakeStatus}, " +
                    "cipher=${serverEngine?.session?.cipherSuite}, peerHost=${intToIp(session.destinationAddress)}, " +
                    "sni=$sni, effectiveSni=$effectiveSni")
                fallingBackToPassthrough()
                return
            }

            serverCert = serverEngine!!.session.peerCertificates.firstOrNull() as? X509Certificate
            if (serverCert == null) {
                Log.w(TAG, "Handshake did not yield certificate: status=${serverEngine?.handshakeStatus}, " +
                    "state=${serverEngine?.session?.cipherSuite}, peerHost=${intToIp(session.destinationAddress)}, " +
                    "sni=$sni, effectiveSni=$effectiveSni")
                fallingBackToPassthrough()
                return
            }
            Log.i(TAG, "Server cert subject: ${serverCert!!.subjectX500Principal.name}")

            val certSubject = effectiveSni ?: intToIp(session.destinationAddress)
            val serial = System.currentTimeMillis()
            val clientSslContext = mitm.createServerSslContext(
                serverCert!!, serial,
                sanDns = listOf(certSubject),
                sanIp = listOf(intToIp(session.destinationAddress))
            )
            if (clientSslContext == null) {
                Log.w(TAG, "Failed to create client SSL context with forged cert, falling back to passthrough")
                fallingBackToPassthrough()
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
                writeToTransport = { buf ->
                    buf.flip()
                    writeToTunClient(buf)
                }
            )
            Log.i(TAG, "Client-side handshake done=$clientDone, status=${clientEngine?.handshakeStatus}")

            if (!clientDone) {
                Log.w(TAG, "Client-side handshake incomplete, continuing anyway")
            }

            Log.i(TAG, "TLS MITM established for port $clientPort (SNI=$sni)")
            sessionAlpn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    clientEngine?.applicationProtocol?.takeIf { it.isNotBlank() }
                        ?: serverEngine?.applicationProtocol?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            } else null
            trafficStore.logSessionEvent(
                clientPort,
                sessionAlpn,
                "MITM established SNI=${sni ?: effectiveSni}"
            )
            if (sessionAlpn == "h2") {
                trafficStore.logSessionEvent(
                    clientPort,
                    "h2",
                    "HTTP/2 session — metadata only (HPACK not decoded)"
                )
            }
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
            serverChannel?.configureBlocking(false)
            serverChannel?.socket()?.soTimeout = 0
            isActivelyDecrypting = true
            proxyLoop()

        } catch (e: Exception) {
            Log.e(TAG, "TLS MITM error: ${e.message}", e)
        } finally {
            close()
        }
    }

    private fun startCaptureOnly() {
        Log.i(TAG, "Starting capture-only MITM for $sni on port $clientPort")
        val mitm = RootCaInstaller(vpnService)
        if (!mitm.isAvailable()) {
            Log.e(TAG, "MITM CA not available for capture-only")
            close()
            return
        }

        val sniDomain = sni ?: intToIp(session.destinationAddress)
        val clientSslContext = mitm.createCaptureOnlySslContext(sniDomain)
        if (clientSslContext == null) {
            Log.w(TAG, "Failed to create capture-only SSL context for $sniDomain")
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
            writeToTransport = { buf ->
                buf.flip()
                writeToTunClient(buf)
            }
        )
        Log.i(TAG, "Capture-only TLS handshake done=$clientDone for $sni port $clientPort")

        VpnLogManager.logConnection(
            vpnService, "TCP", "10.0.0.2",
            session.clientPort, intToIp(session.destinationAddress),
            session.destinationPort, 0, VpnLogManager.AuditCategory.VERBOSE,
            "TLS CAPTURE only • SNI=$sni"
        )

        captureLoop()
    }

    private fun captureLoop() {
        val clientAppIn = ByteBuffer.allocate(16384)
        val clientNetOut = ByteBuffer.allocate(32768)

        while (running) {
            synchronized(clientQueueLock) {
                var item: ByteArray?
                while (clientQueue.poll().also { item = it } != null) {
                    if (item!!.isNotEmpty()) {
                        clientAppIn.put(item!!)
                        lastActivityTime = System.currentTimeMillis()
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
                            val plain = ByteArray(clientNetOut.remaining())
                            clientNetOut.get(plain)
                            clientNetOut.clear()
                            feedPlaintext("CLIENT->SERVER", plain)
                            val preview = String(plain, Charsets.UTF_8).take(256)
                            Log.i(TAG, "CAPTURED ${plain.size}B from $sni: $preview")
                        }
                    } else if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        break
                    } else {
                        Log.w(TAG, "Capture unwrap status: ${result.status} for $sni")
                        break
                    }
                }
                clientAppIn.compact()
            }

            if (System.currentTimeMillis() - lastActivityTime > 10000) {
                Log.i(TAG, "Capture idle timeout for $sni on port $clientPort")
                break
            }

            if (clientAppIn.position() == 0) {
                Thread.sleep(50)
            }
        }

        Log.i(TAG, "Capture session ended for $sni on port $clientPort")
        close()
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

            if (System.currentTimeMillis() - lastActivityTime > PROXY_LOOP_TIMEOUT_MS) {
                Log.w(TAG, "TLS MITM proxy loop idle timeout after ${PROXY_LOOP_TIMEOUT_MS}ms, closing session $clientPort")
                close()
                return
            }

            val workDone = if (clientStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
                serverStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                handleAppData(clientAppDataIn, clientNetDataOut, serverAppDataOut, serverNetDataOut, serverNetDataIn)
            } else {
                if (clientStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                    driveClientHandshake(clientAppDataIn, clientNetDataOut)
                }
                if (serverStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                    driveServerHandshake(serverAppDataOut, serverNetDataOut, serverNetDataIn)
                }
                true
            }

            synchronized(clientQueueLock) {
                if (!running) break
            }
            if (workDone) {
                lastActivityTime = System.currentTimeMillis()
                Thread.sleep(1)
            } else {
                Thread.sleep(15)
            }
        }
    }

    private fun handleAppData(
        clientAppIn: ByteBuffer,
        clientNetOut: ByteBuffer,
        serverAppOut: ByteBuffer,
        serverNetOut: ByteBuffer,
        serverNetIn: ByteBuffer
    ): Boolean {
        var worked = false
        synchronized(clientQueueLock) {
            var item: ByteArray?
            while (clientQueue.poll().also { item = it } != null) {
                if (item!!.isNotEmpty()) {
                    clientAppIn.put(item!!)
                    lastActivityTime = System.currentTimeMillis()
                    worked = true
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
                        val plain = ByteArray(clientNetOut.remaining())
                        clientNetOut.get(plain)
                        clientNetOut.clear()

                        feedPlaintext("CLIENT->SERVER", plain)

                        serverNetOut.clear()
                        val wrapResult = serverEngine!!.wrap(ByteBuffer.wrap(plain), serverNetOut)
                        if (wrapResult.bytesProduced() > 0) {
                            serverNetOut.flip()
                            writeToServer(serverNetOut)
                            lastActivityTime = System.currentTimeMillis()
                            worked = true
                        }
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
        var readBytes = 0
        if (sc != null && sc.isConnected) {
            serverNetIn.clear()
            val read = try { sc.read(serverNetIn) } catch (e: Exception) { -1 }
            if (read == -1) {
                running = false
                return false
            }
            if (read > 0) {
                readBytes = read
                lastActivityTime = System.currentTimeMillis()
                worked = true
            }
        }
        if (readBytes > 0) {
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

                        feedPlaintext("SERVER->CLIENT", plain)

                        clientNetOut.clear()
                        val wrapResult = clientEngine!!.wrap(ByteBuffer.wrap(plain), clientNetOut)
                        if (wrapResult.bytesProduced() > 0) {
                            clientNetOut.flip()
                            writeToTunClient(clientNetOut)
                            lastActivityTime = System.currentTimeMillis()
                            worked = true
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
        }
        return worked
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
        writeToTransport: (ByteBuffer) -> Unit,
        maxIterations: Int = 800
    ): Boolean {
        var iterations = 0
        var needMore = true
        while (needMore && iterations < maxIterations) {
            iterations++
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(ByteBuffer.allocate(0), netOut)
                    if (result.bytesProduced() > 0) {
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
        if (iterations >= maxIterations) {
            Log.w(TAG, "Handshake iteration limit reached ($maxIterations) status=${engine.handshakeStatus}")
        }
        return engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
               engine.session.cipherSuite != "SSL_NULL_WITH_NULL_NULL"
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

    private fun writeFully(channel: SocketChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) {
            val w = channel.write(buf)
            if (w > 0) continue
            if (w < 0) throw IOException("Channel closed during write")
            Thread.sleep(1)
        }
    }

    private fun writeToServer(buf: ByteBuffer) {
        val sc = serverChannel ?: return
        try {
            // buf je již načtený (flipnutý) od volajícího – neflipovat znovu!
            writeFully(sc, buf)
            buf.clear()
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

            if (!VpnCaptureService.protectSocket(ch.socket())) {
                Log.e(TAG, "protect() failed for MITM server socket — aborting connect")
                ch.close()
                return null
            }

            ch.connect(InetSocketAddress(intToInetAddress(dstIp), dstPort))
            // Keep blocking until handshake completes; non-blocking set before proxyLoop/passthroughLoop
            Log.i(TAG, "Connected to ${intToIp(dstIp)}:$dstPort for MITM")
            ch
        } catch (e: Exception) {
            Log.e(TAG, "Server connect failed: ${e.message}")
            null
        }
    }

    fun close() {
        if (closed) return
        running = false
        TlsMitmEngine.removeSession(clientPort)

        try {
            serverChannel?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server channel for port $clientPort: ${e.message}")
        } finally {
            serverChannel = null
        }

        try {
            clientEngine?.session?.invalidate()
        } catch (_: Exception) {}
        clientEngine = null
        serverEngine = null

        synchronized(clientQueueLock) {
            clientQueue.clear()
        }
        clientHttpParser.reset()
        serverHttpParser.reset()

        session.isTlsMitm = false
        session.tlsMitmHandler = null
        isActivelyDecrypting = false

        sendRstToClient()

        try {
            session.socketChannel?.close()
        } catch (_: Exception) {}

        closed = true
        Log.i(TAG, "TLS MITM closed for port $clientPort, all resources released")
    }

    private fun fallingBackToPassthrough() {
        Log.w(TAG, "Falling back to passthrough for port $clientPort SNI=$sni")
        running = true
        clientEngine = null
        serverEngine = null
        trafficStore.logSessionEvent(
            clientPort,
            null,
            "MITM falling back to passthrough SNI=$sni"
        )
        // Always fresh socket — poisoned channel may have MITM ClientHello on the wire
        try {
            serverChannel?.close()
        } catch (_: Exception) {}
        serverChannel = null
        serverChannel = connectServer(session.destinationAddress, session.destinationPort)
        if (serverChannel == null) {
            Log.e(TAG, "Cannot reconnect to server for passthrough on port $clientPort")
            close()
            return
        }
        try {
            passthroughLoop()
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "passthroughLoop error on port $clientPort: ${e.message}")
            }
        } finally {
            running = false
            closed = true
            TlsMitmEngine.removeSession(clientPort)
            try {
                serverChannel?.close()
            } catch (_: Exception) {}
            serverChannel = null
            try {
                session.socketChannel?.close()
            } catch (_: Exception) {}
            synchronized(clientQueueLock) {
                clientQueue.clear()
            }
            session.isTlsMitm = false
            session.tlsMitmHandler = null
            sendRstToClient()
            Log.i(TAG, "TLS MITM passthrough ended for port $clientPort, all resources released")
        }
    }

    private fun passthroughLoop() {
        Log.i(TAG, "TLS MITM passthrough established for port $clientPort (SNI=$sni)")
        VpnLogManager.logConnection(
            vpnService,
            "TCP",
            "10.0.0.2",
            session.clientPort,
            intToIp(session.destinationAddress),
            session.destinationPort,
            0,
            VpnLogManager.AuditCategory.VERBOSE,
            "TLS MITM passthrough active • SNI=$sni"
        )
        var totalForwarded = 0L
        var lastActivity = System.currentTimeMillis()
        var lastLog = System.currentTimeMillis()
        val idleThreshold = 30_000L

        serverChannel?.configureBlocking(false)
        serverChannel?.socket()?.soTimeout = 5000

        // Poslat initialClientHello na server (nebyl v clientQueue!)
        if (initialClientHello.isNotEmpty()) {
            try {
                val sc = serverChannel
                if (sc != null && sc.isConnected) {
                    writeFully(sc, ByteBuffer.wrap(initialClientHello))
                    totalForwarded += initialClientHello.size
                    lastActivity = System.currentTimeMillis()
                    Log.d(TAG, "Forwarded initial TLS ClientHello (${initialClientHello.size}B) for port $clientPort")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to forward initial ClientHello on port $clientPort: ${e.message}")
            }
        }

        while (running) {
            var worked = false

            val sc = serverChannel
            if (sc != null && sc.isConnected) {
                val buf = ByteBuffer.allocate(32768)
                val read = try { sc.read(buf) } catch (e: java.net.SocketTimeoutException) { 0 } catch (e: Exception) { -1 }
                if (read == -1) {
                    break
                }
                if (read > 0) {
                    buf.flip()
                    val data = ByteArray(read)
                    buf.get(data)
                    totalForwarded += data.size
                    writeToTunClientData(data)
                    lastActivity = System.currentTimeMillis()
                    worked = true
                }
            }

            synchronized(clientQueueLock) {
                var item: ByteArray?
                while (clientQueue.poll().also { item = it } != null) {
                    if (item!!.isNotEmpty()) {
                        val wsc = serverChannel
                        if (wsc != null && wsc.isConnected) {
                            val wbuf = ByteBuffer.wrap(item!!)
                            try {
                                writeFully(wsc, wbuf)
                                totalForwarded += item!!.size
                                lastActivity = System.currentTimeMillis()
                                worked = true
                            } catch (e: Exception) {
                                break
                            }
                        }
                    }
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastActivity > idleThreshold) {
                Log.w(TAG, "passthroughLoop idle timeout after ${idleThreshold}ms on port $clientPort, forwarded=$totalForwarded")
                break
            }

            if (!worked) {
                if (now - lastLog > 300) {
                    Log.d(TAG, "passthrough idle port=$clientPort totalForwarded=$totalForwarded")
                    lastLog = now
                }
            } else {
                if (now - lastLog > 300) {
                    Log.d(TAG, "passthrough active port=$clientPort totalForwarded=$totalForwarded")
                    lastLog = now
                }
            }

            if (worked) {
                Thread.sleep(1)
            } else {
                Thread.sleep(50)
            }
        }
        Log.i(TAG, "passthroughLoop exiting port=$clientPort totalForwarded=$totalForwarded")
    }

    private fun sendRstToClient() {
        try {
            val totalLength = 40
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
            tcp.flags = 0x14 // RST | ACK
            response.putShort(20 + 14, 0.toShort())
            tcp.computeChecksum(ip)
            writeToTun(response.array(), totalLength)
        } catch (e: Exception) {
            Log.w(TAG, "sendRstToClient failed: ${e.message}")
        }
    }

    private fun writeToTunClientData(data: ByteArray) {
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
        tcp.flags = 0x18
        response.putShort(20 + 14, 0xFFFF.toShort())
        response.position(40)
        response.put(data)
        tcp.computeChecksum(ip)
        writeToTun(response.array(), totalLength)
        session.serverSeqNum += data.size
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

    private fun buildSniList(hostname: String): List<javax.net.ssl.SNIServerName> {
        val factory = sniServerNameFactory ?: synchronized(this) {
            sniServerNameFactory ?: resolveSniServerNameFactory().also { sniServerNameFactory = it }
        }
        return listOf(factory(hostname))
    }

    private fun resolveSniServerNameFactory(): (String) -> javax.net.ssl.SNIServerName {
        val reflectionCandidates = listOf(
            "sun.net.util.SNIHostName",
            "com.android.org.conscrypt.SNIHostName"
        )
        for (className in reflectionCandidates) {
            try {
                val clazz = Class.forName(className)
                val ctor = clazz.getConstructor(
                    Int::class.javaPrimitiveType,
                    ByteArray::class.java
                )
                @Suppress("UNCHECKED_CAST")
                val factory: (String) -> javax.net.ssl.SNIServerName = { hostname ->
                    ctor.newInstance(
                        StandardConstants.SNI_HOST_NAME,
                        hostname.toByteArray(Charsets.UTF_8)
                    ) as javax.net.ssl.SNIServerName
                }
                Log.i(TAG, "SNIServerName factory resolved via reflection: $className")
                return factory
            } catch (_: ClassNotFoundException) {
            } catch (_: NoSuchMethodException) {
            } catch (_: Exception) {
            }
        }

        try {
            val clazz = Class.forName("javax.net.ssl.SNIHostName")
            val ctor = clazz.getConstructor(String::class.java)
            @Suppress("UNCHECKED_CAST")
            val factory: (String) -> javax.net.ssl.SNIServerName = { hostname ->
                ctor.newInstance(hostname) as javax.net.ssl.SNIServerName
            }
            Log.i(TAG, "SNIServerName factory resolved via javax.net.ssl.SNIHostName")
            return factory
        } catch (_: Exception) {}

        Log.w(TAG, "No SNIServerName implementation found; using empty list")
        @Suppress("UNCHECKED_CAST")
        return { _ -> throw UnsupportedOperationException("No SNIServerName implementation available") }
    }
}
