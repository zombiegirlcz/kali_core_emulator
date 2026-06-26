package com.linux_core.core

import android.net.VpnService
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

class VpnNatEngine(
    private val vpnService: VpnService,
    private val writeToTun: (ByteArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "VpnNatEngine"
        private const val LOCAL_IP_INT = 0x0A000002 // 10.0.0.2
    }

    private val isRunning = AtomicBoolean(false)
    private var selector: Selector? = null
    private var selectorThread: Thread? = null
    private var aiBrain: AIBrain? = null
    private val historyStore = TrafficHistoryStore(vpnService)
    private val lastPacketTimes = ConcurrentHashMap<String, Long>()

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
        val sendQueue = ArrayList<ByteBuffer>()
        var lastActiveTime = System.currentTimeMillis()
        
        var bytesSent: Long = 0
        var bytesReceived: Long = 0
        var lastSpeedCalcTime: Long = System.currentTimeMillis()
        var lastBytesSent: Long = 0
        var lastBytesReceived: Long = 0
        var speedUpload: Long = 0L
        var speedDownload: Long = 0L
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
            aiBrain = AIBrain(vpnService)
            isRunning.set(true)
            startSelectorLoop()
            Log.i(TAG, "NAT Engine successfully initialized with AI Brain")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NAT Engine: ${e.message}", e)
        }
    }

    private fun calculateEntropy(data: ByteArray): Float {
        if (data.isEmpty()) return 0.0f
        val counts = IntArray(256)
        for (b in data) {
            counts[b.toInt() and 0xFF]++
        }
        var entropy = 0.0
        for (count in counts) {
            if (count > 0) {
                val p = count.toDouble() / data.size
                entropy -= p * (Math.log(p) / Math.log(2.0))
            }
        }
        return entropy.toFloat()
    }

    // Pomocné mapy pro stavovou analýzu
    private val sessionByteCounts = mutableMapOf<String, Long>()
    private val sessionPacketCounts = mutableMapOf<String, Int>()

    private fun runInference(
        protocol: Int,
        srcPort: Int,
        dstIpStr: String,
        dstPort: Int,
        payload: ByteArray?,
        totalSize: Int
    ): VpnLogManager.AuditCategory {
        val brain = aiBrain ?: return VpnLogManager.AuditCategory.ALLOWED
        
        val sessionKey = "$protocol:$srcPort:$dstPort"
        val now = System.currentTimeMillis()
        val lastTime = lastPacketTimes[sessionKey] ?: now
        val delta = (now - lastTime) / 1000.0f
        lastPacketTimes[sessionKey] = now

        // --- STAVOVÁ ANALÝZA ---
        val cumulativeBytes = (sessionByteCounts[sessionKey] ?: 0L) + totalSize
        val packetCount = (sessionPacketCounts[sessionKey] ?: 0) + 1
        sessionByteCounts[sessionKey] = cumulativeBytes
        sessionPacketCounts[sessionKey] = packetCount

        val currentEntropy = payload?.let { calculateEntropy(it) } ?: 0.0f

        val features = FloatArray(18)
        features[0] = totalSize.toFloat()
        features[1] = protocol.toFloat()
        features[2] = delta
        features[3] = srcPort.toFloat()
        features[4] = dstPort.toFloat()
        features[5] = currentEntropy
        
        // b0-b7
        if (payload != null) {
            val limit = minOf(payload.size, 8)
            for (i in 0 until limit) {
                features[6 + i] = (payload[i].toInt() and 0xFF).toFloat()
            }
        }

        features[14] = (cumulativeBytes / 1024.0).toFloat()
        features[15] = packetCount.toFloat()
        features[16] = if (totalSize < 100 && packetCount > 50) 1.0f else 0.0f
        features[17] = (payload?.size ?: 0).toFloat()

        // 1. GLOBÁLNÍ ZNALOST (ONNX Model)
        val strategyIndex = brain.classify(features)
        
        // 2. OSOBNÍ PAMĚŤ (Behavioral Profile)
        val anomalyScore = UserProfileStore.getAnomalyScore(protocol, dstPort, currentEntropy, totalSize)

        // LOGIKA SAMOUČENÍ: Pokud globální model říká, že je to SAFE, učíme se to jako TVŮJ zvyk.
        if (strategyIndex == 0 && anomalyScore < 0.3f) {
            UserProfileStore.learnNormalPattern(protocol, dstPort, currentEntropy, totalSize)
        }

        // Pokud globální model váhá, ale OSOBNÍ PAMĚŤ vidí anomálii
        val finalDecision = if (strategyIndex == 0 && anomalyScore > 0.8f && packetCount > 10) {
            Log.w(TAG, "🧠 PERSONAL MEMORY ALERT: Unusual behavior for this user at port $dstPort")
            4 // Automaticky COUNTER pro neznámé chování
        } else strategyIndex

        // Zápis do analytické historie (pro 24h report) — async, outside packet path
        Thread {
            historyStore.logSession("App_Session", dstIpStr, dstPort, null, totalSize.toLong(), currentEntropy, finalDecision)
        }.start()

        if (finalDecision > 0) {
            val strategy = when(finalDecision) {
                1 -> OffensiveEngine.AttackStrategy.RECON
                2 -> OffensiveEngine.AttackStrategy.EXPLOIT
                3 -> OffensiveEngine.AttackStrategy.SPOOF
                4 -> OffensiveEngine.AttackStrategy.COUNTER
                else -> OffensiveEngine.AttackStrategy.RETREAT
            }
            
            if (finalDecision < 4 || packetCount > 5) {
                OffensiveEngine.execute(strategy, dstIpStr, dstPort)
            }
            saveTrainingSample(features, finalDecision)
        }

        return when (finalDecision) {
            1, 2, 3, 4 -> VpnLogManager.AuditCategory.CRITICAL
            else -> VpnLogManager.AuditCategory.ALLOWED
        }
    }

    private fun saveTrainingSample(features: FloatArray, label: Int) {
        Thread {
            try {
                val logFile = java.io.File(vpnService.filesDir, "offensive_learning_data.csv")
                val exists = logFile.exists()
                val writer = java.io.FileWriter(logFile, true)
                if (!exists) {
                    writer.write("size,proto,delta,src,dst,entropy,b0,b1,b2,b3,b4,b5,b6,b7,mss,nop,flood,p_len,label\n")
                }
                val line = features.joinToString(",") + ",$label\n"
                writer.write(line)
                writer.close()
            } catch (e: Exception) {
                Log.e(TAG, "Training data collection failed: ${e.message}")
            }
        }.start()
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
            // Extraction of payload for AI brain
            packetBuffer.position(payloadOffset)
            val payloadForAi = ByteArray(minOf(payloadLen, 64))
            packetBuffer.get(payloadForAi)
            packetBuffer.position(0) // Reset position for subsequent use

            // AI Inference
            val aiCategory = runInference(17, srcPort, dstIpStr, dstPort, payloadForAi, ipHeader.totalLength)
            val detail = if (aiCategory == VpnLogManager.AuditCategory.CRITICAL) {
                "AI: Detected critical network anomaly!"
            } else "AI: Verified UDP stream"

            VpnLogManager.logConnection(vpnService, "UDP", "10.0.0.2", srcPort, dstIpStr, dstPort, payloadLen, aiCategory, detail)

            try {
                val channel = DatagramChannel.open().apply {
                    configureBlocking(false)
                }
                
                // CRITICAL: Protect channel socket from loopback routing
                vpnService.protect(channel.socket())
                
                val remoteAddr = intToInetAddress(dstIp)
                channel.connect(InetSocketAddress(remoteAddr, dstPort))
                
                session = UdpSession(srcPort, dstIp, dstPort).apply {
                    datagramChannel = channel
                }
                udpSessions[srcPort] = session

                // Register with Selector
                selector?.let { sel ->
                    channel.register(sel, SelectionKey.OP_READ, session)
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
            
            // AI Inference for TCP SYN (usually no payload yet, but we check headers)
            val aiCategory = runInference(6, srcPort, dstIpStr, dstPort, null, ipHeader.totalLength)
            val detail = if (aiCategory == VpnLogManager.AuditCategory.CRITICAL) {
                "AI: Detected high-risk TCP request!"
            } else "AI: Verified TCP connection"

            VpnLogManager.logConnection(vpnService, "TCP", "10.0.0.2", srcPort, dstIpStr, dstPort, 40, aiCategory, detail)

            try {
                val channel = SocketChannel.open()
                
                // CRITICAL: Protect channel socket from loopback routing
                vpnService.protect(channel.socket())
                
                session = TcpSession(srcPort, dstIp, dstPort).apply {
                    socketChannel = channel
                    clientSeqNum = tcpHeader.seqNum + 1
                    state = TcpState.SYN_RECEIVED
                }
                tcpSessions[srcPort] = session

                // Asynchronous handshaker thread to connect and do proxy negotiation without blocking Selector loop
                Thread {
                    try {
                        val bypassedSession = if (TerminalService.ignoredSessionIds.containsValue(true)) {
                            getSessionForLocalPort(srcPort, isTcp = true)
                        } else null
                        val isBypassed = bypassedSession != null && TerminalService.isSessionVpnIgnored(bypassedSession)
                        
                        val activeProxy = if (isBypassed) null else VpnProxyManager.getActiveProxy()
                        if (activeProxy != null) {
                            Log.i(TAG, "Routing session $srcPort through proxy: ${activeProxy.country} (${activeProxy.ip}:${activeProxy.port})")
                            VpnLogManager.logConnection(vpnService, "TCP", "10.0.0.2", srcPort, dstIpStr, dstPort, 0, VpnLogManager.AuditCategory.ALLOWED, "Redirected via ${activeProxy.country} Proxy")
                            
                            channel.connect(InetSocketAddress(activeProxy.ip, activeProxy.port))
                            channel.configureBlocking(true)
                            
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
                        } else {
                            if (isBypassed) {
                                Log.i(TAG, "Routing session $srcPort directly (VPN bypass active)")
                                VpnLogManager.logConnection(vpnService, "TCP", "10.0.0.2", srcPort, dstIpStr, dstPort, 0, VpnLogManager.AuditCategory.ALLOWED, "Direct Connection (VPN Ignored)")
                            }
                            channel.connect(InetSocketAddress(intToInetAddress(dstIp), dstPort))
                        }
                        
                        channel.configureBlocking(false)
                        selector?.let { sel ->
                            channel.register(sel, SelectionKey.OP_READ, session)
                        }
                        
                        // Trigger random session-based proxy rotation for the next connection if enabled
                        if (VpnProxyManager.isEnabled() && VpnProxyManager.getRotationMode() == 1) {
                            VpnProxyManager.triggerRandomRotation()
                        }
                        
                        Log.d(TAG, "Connection completed for session $srcPort")
                    } catch (e: Exception) {
                        Log.e(TAG, "Handshake thread failed for session $srcPort: ${e.message}")
                        closeTcpSession(srcPort, sendRst = true)
                    }
                }.start()

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
            closeTcpSession(srcPort)
            return
        }

        if (tcpHeader.isFIN) {
            Log.d(TAG, "Received FIN from client on port $srcPort")
            session.clientSeqNum = tcpHeader.seqNum + 1
            sendTcpAck(session)
            closeTcpSession(srcPort)
            return
        }

        if (tcpHeader.isACK) {
            if (session.state == TcpState.SYN_RECEIVED) {
                session.state = TcpState.ESTABLISHED
                Log.d(TAG, "TCP session established with client on port $srcPort")
            }

            // Sync sequence and ACK numbers immediately before payload processing
            session.serverSeqNum = Math.max(session.serverSeqNum, tcpHeader.ackNum)
            session.clientSeqNum = Math.max(session.clientSeqNum, tcpHeader.seqNum)

            // Extract and forward payload data
            val headerLen = ipHeader.ihl + tcpHeader.dataOffset
            val payloadLen = ipHeader.totalLength - headerLen
            
            if (payloadLen > 0) {
                session.clientSeqNum = tcpHeader.seqNum + payloadLen
                
                packetBuffer.position(headerLen)
                packetBuffer.limit(headerLen + payloadLen)
                
                val payloadCopy = ByteBuffer.allocate(payloadLen)
                payloadCopy.put(packetBuffer)
                payloadCopy.flip()

                if (session.socketChannel?.isConnected == true) {
                    try {
                        session.socketChannel?.write(payloadCopy)
                        session.bytesSent += payloadLen
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to write TCP data to WAN: ${e.message}")
                        closeTcpSession(srcPort, sendRst = true)
                        return
                    }
                } else {
                    // Buffer data if remote channel is still connecting
                    session.sendQueue.add(payloadCopy)
                }
                
                // Acknowledge payload immediately back to TUN client
                sendTcpAck(session)
            }
        }
    }

    private fun startSelectorLoop() {
        selectorThread = Thread({
            val buffer = ByteBuffer.allocate(16384)
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val count = selector?.select(2000) ?: 0
                    if (count == 0) {
                        cleanIdleSessions()
                        continue
                    }

                    val keys = selector?.selectedKeys() ?: continue
                    val iterator = keys.iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()

                        if (!key.isValid) continue

                        try {
                            if (key.isConnectable) {
                                handleConnectableKey(key)
                            }
                            if (key.isReadable) {
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
                key.interestOps(SelectionKey.OP_READ)
                
                // Flush any buffered outgoing payloads
                while (session.sendQueue.isNotEmpty()) {
                    val data = session.sendQueue.removeAt(0)
                    val len = data.remaining()
                    channel.write(data)
                    session.bytesSent += len
                }
                Log.d(TAG, "WAN connection established for port ${session.clientPort}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete WAN connection for port ${session.clientPort}: ${e.message}")
            closeTcpSession(session.clientPort, sendRst = true)
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

    private fun sendTcpDataToClient(session: TcpSession, data: ByteArray) {
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

    private fun closeTcpSession(port: Int, sendRst: Boolean = false) {
        val session = tcpSessions.remove(port) ?: return
        if (sendRst && session.state != TcpState.CLOSED) {
            try {
                sendTcpRstForSession(session)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send TCP RST: ${e.message}")
            }
            session.state = TcpState.CLOSED
        }
        try {
            session.socketChannel?.close()
        } catch (_: IOException) {}
        Log.d(TAG, "Closed TCP session on port $port")
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
        
        // Idle TCP clean (e.g. 5 minutes)
        val tcpIterator = tcpSessions.keys().iterator()
        while (tcpIterator.hasNext()) {
            val port = tcpIterator.next()
            val session = tcpSessions[port] ?: continue
            if (now - session.lastActiveTime > 300000) {
                closeTcpSession(port, sendRst = true)
            }
        }
        
        // Idle UDP clean (e.g. 1 minute)
        val udpIterator = udpSessions.keys().iterator()
        while (udpIterator.hasNext()) {
            val port = udpIterator.next()
            val session = udpSessions[port] ?: continue
            if (now - session.lastActiveTime > 60000) {
                closeUdpSession(port)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        selectorThread?.interrupt()
        
        try {
            selector?.close()
        } catch (_: Exception) {}
        
        for (port in tcpSessions.keys) {
            closeTcpSession(port)
        }
        for (port in udpSessions.keys) {
            closeUdpSession(port)
        }
        Log.i(TAG, "NAT Engine successfully stopped")
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
                    flagEmoji = flag
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
        val originalPos = packetBuffer.position()
        val dnsPayload = ByteArray(payloadLen)
        packetBuffer.position(ipHeader.ihl + 8)
        packetBuffer.get(dnsPayload)
        packetBuffer.position(originalPos)
        
        if (dnsPayload.size < 12) return
        
        val dnsResponse = ByteArray(dnsPayload.size)
        System.arraycopy(dnsPayload, 0, dnsResponse, 0, dnsPayload.size)
        
        dnsResponse[2] = 0x81.toByte()
        dnsResponse[3] = 0x83.toByte()
        
        val ipLen = ipHeader.ihl + 8 + dnsResponse.size
        val responseBytes = ByteArray(ipLen)
        val respBuffer = ByteBuffer.wrap(responseBytes)
        
        respBuffer.put(0x45.toByte())
        respBuffer.put(0x00.toByte())
        respBuffer.putShort(ipLen.toShort())
        respBuffer.putShort(0.toShort())
        respBuffer.putShort(0x4000.toShort())
        respBuffer.put(64.toByte())
        respBuffer.put(17.toByte())
        respBuffer.putShort(0.toShort())
        
        respBuffer.putInt(ipHeader.destinationAddress)
        respBuffer.putInt(ipHeader.sourceAddress)
        
        respBuffer.putShort(udpHeader.destinationPort.toShort())
        respBuffer.putShort(udpHeader.sourcePort.toShort())
        respBuffer.putShort((8 + dnsResponse.size).toShort())
        respBuffer.putShort(0.toShort())
        
        respBuffer.put(dnsResponse)
        
        var sum = 0
        respBuffer.position(0)
        for (i in 0 until 10) {
            sum += respBuffer.getShort().toInt() and 0xFFFF
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val ipChecksum = (sum.inv() and 0xFFFF).toShort()
        respBuffer.putShort(10, ipChecksum)
        
        writeToTun(responseBytes, responseBytes.size)
    }
}
