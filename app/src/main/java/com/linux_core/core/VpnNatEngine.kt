package com.linux_core.core

import android.net.VpnService
import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.termux.terminal.TerminalSession
import com.linux_core.security.TlsClientHelloParser
import com.linux_core.BuildConfig

class VpnNatEngine(
    private val vpnService: VpnService,
    private val writeToTun: (ByteArray, Int) -> Unit
) {
    companion object {
        const val TAG = "VpnNatEngine"
        const val LOCAL_IP_INT = 0x0A000002 // 10.0.0.2
        private val TLS_PORTS = setOf(443, 8443, 993, 995, 587, 465, 25)
    }

    private val isRunning = AtomicBoolean(false)
    private var selector: Selector? = null
    private var selectorThread: Thread? = null
    private var aiBrainWorker: AIBrainWorker? = null
    private val connectionThreadPool = java.util.concurrent.Executors.newCachedThreadPool()
    private val pendingRegistrations = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()

    // Session maps keyed by client source port
    private val tcpSessions = ConcurrentHashMap<Int, TcpSession>()
    private val udpSessions = ConcurrentHashMap<Int, UdpSession>()

    enum class TcpState {
        CLOSED, SYN_RECEIVED, ESTABLISHED, FIN_WAIT
    }

    class TcpSession(
        val clientPort: Int,
        val destinationAddress: Int,
        val destinationPort: Int
    ) {
        var socketChannel: SocketChannel? = null
        var clientSeqNum: Long = 0
        var serverSeqNum: Long = 1000 // Server starting sequence number
        var state = TcpState.CLOSED
        val sendQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer>()
        var lastActiveTime = System.currentTimeMillis()
        
        var bytesSent: Long = 0
        var bytesReceived: Long = 0
        var lastSpeedCalcTime: Long = System.currentTimeMillis()
        var lastBytesSent: Long = 0
        var lastBytesReceived: Long = 0
        var speedUpload: Long = 0L
        var speedDownload: Long = 0L

        var isTlsMitm = false
        var tlsMitmHandler: TlsMitmSession? = null
    }

    class UdpSession(
        val clientPort: Int,
        val destinationAddress: Int,
        val destinationPort: Int
    ) {
        var datagramChannel: DatagramChannel? = null
        var lastActiveTime = System.currentTimeMillis()

        var bytesSent: Long = 0
        var bytesReceived: Long = 0
        var lastSpeedCalcTime: Long = System.currentTimeMillis()
        var lastBytesSent: Long = 0
        var lastBytesReceived: Long = 0
        var speedUpload: Long = 0L
        var speedDownload: Long = 0L
    }

    init {
        try {
            selector = Selector.open()
            aiBrainWorker = AIBrainWorker(vpnService)
            TlsMitmEngine.init(connectionThreadPool, selector!!)
            isRunning.set(true)
            startSelectorLoop()
            startDnsCacheCleaner()
            Log.i(TAG, "NAT Engine successfully initialized with Async AI Brain Worker")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NAT Engine: ${e.message}", e)
        }
    }

    private fun startDnsCacheCleaner() {
        dnsCacheCleanerThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(60_000)
                    val now = System.currentTimeMillis()
                    val it = dnsResponseCache.entries.iterator()
                    while (it.hasNext()) {
                        if (it.next().value.expiresAt < now) it.remove()
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (_: Exception) {}
            }
        }, "DnsCacheCleaner").apply { isDaemon = true; start() }
    }

    /**
     * Asynchronní AI inference — zařadí packet do fronty AIBrainWorker a
     * vrátí se okamžitě. Pokud AI není povolena nebo worker není k dispozici,
     * vrací ALLOWED.
     *
     * Rozhodnutí o blokování je založeno na cache výsledků; pro nové toky
     * se vrací ALLOWED (packet projde) a inference běží na pozadí.
     */
    private fun runAsyncInference(
        protocol: Int,
        srcPort: Int,
        dstIpStr: String,
        dstPort: Int,
        payload: ByteArray?,
        totalSize: Int
    ): VpnLogManager.AuditCategory {
        val prefs = vpnService.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("ai_enabled", true)) return VpnLogManager.AuditCategory.ALLOWED

        val worker = aiBrainWorker ?: return VpnLogManager.AuditCategory.ALLOWED
        val sessionKey = "$protocol:$srcPort:$dstPort"

        // Nejdříve zkusíme cache — pokud máme výsledek z předchozí inference
        val cached = worker.getCachedResult(sessionKey)
        if (cached != null) {
            return cached.category
        }

        // Zařaď do fronty pro asynchronní zpracování
        worker.submitForInference(
            AIBrainWorker.InferenceRequest(
                protocol = protocol,
                srcPort = srcPort,
                dstIpStr = dstIpStr,
                dstPort = dstPort,
                payload = payload,
                totalSize = totalSize,
                sessionKey = sessionKey
            )
        )

        // Vracíme ALLOWED — packet neblokujeme, dokud AI nerozhodne
        return VpnLogManager.AuditCategory.ALLOWED
    }

    private fun isMitmEnabled(): Boolean {
        val prefs = vpnService.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("enable_mitm", BuildConfig.ENABLE_MITM)
    }

    fun handlePacketFromTun(packetBuffer: ByteBuffer, length: Int) {
        if (!isRunning.get() || length < 20) return
        val ipHeader = IpHeader(packetBuffer, 0)
        val rawData = ByteArray(length)
        val pos = packetBuffer.position()
        packetBuffer.position(0)
        packetBuffer.get(rawData)
        packetBuffer.position(pos)
        val protoStr = when(ipHeader.protocol) { 6 -> "TCP"; 17 -> "UDP"; else -> "IP-${ipHeader.protocol}" }

        var srcPort = 0
        var dstPort = 0
        var payloadBytes: ByteArray? = null
        try {
            if (ipHeader.protocol == 6) { // TCP
                val tcpHeader = TcpHeader(packetBuffer, ipHeader.ihl)
                srcPort = tcpHeader.sourcePort
                dstPort = tcpHeader.destinationPort
                val headerLen = ipHeader.ihl + tcpHeader.dataOffset
                val payloadLen = ipHeader.totalLength - headerLen
                if (payloadLen > 0 && headerLen + payloadLen <= length) {
                    payloadBytes = ByteArray(payloadLen)
                    val originalPos = packetBuffer.position()
                    packetBuffer.position(headerLen)
                    packetBuffer.get(payloadBytes)
                    packetBuffer.position(originalPos)
                }
            } else if (ipHeader.protocol == 17) { // UDP
                val udpHeader = UdpHeader(packetBuffer, ipHeader.ihl)
                srcPort = udpHeader.sourcePort
                dstPort = udpHeader.destinationPort
                val payloadLen = udpHeader.length - 8
                val payloadOffset = ipHeader.ihl + 8
                if (payloadLen > 0 && payloadOffset + payloadLen <= length) {
                    payloadBytes = ByteArray(payloadLen)
                    val originalPos = packetBuffer.position()
                    packetBuffer.position(payloadOffset)
                    packetBuffer.get(payloadBytes)
                    packetBuffer.position(originalPos)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ports for raw log: ${e.message}")
        }

        // RAW TUN INTERCEPT log removed to prevent logging duplication and performance overhead

        if (ipHeader.version != 4) return // Only support IPv4 in this userspace stack

        val dstIp = ipHeader.destinationAddress
        if (VpnPeerManager.isEnabled() && (dstIp and 0xFFFFFF00.toInt()) == 0x0A090000) {
            val peerId = dstIp and 0x000000FF
            if (peerId in 1..254) {
                VpnPeerManager.sendPacketToPeer(peerId, rawData)
                return
            }
        }

        when (ipHeader.protocol) {
            6 -> handleTcpPacket(packetBuffer, ipHeader)
            17 -> handleUdpPacket(packetBuffer, ipHeader)
        }
    }

    private fun handleUdpPacket(packetBuffer: ByteBuffer, ipHeader: IpHeader) {
        val udpHeader = UdpHeader(packetBuffer, ipHeader.ihl)
        val srcPort = udpHeader.sourcePort
        val dstPort = udpHeader.destinationPort
        val dstIp = ipHeader.destinationAddress
        val dstIpStr = intToIp(dstIp)
        
        if (VpnFirewallManager.isIpBlocked(dstIpStr)) {
            val payloadLen = udpHeader.length - 8
            VpnLogManager.logConnection(vpnService, "UDP", "10.0.0.2", srcPort, dstIpStr, dstPort, if (payloadLen > 0) payloadLen else 0, VpnLogManager.AuditCategory.BLOCKED, "Blocked by firewall rules")
            return
        }
        
        val payloadOffset = ipHeader.ihl + 8
        val payloadLen = udpHeader.length - 8
        if (payloadLen <= 0) return

        // DNS parsing and custom blocklist matching
        if (dstPort == 53) {
            val originalPos = packetBuffer.position()
            val dnsPayload = ByteArray(payloadLen)
            packetBuffer.position(payloadOffset)
            packetBuffer.get(dnsPayload)
            packetBuffer.position(originalPos)
            
            val query = parseDnsQuery(dnsPayload)
            if (query != null) {
                val (domain, qType) = query
                val isBlocked = VpnLogManager.isDomainBlocked(domain)
                if (isBlocked) {
                    VpnLogManager.logDnsQuery(domain, qType, VpnLogManager.AuditCategory.BLOCKED, "Blocked by custom rules")
                    VpnLogManager.logConnection(vpnService, "UDP", "10.0.0.2", srcPort, dstIpStr, dstPort, payloadLen, VpnLogManager.AuditCategory.BLOCKED, "DNS Blocked: $domain")
                    sendDnsNxDomainResponse(packetBuffer, ipHeader, udpHeader, payloadLen)
                    return
                } else {
                    VpnLogManager.logDnsQuery(domain, qType, VpnLogManager.AuditCategory.ALLOWED)
                }
            }
        }

        var session = udpSessions[srcPort]
        if (session == null) {
            // Extraction of payload for AI brain (max 64 bytes)
            packetBuffer.position(payloadOffset)
            val payloadForAi = ByteArray(minOf(payloadLen, 64))
            packetBuffer.get(payloadForAi)
            packetBuffer.position(0) // Reset position for subsequent use

            // AI Inference — asynchronně přes AIBrainWorker
            val aiCategory = runAsyncInference(17, srcPort, dstIpStr, dstPort, payloadForAi, ipHeader.totalLength)
            val detail = if (aiCategory == VpnLogManager.AuditCategory.CRITICAL) {
                "AI: Detected critical network anomaly!"
            } else "AI: Verified UDP stream"

            VpnLogManager.logConnection(vpnService, "UDP", "10.0.0.2", srcPort, dstIpStr, dstPort, payloadLen, aiCategory, detail, payloadForAi)

            try {
                val channel = DatagramChannel.open().apply {
                    configureBlocking(false)
                }
                
                // CRITICAL: Protect channel socket from loopback routing
                if (!vpnService.protect(channel.socket())) {
                    Log.e(TAG, "protect() FAILED for UDP DatagramChannel — outbound traffic may loop back into VPN!")
                }
                
                val remoteAddr = intToInetAddress(dstIp)
                channel.connect(InetSocketAddress(remoteAddr, dstPort))
                
                session = UdpSession(srcPort, dstIp, dstPort).apply {
                    datagramChannel = channel
                }
                udpSessions[srcPort] = session

                // Register with Selector (wakeup to avoid blocking on selector contention)
                selector?.let { sel ->
                    pendingRegistrations.offer(Runnable {
                        try {
                            channel.register(sel, SelectionKey.OP_READ, session)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to register UDP channel: ${e.message}")
                        }
                    })
                    sel.wakeup()
                }
                Log.d(TAG, "Created UDP session for port $srcPort to $dstIpStr:$dstPort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to establish UDP connection: ${e.message}")
                return
            }
        } else {
            session.lastActiveTime = System.currentTimeMillis()
        }

        session.lastActiveTime = System.currentTimeMillis()
        try {
            packetBuffer.position(payloadOffset)
            packetBuffer.limit(payloadOffset + payloadLen)
            
            session.datagramChannel?.write(packetBuffer)
            session.bytesSent += payloadLen
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write UDP data to WAN: ${e.message}")
            closeUdpSession(srcPort)
        }
    }

    private fun handleTcpPacket(packetBuffer: ByteBuffer, ipHeader: IpHeader) {
        val tcpHeader = TcpHeader(packetBuffer, ipHeader.ihl)
        val srcPort = tcpHeader.sourcePort
        val dstPort = tcpHeader.destinationPort
        val dstIp = ipHeader.destinationAddress
        val dstIpStr = intToIp(dstIp)
        
        if (VpnFirewallManager.isIpBlocked(dstIpStr)) {
            if (tcpHeader.isSYN) {
                VpnLogManager.logConnection(vpnService, "TCP", "10.0.0.2", srcPort, dstIpStr, dstPort, 40, VpnLogManager.AuditCategory.BLOCKED, "Blocked by firewall rules")
                sendTcpRst(ipHeader, tcpHeader)
            }
            return
        }
        
        var session = tcpSessions[srcPort]

        if (tcpHeader.isSYN) {
            // New TCP connection request
            if (session != null) {
                closeTcpSession(srcPort)
            }
            
            // TCP SYN — defer AI till data payload arrives
            val detail = "AI: New TCP connection (deferred)"

            VpnLogManager.logConnection(vpnService, "TCP", "10.0.0.2", srcPort, dstIpStr, dstPort, 40, VpnLogManager.AuditCategory.ALLOWED, detail)

            try {
                val channel = SocketChannel.open()
                
                // CRITICAL: Protect channel socket from loopback routing
                if (!vpnService.protect(channel.socket())) {
                    Log.e(TAG, "protect() FAILED for TCP SocketChannel — outbound traffic may loop back into VPN!")
                }
                
                session = TcpSession(srcPort, dstIp, dstPort).apply {
                    socketChannel = channel
                    clientSeqNum = tcpHeader.seqNum + 1
                    state = TcpState.SYN_RECEIVED
                }
                tcpSessions[srcPort] = session

                val bypassedSession = if (TerminalService.ignoredSessionIds.containsValue(true)) {
                    getSessionForLocalPort(srcPort, isTcp = true)
                } else null
                val isBypassed = bypassedSession != null && TerminalService.isSessionVpnIgnored(bypassedSession)

                val activeProxy = if (isBypassed) null else VpnProxyManager.getActiveProxy()

                if (activeProxy == null) {
                    // DIRECT CONNECTION: Non-blocking direct connect in selector loop
                    channel.configureBlocking(false)
                    if (isBypassed) {
                        Log.i(TAG, "Routing session $srcPort directly non-blocking (VPN bypass active)")
                        VpnLogManager.logConnection(vpnService, "TCP", "10.0.0.2", srcPort, dstIpStr, dstPort, 0, VpnLogManager.AuditCategory.ALLOWED, "Direct Connection (VPN Ignored)")
                    } else {
                        Log.i(TAG, "Routing session $srcPort directly non-blocking")
                    }
                    
                    channel.connect(InetSocketAddress(intToInetAddress(dstIp), dstPort))
                    selector?.let { sel ->
                        pendingRegistrations.offer(Runnable {
                            try {
                                channel.register(sel, SelectionKey.OP_CONNECT or SelectionKey.OP_READ, session)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to register TCP channel: ${e.message}")
                            }
                        })
                        sel.wakeup()
                    }
                } else {
                    // PROXY CONNECTION: Run on the shared thread pool
                    connectionThreadPool.submit {
                        try {
                            var connectedViaProxy = false
                            try {
                                Log.i(TAG, "Routing session $srcPort through proxy: ${activeProxy.country} (${activeProxy.ip}:${activeProxy.port})")
                                VpnLogManager.logConnection(vpnService, "TCP", "10.0.0.2", srcPort, dstIpStr, dstPort, 0, VpnLogManager.AuditCategory.ALLOWED, "Redirected via ${activeProxy.country} Proxy")

                                channel.socket().connect(InetSocketAddress(activeProxy.ip, activeProxy.port), 5000)
                                channel.configureBlocking(true)
                                channel.socket().soTimeout = 5000 // 5 seconds read timeout

                                // 1. Send SOCKS5 greeting
                                val out = channel.socket().getOutputStream()
                                val input = channel.socket().getInputStream()
                                out.write(byteArrayOf(0x05, 0x01, 0x00)) // Version 5, 1 auth method: No Auth
                                out.flush()

                                val response = ByteArray(2)
                                val read = input.read(response)
                                if (read < 2 || response[0].toInt() != 0x05 || response[1].toInt() != 0x00) {
                                    throw IOException("SOCKS5 Proxy rejected authentication method")
                                }

                                // 2. Request connection to target
                                val req = ByteArray(10)
                                req[0] = 0x05 // Version
                                req[1] = 0x01 // Command: CONNECT
                                req[2] = 0x00 // Reserved
                                req[3] = 0x01 // Address Type: IPv4

                                // Destination IP bytes
                                req[4] = ((dstIp shr 24) and 0xFF).toByte()
                                req[5] = ((dstIp shr 16) and 0xFF).toByte()
                                req[6] = ((dstIp shr 8) and 0xFF).toByte()
                                req[7] = (dstIp and 0xFF).toByte()

                                // Destination Port
                                req[8] = ((dstPort shr 8) and 0xFF).toByte()
                                req[9] = (dstPort and 0xFF).toByte()

                                out.write(req)
                                out.flush()

                                val rep = ByteArray(10)
                                val r = input.read(rep)
                                if (r < 10 || rep[0].toInt() != 0x05 || rep[1].toInt() != 0x00) {
                                    throw IOException("SOCKS5 Tunnel setup failed with error code: ${rep[1].toInt()}")
                                }

                                connectedViaProxy = true
                                Log.d(TAG, "Proxy connection established for session $srcPort")
                            } catch (e: Exception) {
                                Log.w(TAG, "Proxy failed for session $srcPort (${e.message}), falling back to direct")
                                VpnLogManager.logConnection(vpnService, "TCP", "10.0.0.2", srcPort, dstIpStr, dstPort, 0, VpnLogManager.AuditCategory.ALLOWED, "Proxy failed, falling back to direct")

                                channel.close()
                                val directChannel = SocketChannel.open()
                                // CRITICAL FIXED: Protect the fallback channel!
                                if (!vpnService.protect(directChannel.socket())) {
                                    Log.e(TAG, "protect() FAILED for direct fallback socket!")
                                }
                                session.socketChannel = directChannel
                            }

                            if (!connectedViaProxy) {
                                val activeChannel = session.socketChannel ?: channel
                                Log.i(TAG, "Routing session $srcPort directly (proxy fallback)")
                                VpnLogManager.logConnection(vpnService, "TCP", "10.0.0.2", srcPort, dstIpStr, dstPort, 0, VpnLogManager.AuditCategory.ALLOWED, "Direct Connection (Proxy Fallback)")
                                activeChannel.socket().connect(InetSocketAddress(intToInetAddress(dstIp), dstPort), 10000)
                            }

                            val activeChannel = session.socketChannel ?: channel
                            try { activeChannel.socket().soTimeout = 0 } catch (_: Exception) {}
                            activeChannel.configureBlocking(false)
                            selector?.let { sel ->
                                sel.wakeup()
                                activeChannel.register(sel, SelectionKey.OP_READ, session)
                            }

                            // Flush any data buffered while WAN connection was being established
                            while (true) {
                                val data = session.sendQueue.poll() ?: break
                                writeOrQueue(session, activeChannel, data)
                            }

                            // Trigger random session-based proxy rotation for the next connection if enabled
                            if (VpnProxyManager.isEnabled() && VpnProxyManager.getRotationMode() == 1) {
                                VpnProxyManager.triggerRandomRotation()
                            }

                            Log.d(TAG, "Connection completed for session $srcPort")
                        } catch (e: Exception) {
                            Log.e(TAG, "Handshake thread failed for session $srcPort: ${e.message ?: e.javaClass.simpleName}")
                            closeTcpSession(srcPort, sendRst = true)
                        }
                    }
                }

                // Send SYN-ACK back to local client immediately to complete Tun handshake
                sendTcpSynAck(session, tcpHeader)
                Log.d(TAG, "Started TCP handshake with client on port $srcPort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TCP handshake: ${e.message}")
                sendTcpRst(ipHeader, tcpHeader)
            }
            return
        }

        if (session == null) {
            // If packet is not SYN and session does not exist, reject with RST
            sendTcpRst(ipHeader, tcpHeader)
            return
        }

        session.lastActiveTime = System.currentTimeMillis()
        
        if (tcpHeader.isRST) {
            Log.d(TAG, "Received RST from client on port $srcPort")
            closeTcpSession(srcPort, sendRst = false) // Již jsme obdrželi RST, nemusíme posílat další
            return
        }

        if (tcpHeader.isFIN) {
            Log.d(TAG, "Received FIN from client on port $srcPort")
            session.clientSeqNum = tcpHeader.seqNum + 1
            sendTcpAck(session)
            // Přechod do stavu FIN_WAIT a čekání na potvrzení od serveru
            session.state = TcpState.FIN_WAIT
            closeTcpSession(srcPort, sendRst = false)
            return
        }

        if (tcpHeader.isACK) {
            if (session.state == TcpState.SYN_RECEIVED) {
                session.state = TcpState.ESTABLISHED
                Log.d(TAG, "TCP session established with client on port $srcPort")
            }

            session.serverSeqNum = Math.max(session.serverSeqNum, tcpHeader.ackNum)
            session.clientSeqNum = Math.max(session.clientSeqNum, tcpHeader.seqNum)

            val headerLen = ipHeader.ihl + tcpHeader.dataOffset
            val payloadLen = ipHeader.totalLength - headerLen
            
            if (payloadLen > 0) {
                session.clientSeqNum = tcpHeader.seqNum + payloadLen
                
                packetBuffer.position(headerLen)
                packetBuffer.limit(headerLen + payloadLen)
                
                val payloadCopy = ByteBuffer.allocate(payloadLen)
                payloadCopy.put(packetBuffer)
                payloadCopy.flip()

                if (session.isTlsMitm) {
                    val raw = ByteArray(payloadCopy.remaining())
                    payloadCopy.get(raw)
                    TlsMitmEngine.onClientData(vpnService, session, raw, writeToTun)
                    return
                }
                
                val rawPeek = ByteArray(payloadCopy.remaining())
                payloadCopy.get(rawPeek)
                payloadCopy.flip()

                val looksLikeTls = isMitmEnabled() && TlsClientHelloParser.isTlsClientHello(rawPeek)
                if (looksLikeTls) {
                    if (session.destinationPort in TLS_PORTS) {
                        session.isTlsMitm = true
                        TlsMitmEngine.onClientData(vpnService, session, rawPeek, writeToTun)
                        return
                    }
                    Log.w(TAG, "TLS ClientHello detected on non-whitelisted port ${session.destinationPort} for client ${session.clientPort}")
                }

                if (session.socketChannel?.isConnected == true) {
                    if (!writeOrQueue(session, session.socketChannel!!, payloadCopy)) {
                        return
                    }
                } else {
                    session.sendQueue.offer(payloadCopy)
                }
                
                sendTcpAck(session)
            }
        }
    }

    private fun startSelectorLoop() {
        selectorThread = Thread({
            val buffer = ByteBuffer.allocate(16384)
            // Sledování času pro pravidelné čištění
            var lastCleanup = System.currentTimeMillis()
            val cleanupInterval = 30_000L // 30 sekund
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                try {
                    // Process any pending channel registrations first
                    while (true) {
                        val task = pendingRegistrations.poll() ?: break
                        try {
                            task.run()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error executing pending registration: ${e.message}")
                        }
                    }

                    val count = selector?.select(2000) ?: 0
                    if (count == 0) {
                        // Pravidelné čištění neaktivních spojení každých 30 sekund
                        val now = System.currentTimeMillis()
                        if (now - lastCleanup > cleanupInterval) {
                            cleanIdleSessions()
                            lastCleanup = now
                        } else {
                            // Lehké čištění - pouze zastaralé spojení
                            cleanIdleSessions()
                        }
                        continue
                    }

                    val keys = selector?.selectedKeys() ?: continue
                    val iterator = keys.iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()

                        if (!key.isValid) continue

                        try {
                            if (key.isValid && key.isConnectable) {
                                handleConnectableKey(key)
                            }
                            if (key.isValid && key.isWritable) {
                                handleWritableKey(key)
                            }
                            if (key.isValid && key.isReadable) {
                                handleReadableKey(key, buffer)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling selector key: ${e.message}")
                            key.cancel()
                            try { key.channel().close() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Selector loop error: ${e.message}")
                    }
                }
            }
            Log.i(TAG, "Selector loop thread terminated")
        }, "VpnNatSelectorThread").apply { start() }
    }

    private fun handleConnectableKey(key: SelectionKey) {
        val channel = key.channel() as SocketChannel
        val session = key.attachment() as TcpSession
        
        try {
            if (channel.finishConnect()) {
                // Done connecting, default to reading
                key.interestOps(SelectionKey.OP_READ)
                
                // Try flushing any buffered outgoing payloads
                while (true) {
                    val data = session.sendQueue.peek() ?: break
                    val written = channel.write(data)
                    session.bytesSent += written
                    if (data.hasRemaining()) {
                        break
                    }
                    session.sendQueue.poll()
                }

                if (session.sendQueue.isNotEmpty()) {
                    key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
                }
                
                Log.d(TAG, "WAN connection established for port ${session.clientPort}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete WAN connection for port ${session.clientPort}: ${e.message}")
            closeTcpSession(session.clientPort, sendRst = true)
        }
    }

    private fun handleWritableKey(key: SelectionKey) {
        val channel = key.channel() as? SocketChannel ?: return
        val session = key.attachment() as? TcpSession ?: return
        
        try {
            while (true) {
                val data = session.sendQueue.peek() ?: break
                val written = channel.write(data)
                session.bytesSent += written
                if (data.hasRemaining()) {
                    break
                }
                session.sendQueue.poll()
            }
            if (session.sendQueue.isEmpty()) {
                key.interestOps(SelectionKey.OP_READ)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Socket write error on port ${session.clientPort}: ${e.message}")
            closeTcpSession(session.clientPort, sendRst = true)
        }
    }

    private fun writeOrQueue(session: TcpSession, channel: SocketChannel, data: ByteBuffer): Boolean {
        return try {
            if (session.sendQueue.isNotEmpty()) {
                session.sendQueue.offer(data)
                registerWriteInterest(channel)
                return true
            }
            
            val written = channel.write(data)
            session.bytesSent += written
            
            if (data.hasRemaining()) {
                session.sendQueue.offer(data)
                registerWriteInterest(channel)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Socket write error on port ${session.clientPort}: ${e.message}")
            closeTcpSession(session.clientPort, sendRst = true)
            false
        }
    }

    private fun registerWriteInterest(channel: SocketChannel) {
        try {
            val key = channel.keyFor(selector)
            if (key != null && key.isValid) {
                selector?.wakeup()
                key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register write interest: ${e.message}")
        }
    }

    private fun handleReadableKey(key: SelectionKey, buffer: ByteBuffer) {
        if (key.channel() is SocketChannel) {
            val channel = key.channel() as SocketChannel
            val session = key.attachment() as TcpSession
            buffer.clear()
            
            val read = try {
                channel.read(buffer)
            } catch (e: Exception) {
                Log.e(TAG, "Socket read error on port ${session.clientPort}: ${e.message}")
                -1
            }

            if (read == -1) {
                // Connection closed by remote WAN server -> send FIN to local client
                sendTcpFin(session)
                closeTcpSession(session.clientPort)
            } else if (read > 0) {
                session.bytesReceived += read
                buffer.flip()
                val payload = ByteArray(read)
                buffer.get(payload)
                
                // Wrap in TCP packet and write to TUN
                sendTcpDataToClient(session, payload)
            }
        } else if (key.channel() is DatagramChannel) {
            val channel = key.channel() as DatagramChannel
            val session = key.attachment() as UdpSession
            buffer.clear()
            
            val read = try {
                channel.read(buffer)
            } catch (e: Exception) {
                Log.e(TAG, "Datagram read error on port ${session.clientPort}: ${e.message}")
                -1
            }

            if (read > 0) {
                session.bytesReceived += read
                buffer.flip()
                val payload = ByteArray(read)
                buffer.get(payload)
                
                // Wrap in UDP packet and write to TUN
                sendUdpDataToClient(session, payload)
            }
        }
    }

    private fun sendTcpSynAck(session: TcpSession, clientTcp: TcpHeader) {
        val totalLength = 40 // IP (20) + TCP (20)
        val response = ByteBuffer.allocate(totalLength)
        
        val ip = IpHeader(response, 0)
        ip.version
        response.put(0, 0x45.toByte()) // IPv4, IHL = 5
        response.put(1, 0.toByte())    // TOS = 0
        ip.totalLength = totalLength
        response.putShort(4, 0)         // ID = 0
        response.putShort(6, 0)         // Flags/Offset = 0
        response.put(8, 64.toByte())   // TTL = 64
        response.put(9, 6.toByte())    // Protocol = TCP
        ip.sourceAddress = session.destinationAddress
        ip.destinationAddress = LOCAL_IP_INT
        ip.computeChecksum()

        val tcp = TcpHeader(response, 20)
        tcp.sourcePort = session.destinationPort
        tcp.destinationPort = session.clientPort
        tcp.seqNum = session.serverSeqNum
        tcp.ackNum = clientTcp.seqNum + 1
        response.put(20 + 12, 0x50.toByte()) // Data Offset = 5, Reserved = 0
        tcp.flags = 0x12                     // SYN | ACK
        response.putShort(20 + 14, 0xFFFF.toShort()) // Window size = 65535
        tcp.computeChecksum(ip)

        writeToTun(response.array(), totalLength)
        session.serverSeqNum++
    }

    private fun sendTcpAck(session: TcpSession) {
        val totalLength = 40 // IP (20) + TCP (20)
        val response = ByteBuffer.allocate(totalLength)
        
        val ip = IpHeader(response, 0)
        response.put(0, 0x45.toByte())
        ip.totalLength = totalLength
        response.put(8, 64.toByte())
        response.put(9, 6.toByte())
        ip.sourceAddress = session.destinationAddress
        ip.destinationAddress = LOCAL_IP_INT
        ip.computeChecksum()

        val tcp = TcpHeader(response, 20)
        tcp.sourcePort = session.destinationPort
        tcp.destinationPort = session.clientPort
        tcp.seqNum = session.serverSeqNum
        tcp.ackNum = session.clientSeqNum
        response.put(20 + 12, 0x50.toByte())
        tcp.flags = 0x10 // ACK
        response.putShort(20 + 14, 0xFFFF.toShort())
        tcp.computeChecksum(ip)

        writeToTun(response.array(), totalLength)
    }

    fun sendTcpDataToClient(session: TcpSession, data: ByteArray) {
        val totalLength = 40 + data.size
        val response = ByteBuffer.allocate(totalLength)
        
        val ip = IpHeader(response, 0)
        response.put(0, 0x45.toByte())
        ip.totalLength = totalLength
        response.put(8, 64.toByte())
        response.put(9, 6.toByte())
        ip.sourceAddress = session.destinationAddress
        ip.destinationAddress = LOCAL_IP_INT
        ip.computeChecksum()

        val tcp = TcpHeader(response, 20)
        tcp.sourcePort = session.destinationPort
        tcp.destinationPort = session.clientPort
        tcp.seqNum = session.serverSeqNum
        tcp.ackNum = session.clientSeqNum
        response.put(20 + 12, 0x50.toByte())
        tcp.flags = 0x18 // ACK | PSH
        response.putShort(20 + 14, 0xFFFF.toShort())
        
        // Put data payload
        response.position(40)
        response.put(data)
        
        tcp.computeChecksum(ip)

        writeToTun(response.array(), totalLength)
        session.serverSeqNum += data.size
    }

    private fun sendTcpFin(session: TcpSession) {
        val totalLength = 40
        val response = ByteBuffer.allocate(totalLength)
        
        val ip = IpHeader(response, 0)
        response.put(0, 0x45.toByte())
        ip.totalLength = totalLength
        response.put(8, 64.toByte())
        response.put(9, 6.toByte())
        ip.sourceAddress = session.destinationAddress
        ip.destinationAddress = LOCAL_IP_INT
        ip.computeChecksum()

        val tcp = TcpHeader(response, 20)
        tcp.sourcePort = session.destinationPort
        tcp.destinationPort = session.clientPort
        tcp.seqNum = session.serverSeqNum
        tcp.ackNum = session.clientSeqNum
        response.put(20 + 12, 0x50.toByte())
        tcp.flags = 0x11 // FIN | ACK
        response.putShort(20 + 14, 0xFFFF.toShort())
        tcp.computeChecksum(ip)

        writeToTun(response.array(), totalLength)
    }

    private fun sendTcpRst(clientIp: IpHeader, clientTcp: TcpHeader) {
        val totalLength = 40
        val response = ByteBuffer.allocate(totalLength)
        
        val ip = IpHeader(response, 0)
        response.put(0, 0x45.toByte())
        ip.totalLength = totalLength
        response.put(8, 64.toByte())
        response.put(9, 6.toByte())
        ip.sourceAddress = clientIp.destinationAddress
        ip.destinationAddress = clientIp.sourceAddress
        ip.computeChecksum()

        val tcp = TcpHeader(response, 20)
        tcp.sourcePort = clientTcp.destinationPort
        tcp.destinationPort = clientTcp.sourcePort
        tcp.seqNum = 0
        tcp.ackNum = clientTcp.seqNum + 1
        response.put(20 + 12, 0x50.toByte())
        tcp.flags = 0x14 // RST | ACK
        response.putShort(20 + 14, 0.toShort())
        tcp.computeChecksum(ip)

        writeToTun(response.array(), totalLength)
    }

    private fun sendUdpDataToClient(session: UdpSession, data: ByteArray) {
        val udpLen = 8 + data.size
        val totalLength = 20 + udpLen
        val response = ByteBuffer.allocate(totalLength)
        
        val ip = IpHeader(response, 0)
        response.put(0, 0x45.toByte())
        ip.totalLength = totalLength
        response.put(8, 64.toByte())
        response.put(9, 17.toByte()) // Protocol = UDP
        ip.sourceAddress = session.destinationAddress
        ip.destinationAddress = LOCAL_IP_INT
        ip.computeChecksum()

        val udp = UdpHeader(response, 20)
        udp.sourcePort = session.destinationPort
        udp.destinationPort = session.clientPort
        udp.length = udpLen
        
        // Put UDP payload
        response.position(28)
        response.put(data)
        
        udp.computeChecksum(ip)

        writeToTun(response.array(), totalLength)
    }

    private fun sendTcpRstForSession(session: TcpSession) {
        val totalLength = 40
        val response = ByteBuffer.allocate(totalLength)
        
        val ip = IpHeader(response, 0)
        response.put(0, 0x45.toByte())
        ip.totalLength = totalLength
        response.put(8, 64.toByte())
        response.put(9, 6.toByte())
        ip.sourceAddress = session.destinationAddress
        ip.destinationAddress = LOCAL_IP_INT
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
    }

    internal fun closeTcpSession(port: Int, sendRst: Boolean = false) {
        val session = tcpSessions.remove(port) ?: return
        val prevState = session.state
        
        // Uzavření TLS MITM relace, pokud existuje
        try {
            session.tlsMitmHandler?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TLS MITM session on port $port: ${e.message}")
        } finally {
            session.tlsMitmHandler = null
            session.isTlsMitm = false
        }
        
        // Odeslání RST packetu, pokud je požadováno a spojení není již uzavřeno
        if (sendRst && prevState != TcpState.CLOSED) {
            try {
                sendTcpRstForSession(session)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send TCP RST for port $port: ${e.message}")
            }
            session.state = TcpState.CLOSED
        }
        
        // Explicitní uzavření socketu s kontrolou
        try {
            session.socketChannel?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing socket channel for port $port: ${e.message}")
        } finally {
            session.socketChannel = null
        }
        
        Log.d(TAG, "Closed TCP session on port $port (state: $prevState -> CLOSED)")
    }

    private fun closeUdpSession(port: Int) {
        val session = udpSessions.remove(port) ?: return
        try {
            session.datagramChannel?.close()
        } catch (_: IOException) {}
        Log.d(TAG, "Closed UDP session on port $port")
    }

    private fun cleanIdleSessions() {
        val now = System.currentTimeMillis()
        
        // Idle TCP clean (reduced from 5 minutes to 2 minutes)
        val tcpIterator = tcpSessions.keys().iterator()
        var tcpClosed = 0
        while (tcpIterator.hasNext()) {
            val port = tcpIterator.next()
            val session = tcpSessions[port] ?: continue
            // Zkrácení timeoutu z 300000ms (5 min) na 120000ms (2 min)
            if (now - session.lastActiveTime > 120000) {
                closeTcpSessionWithRetry(port)
                tcpClosed++
            }
        }
        
        // Idle UDP clean (reduced from 1 minute to 30 seconds)
        val udpIterator = udpSessions.keys().iterator()
        var udpClosed = 0
        while (udpIterator.hasNext()) {
            val port = udpIterator.next()
            val session = udpSessions[port] ?: continue
            // Zkrácení timeoutu z 60000ms (1 min) na 30000ms (30 sekund)
            if (now - session.lastActiveTime > 30000) {
                closeUdpSession(port)
                udpClosed++
            }
        }
        
        if (tcpClosed > 0 || udpClosed > 0) {
            Log.d(TAG, "Cleaned idle sessions: TCP=$tcpClosed, UDP=$udpClosed")
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping NAT Engine - closing all connections...")
        isRunning.set(false)
        selectorThread?.interrupt()
        
        try {
            selector?.close()
        } catch (_: Exception) {}
        
        // Robustní uzavření všech TCP spojení s opakovanými pokusy
        val tcpPorts = tcpSessions.keys.toList()
        Log.i(TAG, "Closing ${tcpPorts.size} TCP sessions...")
        for (port in tcpPorts) {
            closeTcpSessionWithRetry(port)
        }
        
        // Robustní uzavření všech UDP spojení
        val udpPorts = udpSessions.keys.toList()
        Log.i(TAG, "Closing ${udpPorts.size} UDP sessions...")
        for (port in udpPorts) {
            closeUdpSession(port)
        }
        
        // Čekání na uzavření všech spojení
        var attempt = 0
        while ((tcpSessions.isNotEmpty() || udpSessions.isNotEmpty()) && attempt < 10) {
            Thread.sleep(100)
            attempt++
            Log.d(TAG, "Waiting for sessions to close... TCP: ${tcpSessions.size}, UDP: ${udpSessions.size}")
        }
        
        aiBrainWorker?.close()
        aiBrainWorker = null
        try { connectionThreadPool.shutdownNow() } catch (_: Exception) {}
        dnsCacheCleanerThread?.interrupt()
        dnsCacheCleanerThread = null
        dnsResponseCache.clear()
        Log.i(TAG, "NAT Engine successfully stopped. Remaining sessions - TCP: ${tcpSessions.size}, UDP: ${udpSessions.size}")
    }
    
    private fun closeTcpSessionWithRetry(port: Int, maxRetries: Int = 3) {
        var retries = 0
        while (retries < maxRetries) {
            try {
                closeTcpSession(port, sendRst = true)
                // Kontrola, zda bylo spojení skutečně uzavřeno
                if (!tcpSessions.containsKey(port)) {
                    return
                }
                retries++
                Thread.sleep(50)
            } catch (e: Exception) {
                Log.w(TAG, "Error closing TCP session on port $port (attempt ${retries + 1}): ${e.message}")
                retries++
            }
        }
        // Vynucené odstranění ze seznamu, pokud se nepodařilo uzavřít normálně
        tcpSessions.remove(port)
        Log.w(TAG, "Force removed TCP session on port $port after $maxRetries attempts")
    }

    private fun getParentPid(pid: Int): Int {
        try {
            val statFile = java.io.File("/proc/$pid/stat")
            if (!statFile.exists()) return -1
            val content = statFile.readText()
            val lastParen = content.lastIndexOf(')')
            if (lastParen != -1 && lastParen + 2 < content.length) {
                val afterParen = content.substring(lastParen + 2).trim()
                val fields = afterParen.split(" ")
                if (fields.size >= 2) {
                    return fields[1].toIntOrNull() ?: -1
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return -1
    }

    private fun getDescendantPids(parentPid: Int): Set<Int> {
        val descendants = HashSet<Int>()
        descendants.add(parentPid)
        try {
            val procDir = java.io.File("/proc")
            val pids = procDir.list { _, name -> name.all { it.isDigit() } }
            if (pids == null) return descendants
            val pidsList = pids.mapNotNull { it.toIntOrNull() }
            
            val parentMap = HashMap<Int, MutableList<Int>>()
            for (pid in pidsList) {
                val ppid = getParentPid(pid)
                if (ppid != -1) {
                    parentMap.getOrPut(ppid) { ArrayList() }.add(pid)
                }
            }
            
            val queue = java.util.ArrayDeque<Int>()
            queue.add(parentPid)
            while (!queue.isEmpty()) {
                val current = queue.poll() ?: break
                val children = parentMap[current]
                if (children != null) {
                    for (child in children) {
                        if (descendants.add(child)) {
                            queue.add(child)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return descendants
    }

    private fun getSocketInodeForLocalPort(localPort: Int, isTcp: Boolean): Long? {
        val files = if (isTcp) {
            listOf("/proc/self/net/tcp", "/proc/self/net/tcp6")
        } else {
            listOf("/proc/self/net/udp", "/proc/self/net/udp6")
        }
        
        val portHex = String.format("%04X", localPort)
        for (filePath in files) {
            try {
                val file = java.io.File(filePath)
                if (!file.exists()) continue
                val lines = file.readLines()
                for (line in lines) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 10) {
                        val localAddress = parts[1]
                        val inode = parts[9]
                        val localPortHex = localAddress.substringAfterLast(":")
                        if (localPortHex.equals(portHex, ignoreCase = true)) {
                            val inodeVal = inode.toLongOrNull()
                            if (inodeVal != null && inodeVal != 0L) {
                                return inodeVal
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return null
    }

    private fun getSessionForLocalPort(localPort: Int, isTcp: Boolean): TerminalSession? {
        val inode = getSocketInodeForLocalPort(localPort, isTcp) ?: return null
        val activeSessions = TerminalService.sessions
        for (session in activeSessions) {
            val shellPid = session.pid
            if (shellPid <= 0) continue
            val descendants = getDescendantPids(shellPid)
            for (pid in descendants) {
                val fdDir = java.io.File("/proc/$pid/fd")
                val fds = fdDir.list() ?: continue
                for (fd in fds) {
                    try {
                        val symlink = java.io.File(fdDir, fd)
                        val target = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            java.nio.file.Files.readSymbolicLink(symlink.toPath()).toString()
                        } else {
                            symlink.canonicalPath
                        }
                        if (target.contains("socket:[$inode]")) {
                            return session
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        }
        return null
    }

    private fun intToIp(ip: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            (ip shr 24) and 0xFF,
            (ip shr 16) and 0xFF,
            (ip shr 8) and 0xFF,
            ip and 0xFF
        )
    }

    private fun intToInetAddress(ip: Int): InetAddress {
        val buf = ByteBuffer.allocate(4).putInt(ip)
        return InetAddress.getByAddress(buf.array())
    }

    fun getActiveSockets(context: android.content.Context): List<ActiveSocket> {
        val now = System.currentTimeMillis()
        val list = ArrayList<ActiveSocket>()

        // Update speeds and gather TCP active sockets
        for (session in tcpSessions.values) {
            val delta = (now - session.lastSpeedCalcTime) / 1000.0f
            if (delta >= 0.5f) {
                session.speedUpload = ((session.bytesSent - session.lastBytesSent) / delta).toLong().coerceAtLeast(0)
                session.speedDownload = ((session.bytesReceived - session.lastBytesReceived) / delta).toLong().coerceAtLeast(0)
                session.lastBytesSent = session.bytesSent
                session.lastBytesReceived = session.bytesReceived
                session.lastSpeedCalcTime = now
            }
            
            val dstIpStr = intToIp(session.destinationAddress)
            val resolved = ProcessResolver.resolve(context, "TCP", "10.0.0.2", session.clientPort, dstIpStr, session.destinationPort)
            val flag = IpInfoResolver.getCached(dstIpStr)?.flagEmoji ?: "🌐"

            list.add(
                ActiveSocket(
                    protocol = "TCP",
                    srcIp = "10.0.0.2",
                    srcPort = session.clientPort,
                    dstIp = dstIpStr,
                    dstPort = session.destinationPort,
                    state = session.state.name,
                    bytesSent = session.bytesSent,
                    bytesReceived = session.bytesReceived,
                    speedUpload = session.speedUpload,
                    speedDownload = session.speedDownload,
                    appName = resolved.appName,
                    packageName = resolved.packageName,
                    flagEmoji = flag,
                    isTlsMitm = session.isTlsMitm,
                    sni = session.tlsMitmHandler?.sni
                )
            )
        }

        // Update speeds and gather UDP active sockets
        for (session in udpSessions.values) {
            val delta = (now - session.lastSpeedCalcTime) / 1000.0f
            if (delta >= 0.5f) {
                session.speedUpload = ((session.bytesSent - session.lastBytesSent) / delta).toLong().coerceAtLeast(0)
                session.speedDownload = ((session.bytesReceived - session.lastBytesReceived) / delta).toLong().coerceAtLeast(0)
                session.lastBytesSent = session.bytesSent
                session.lastBytesReceived = session.bytesReceived
                session.lastSpeedCalcTime = now
            }

            val dstIpStr = intToIp(session.destinationAddress)
            val resolved = ProcessResolver.resolve(context, "UDP", "10.0.0.2", session.clientPort, dstIpStr, session.destinationPort)
            val flag = IpInfoResolver.getCached(dstIpStr)?.flagEmoji ?: "🌐"

            list.add(
                ActiveSocket(
                    protocol = "UDP",
                    srcIp = "10.0.0.2",
                    srcPort = session.clientPort,
                    dstIp = dstIpStr,
                    dstPort = session.destinationPort,
                    state = "ESTABLISHED",
                    bytesSent = session.bytesSent,
                    bytesReceived = session.bytesReceived,
                    speedUpload = session.speedUpload,
                    speedDownload = session.speedDownload,
                    appName = resolved.appName,
                    packageName = resolved.packageName,
                    flagEmoji = flag
                )
            )
        }

        return list
    }

    // ---- DNS Block Cache ----

    /**
     * Cache pro předgenerované IP+UDP hlavičky NXDOMAIN odpovědí.
     * Klíč je (dstIp, srcIp, dstPort, srcPort) — tedy IP adresy a porty
     * prohozené oproti původnímu dotazu.
     */
    private data class DnsHeaderCacheKey(
        val dstIp: Int,  // původní destination (teď source v odpovědi)
        val srcIp: Int,  // původní source (teď destination v odpovědi)
        val dstPort: Int,
        val srcPort: Int,
        val payloadLen: Int  // délka DNS payloadu — ovlivňuje IP Total Length a UDP Length
    )

    private data class CachedDnsResponse(
        val headerBytes: ByteArray,  // předgenerovaná IP+UDP hlavička (20+8=28 bajtů)
        val expiresAt: Long
    )

    private val dnsResponseCache = ConcurrentHashMap<DnsHeaderCacheKey, CachedDnsResponse>()
    private var dnsCacheCleanerThread: Thread? = null

    /**
     * Předgeneruje IP+UDP hlavičku pro NXDOMAIN odpověď.
     * Hlavička je konstantní pro danou čtveřici (sourceIp, destIp, sourcePort, destPort).
     */
    private fun buildDnsResponseHeader(
        dstIp: Int,
        srcIp: Int,
        dstPort: Int,
        srcPort: Int,
        udpPayloadLen: Int
    ): ByteArray {
        val udpLen = 8 + udpPayloadLen
        val ipLen = 20 + udpLen
        val buf = ByteBuffer.allocate(28)

        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(ipLen.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(17.toByte())
        buf.putShort(0.toShort())
        buf.putInt(0)
        buf.putInt(0)
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())

        val ip = IpHeader(buf, 0)
        ip.sourceAddress = dstIp
        ip.destinationAddress = srcIp
        ip.computeChecksum()

        val udp = UdpHeader(buf, 20)
        udp.sourcePort = dstPort
        udp.destinationPort = srcPort
        udp.length = udpLen

        return buf.array()
    }

    private fun parseDnsQuery(payload: ByteArray): Pair<String, String>? {
        if (payload.size < 12) return null
        var pos = 12
        val domain = java.lang.StringBuilder()
        try {
            while (pos < payload.size) {
                val len = payload[pos].toInt() and 0xFF
                if (len == 0) {
                    pos++
                    break
                }
                if (pos + 1 + len > payload.size) return null
                if (domain.isNotEmpty()) domain.append(".")
                domain.append(String(payload, pos + 1, len, Charsets.US_ASCII))
                pos += 1 + len
            }
            if (pos + 4 <= payload.size) {
                val qType = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
                val qTypeStr = when(qType) {
                    1 -> "A"
                    28 -> "AAAA"
                    5 -> "CNAME"
                    15 -> "MX"
                    16 -> "TXT"
                    2 -> "NS"
                    6 -> "SOA"
                    12 -> "PTR"
                    else -> "TYPE_$qType"
                }
                return Pair(domain.toString(), qTypeStr)
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    private fun sendDnsNxDomainResponse(
        packetBuffer: ByteBuffer,
        ipHeader: IpHeader,
        udpHeader: UdpHeader,
        payloadLen: Int
    ) {
        if (payloadLen < 12) return

        // Původní pozice v bufferu pro obnovení
        val originalPos = packetBuffer.position()

        // Vytvoříme DNS odpověď přímo z DNS payloadu — modifikace in-place,
        // pouze nastavíme NXDOMAIN flagy
        val dnsResponse = ByteArray(payloadLen)
        packetBuffer.position(ipHeader.ihl + 8)
        packetBuffer.get(dnsResponse)
        packetBuffer.position(originalPos)

        // Nastav NXDOMAIN response flags (QR=1, Opcode=0, AA=0, TC=0, RD=1, RA=1, RCODE=3)
        dnsResponse[2] = 0x81.toByte()  // QR | RD
        dnsResponse[3] = 0x83.toByte()  // RA | NXDOMAIN

        // Klíč cache: prohozené IP adresy a porty
        val cacheKey = DnsHeaderCacheKey(
            dstIp = ipHeader.destinationAddress,
            srcIp = ipHeader.sourceAddress,
            dstPort = udpHeader.destinationPort,
            srcPort = udpHeader.sourcePort,
            payloadLen = dnsResponse.size
        )

        val cached = dnsResponseCache[cacheKey]
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            // Cache hit — použijeme předgenerovanou hlavičku
            val responseBytes = ByteArray(cached.headerBytes.size + dnsResponse.size)
            System.arraycopy(cached.headerBytes, 0, responseBytes, 0, cached.headerBytes.size)
            System.arraycopy(dnsResponse, 0, responseBytes, cached.headerBytes.size, dnsResponse.size)
            writeToTun(responseBytes, responseBytes.size)
        } else {
            // Cache miss — postavíme hlavičku a uložíme do cache
            val header = buildDnsResponseHeader(
                dstIp = ipHeader.destinationAddress,
                srcIp = ipHeader.sourceAddress,
                dstPort = udpHeader.destinationPort,
                srcPort = udpHeader.sourcePort,
                udpPayloadLen = dnsResponse.size
            )

            val responseBytes = ByteArray(header.size + dnsResponse.size)
            System.arraycopy(header, 0, responseBytes, 0, header.size)
            System.arraycopy(dnsResponse, 0, responseBytes, header.size, dnsResponse.size)

            // Ulož header do cache s TTL 30s
            dnsResponseCache[cacheKey] = CachedDnsResponse(
                headerBytes = header,
                expiresAt = System.currentTimeMillis() + 30_000
            )

            writeToTun(responseBytes, responseBytes.size)
        }
    }

}
