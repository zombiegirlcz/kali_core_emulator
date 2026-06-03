package com.linux_core.core

import android.util.Base64
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object VpnPeerManager {
    private const val TAG = "VpnPeerManager"
    private const val PEER_PORT = 13337

    data class PeerNode(
        val peerId: Int, // 1 to 254, maps to IP 10.9.0.peerId
        val nodeName: String,
        val publicKeyBase64: String,
        var wanIp: String,
        var wanPort: Int,
        var lastSeenMs: Long = 0,
        var pingMs: Int = -1,
        var isConnecting: Boolean = false
    ) {
        val sharedSecretKey: SecretKey by lazy {
            deriveSharedKey(publicKeyBase64)
        }
    }

    private val isP2PEnabled = AtomicBoolean(false)
    private var localPeerId = 1
    private var nodeName = "NetHunter-Node"

    val peers = ConcurrentHashMap<Int, PeerNode>()

    private val keyPair: KeyPair by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        kpg.generateKeyPair()
    }

    private var udpSocket: DatagramSocket? = null
    private var listenThread: Thread? = null
    private var keepAliveThread: Thread? = null

    @Volatile
    private var localWanAddress: InetSocketAddress? = null

    private var writeToTunCallback: ((ByteArray) -> Unit)? = null

    fun isEnabled(): Boolean = isP2PEnabled.get()

    fun setEnabled(enabled: Boolean) {
        isP2PEnabled.set(enabled)
        if (enabled) {
            startP2P()
        } else {
            stopP2P()
        }
    }

    fun getLocalPeerId(): Int = localPeerId
    fun setLocalPeerId(id: Int) {
        localPeerId = id.coerceIn(1, 254)
    }

    fun getNodeName(): String = nodeName
    fun setNodeName(name: String) {
        nodeName = name.ifBlank { "NetHunter-Node" }
    }

    fun getPublicKeyEncoded(): String {
        return Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
    }

    fun getLocalWanAddress(): String {
        val addr = localWanAddress ?: return "resolving WAN..."
        return "${addr.hostString}:${addr.port}"
    }

    fun getLocalConnectionString(): String {
        return "$localPeerId|$nodeName|${getPublicKeyEncoded()}|${localWanAddress?.hostString ?: "0.0.0.0"}|${localWanAddress?.port ?: PEER_PORT}"
    }

    fun addPeerFromConnectionString(connStr: String): Boolean {
        try {
            val parts = connStr.trim().split("|")
            if (parts.size < 5) return false
            val id = parts[0].toIntOrNull() ?: return false
            val name = parts[1]
            val pubKey = parts[2]
            val ip = parts[3]
            val port = parts[4].toIntOrNull() ?: PEER_PORT
            
            val node = PeerNode(
                peerId = id,
                nodeName = name,
                publicKeyBase64 = pubKey,
                wanIp = ip,
                wanPort = port
            )
            peers[id] = node
            Log.i(TAG, "Added peer: $name (10.9.0.$id) at $ip:$port")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse peer connection string: ${e.message}")
        }
        return false
    }

    fun removePeer(peerId: Int) {
        peers.remove(peerId)
    }

    fun initCallbacks(tunWrite: (ByteArray) -> Unit) {
        writeToTunCallback = tunWrite
    }

    private fun deriveSharedKey(peerPublicKeyBase64: String): SecretKey {
        val keyBytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
        val kf = KeyFactory.getInstance("EC")
        val pubKeySpec = X509EncodedKeySpec(keyBytes)
        val peerPubKey = kf.generatePublic(pubKeySpec)
        
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keyPair.private)
        ka.doPhase(peerPubKey, true)
        val sharedSecret = ka.generateSecret()
        
        val md = MessageDigest.getInstance("SHA-256")
        val aesKeyBytes = md.digest(sharedSecret)
        return SecretKeySpec(aesKeyBytes, 0, 16, "AES") // AES-128
    }

    private fun encrypt(data: ByteArray, secretKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val ciphertext = cipher.doFinal(data)
        
        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
        return result
    }

    private fun decrypt(encryptedData: ByteArray, secretKey: SecretKey): ByteArray {
        if (encryptedData.size < 12) throw IllegalArgumentException("Data too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        System.arraycopy(encryptedData, 0, iv, 0, 12)
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        return cipher.doFinal(encryptedData, 12, encryptedData.size - 12)
    }

    fun sendPacketToPeer(peerId: Int, packetBytes: ByteArray) {
        if (!isEnabled()) return
        val peer = peers[peerId] ?: return
        if (peer.wanIp == "0.0.0.0" || peer.wanIp.isBlank()) return

        Thread {
            try {
                val encrypted = encrypt(packetBytes, peer.sharedSecretKey)
                
                // Add header to identify sending peer ID:
                // byte 0: MAGIC_P2P (0xE9)
                // byte 1: senderPeerId
                val payload = ByteArray(2 + encrypted.size)
                payload[0] = 0xE9.toByte()
                payload[1] = localPeerId.toByte()
                System.arraycopy(encrypted, 0, payload, 2, encrypted.size)

                val socket = udpSocket ?: return@Thread
                val address = InetAddress.getByName(peer.wanIp)
                val packet = DatagramPacket(payload, payload.size, address, peer.wanPort)
                socket.send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending packet to peer $peerId: ${e.message}")
            }
        }.start()
    }

    private fun startP2P() {
        stopP2P()
        Log.i(TAG, "Starting P2P Mesh service...")
        
        try {
            udpSocket = DatagramSocket(PEER_PORT).apply {
                reuseAddress = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Port $PEER_PORT busy, binding to dynamic port")
            try {
                udpSocket = DatagramSocket()
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to open UDP socket: ${ex.message}")
                return
            }
        }

        // 1. Start listening thread
        listenThread = Thread {
            val buffer = ByteArray(65535)
            val socket = udpSocket ?: return@Thread
            Log.i(TAG, "UDP P2P Receiver Listening on port ${socket.localPort}")
            
            while (isP2PEnabled.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    
                    val length = packet.length
                    if (length < 14) continue // header (2) + IV (12) + min data

                    val magic = buffer[0].toInt() and 0xFF
                    val senderId = buffer[1].toInt() and 0xFF
                    
                    if (magic == 0xE9) { // Data Packet
                        val peer = peers[senderId]
                        if (peer != null) {
                            // Update WAN address in case of NAT port changes
                            peer.wanIp = packet.address.hostAddress ?: peer.wanIp
                            peer.wanPort = packet.port
                            peer.lastSeenMs = System.currentTimeMillis()
                            
                            val encrypted = ByteArray(length - 2)
                            System.arraycopy(buffer, 2, encrypted, 0, length - 2)

                            try {
                                val decrypted = decrypt(encrypted, peer.sharedSecretKey)
                                writeToTunCallback?.invoke(decrypted)
                            } catch (decErr: Exception) {
                                Log.e(TAG, "Failed to decrypt packet from peer $senderId: ${decErr.message}")
                            }
                        }
                    } else if (magic == 0xEA) { // Ping/Hole Punching handshake
                        val peer = peers[senderId]
                        if (peer != null) {
                            peer.wanIp = packet.address.hostAddress ?: peer.wanIp
                            peer.wanPort = packet.port
                            peer.lastSeenMs = System.currentTimeMillis()
                            
                            // Send Pong
                            val pong = ByteArray(2)
                            pong[0] = 0xEB.toByte()
                            pong[1] = localPeerId.toByte()
                            socket.send(DatagramPacket(pong, 2, packet.socketAddress))
                        }
                    } else if (magic == 0xEB) { // Pong response
                        val peer = peers[senderId]
                        if (peer != null) {
                            peer.wanIp = packet.address.hostAddress ?: peer.wanIp
                            peer.wanPort = packet.port
                            peer.pingMs = (System.currentTimeMillis() - peer.lastSeenMs).toInt().coerceAtLeast(1)
                            peer.lastSeenMs = System.currentTimeMillis()
                        }
                    }
                } catch (e: Exception) {
                    if (isP2PEnabled.get()) {
                        Log.d(TAG, "Socket receive error: ${e.message}")
                    }
                }
            }
        }.apply { start() }

        // 2. Start STUN & Keep-alive / Hole punching loop
        keepAliveThread = Thread {
            val socket = udpSocket ?: return@Thread
            while (isP2PEnabled.get() && !Thread.currentThread().isInterrupted) {
                try {
                    // Query STUN every 60 seconds
                    val resolved = queryStun(socket)
                    if (resolved != null) {
                        localWanAddress = resolved
                        Log.d(TAG, "STUN mapped address resolved: $resolved")
                    }

                    // Keep-alive/Hole punching to all peers
                    peers.values.forEach { peer ->
                        if (peer.wanIp != "0.0.0.0" && peer.wanIp.isNotBlank()) {
                            val handshake = ByteArray(2)
                            handshake[0] = 0xEA.toByte() // Ping
                            handshake[1] = localPeerId.toByte()
                            
                            peer.lastSeenMs = System.currentTimeMillis() // Start ping timer
                            val peerAddr = InetSocketAddress(peer.wanIp, peer.wanPort)
                            socket.send(DatagramPacket(handshake, 2, peerAddr))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Keep-alive thread error: ${e.message}")
                }
                
                try {
                    Thread.sleep(10000) // Sleep 10s between checks
                } catch (ie: InterruptedException) {
                    break
                }
            }
        }.apply { start() }
    }

    private fun queryStun(socket: DatagramSocket): InetSocketAddress? {
        try {
            val stunServer = InetSocketAddress("stun.l.google.com", 19302)
            val request = ByteArray(20)
            request[0] = 0x00
            request[1] = 0x01 // Binding Request
            request[2] = 0x00
            request[3] = 0x00 // Length = 0
            
            // Magic Cookie
            request[4] = 0x21
            request[5] = 0x12
            request[6] = 0xA4.toByte()
            request[7] = 0x42.toByte()
            
            // Random Transaction ID
            val transactionId = ByteArray(12)
            SecureRandom().nextBytes(transactionId)
            System.arraycopy(transactionId, 0, request, 8, 12)

            val sendPacket = DatagramPacket(request, request.size, stunServer)
            socket.send(sendPacket)

            val response = ByteArray(1024)
            val receivePacket = DatagramPacket(response, response.size)
            val oldTimeout = socket.soTimeout
            socket.soTimeout = 1500
            try {
                socket.receive(receivePacket)
            } finally {
                socket.soTimeout = oldTimeout
            }

            var pos = 20
            while (pos + 4 <= receivePacket.length) {
                val attrType = ((response[pos].toInt() and 0xFF) shl 8) or (response[pos + 1].toInt() and 0xFF)
                val attrLen = ((response[pos + 2].toInt() and 0xFF) shl 8) or (response[pos + 3].toInt() and 0xFF)
                pos += 4
                if (attrType == 0x0001) { // MAPPED-ADDRESS
                    if (pos + attrLen > receivePacket.length) break
                    val family = response[pos + 1].toInt()
                    val port = ((response[pos + 2].toInt() and 0xFF) shl 8) or (response[pos + 3].toInt() and 0xFF)
                    if (family == 0x01) { // IPv4
                        val ipBytes = ByteArray(4)
                        System.arraycopy(response, pos + 4, ipBytes, 0, 4)
                        val address = InetAddress.getByAddress(ipBytes)
                        return InetSocketAddress(address, port)
                    }
                } else if (attrType == 0x0020) { // XOR-MAPPED-ADDRESS
                    if (pos + attrLen > receivePacket.length) break
                    val family = response[pos + 1].toInt()
                    val xPort = ((response[pos + 2].toInt() and 0xFF) shl 8) or (response[pos + 3].toInt() and 0xFF)
                    val port = xPort xor 0x2112
                    if (family == 0x01) { // IPv4
                        val ipBytes = ByteArray(4)
                        System.arraycopy(response, pos + 4, ipBytes, 0, 4)
                        ipBytes[0] = (ipBytes[0].toInt() xor 0x21).toByte()
                        ipBytes[1] = (ipBytes[1].toInt() xor 0x12).toByte()
                        ipBytes[2] = (ipBytes[2].toInt() xor 0xA4).toByte()
                        ipBytes[3] = (ipBytes[3].toInt() xor 0x42).toByte()
                        val address = InetAddress.getByAddress(ipBytes)
                        return InetSocketAddress(address, port)
                    }
                }
                pos += (attrLen + 3) and 0xFFFC.inv()
            }
        } catch (e: Exception) {
            Log.w(TAG, "STUN query failed: ${e.message}")
        }
        return null
    }

    private fun stopP2P() {
        Log.i(TAG, "Stopping P2P Mesh service...")
        listenThread?.interrupt()
        listenThread = null
        
        keepAliveThread?.interrupt()
        keepAliveThread = null

        udpSocket?.close()
        udpSocket = null
        localWanAddress = null
    }
}
