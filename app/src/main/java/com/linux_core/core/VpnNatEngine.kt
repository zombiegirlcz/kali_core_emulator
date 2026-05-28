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

class VpnNatEngine(
    private val vpnService: VpnService,
    private val writeToTun: (ByteArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "VpnNatEngine"
        private const val LOCAL_IP_INT = 0x0A080002 // 10.8.0.2
    }

    private val isRunning = AtomicBoolean(false)
    private var selector: Selector? = null
    private var selectorThread: Thread? = null

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
    }

    class UdpSession(
        val clientPort: Int,
        val destinationAddress: Int,
        val destinationPort: Int
    ) {
        var datagramChannel: DatagramChannel? = null
        var lastActiveTime = System.currentTimeMillis()
    }

    init {
        try {
            selector = Selector.open()
            isRunning.set(true)
            startSelectorLoop()
            Log.i(TAG, "NAT Engine successfully initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Selector: ${e.message}", e)
        }
    }

    fun handlePacketFromTun(packetBuffer: ByteBuffer, length: Int) {
        if (!isRunning.get() || length < 20) return

        val ipHeader = IpHeader(packetBuffer, 0)
        if (ipHeader.version != 4) return // Only support IPv4 in this userspace stack

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
        
        val payloadOffset = ipHeader.ihl + 8
        val payloadLen = udpHeader.length - 8
        if (payloadLen <= 0) return

        var session = udpSessions[srcPort]
        if (session == null) {
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
                Log.d(TAG, "Created UDP session for port $srcPort to ${intToIp(dstIp)}:$dstPort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to establish UDP connection: ${e.message}")
                return
            }
        }

        session.lastActiveTime = System.currentTimeMillis()
        try {
            packetBuffer.position(payloadOffset)
            packetBuffer.limit(payloadOffset + payloadLen)
            session.datagramChannel?.write(packetBuffer)
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
        
        var session = tcpSessions[srcPort]

        if (tcpHeader.isSYN) {
            // New TCP connection request
            if (session != null) {
                closeTcpSession(srcPort)
            }
            
            try {
                val channel = SocketChannel.open().apply {
                    configureBlocking(false)
                }
                
                // CRITICAL: Protect channel socket from loopback routing
                vpnService.protect(channel.socket())
                
                val remoteAddr = intToInetAddress(dstIp)
                channel.connect(InetSocketAddress(remoteAddr, dstPort))
                
                session = TcpSession(srcPort, dstIp, dstPort).apply {
                    socketChannel = channel
                    clientSeqNum = tcpHeader.seqNum + 1
                    state = TcpState.SYN_RECEIVED
                }
                tcpSessions[srcPort] = session
                
                // Register with Selector for connect/read
                selector?.let { sel ->
                    channel.register(sel, SelectionKey.OP_CONNECT, session)
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
                    channel.write(data)
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
}
