package com.linux_core.xlauncher

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal RFB 3.8 client that renders an Xvnc framebuffer.
 *
 * Only Raw (0) encoding is requested; DesktopSize (-223) and LastRect (-224)
 * are enabled so the server can resize the desktop and terminate each update.
 * Pixel format is forced to 32bpp, little-endian, truecolor (RGBX) so decoding
 * is deterministic.
 */
class VncClient {

    interface Listener {
        fun onConnected(width: Int, height: Int)
        fun onFramebuffer(x: Int, y: Int, w: Int, h: Int, pixels: IntArray)
        fun onDesktopSize(w: Int, h: Int)
        fun onDisconnected()
        fun onError(t: Throwable)
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var running = false

    @Volatile private var fbWidth = 0
    @Volatile private var fbHeight = 0

    fun connect(config: ConnectionConfig, listener: Listener) {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(config.host, config.port), 5000)
            sock.tcpNoDelay = true
            socket = sock
            input = DataInputStream(sock.getInputStream())
            output = DataOutputStream(sock.getOutputStream())
            running = true
            handshake(config.password, listener)
            receiveLoop(listener)
        } catch (t: Throwable) {
            Log.e(TAG, "VNC connection failed", t)
            listener.onError(t)
        } finally {
            disconnect()
            listener.onDisconnected()
        }
    }

    private fun handshake(password: String, listener: Listener) {
        val `in` = input!!
        val out = output!!

        // 1) Protocol version
        val version = ByteArray(12)
        `in`.readFully(version)
        if (!String(version, Charsets.ISO_8859_1).startsWith("RFB 003.00")) {
            throw IOException("Unsupported RFB version: ${String(version)}")
        }
        out.writeBytes("RFB 003.008\n")
        out.flush()

        // 2) Security types
        val nTypes = `in`.readUnsignedByte()
        if (nTypes == 0) {
            val reasonLen = `in`.readInt()
            val reason = ByteArray(reasonLen)
            `in`.readFully(reason)
            throw IOException("Server refused connection: ${String(reason)}")
        }
        val types = ByteArray(nTypes)
        `in`.readFully(types)
        val secType = when {
            types.contains(2.toByte()) -> 2   // VNC Authentication
            types.contains(1.toByte()) -> 1   // None
            else -> throw IOException("No supported security type (types=${types.contentToString()})")
        }
        out.writeByte(secType)
        out.flush()

        // 3) Authentication
        if (secType == 2) {
            val challenge = ByteArray(16)
            `in`.readFully(challenge)
            out.write(VncAuth.challenge(password, challenge))
            out.flush()
        }
        val result = `in`.readInt()
        if (result != 0) {
            throw IOException("Authentication failed (code=$result)")
        }

        // 4) ClientInit
        out.writeByte(1) // shared desktop
        out.flush()

        // 5) ServerInit
        fbWidth = `in`.readUnsignedShort()
        fbHeight = `in`.readUnsignedShort()
        `in`.skipBytes(16) // pixel format (we override it next)
        val nameLen = `in`.readInt()
        if (nameLen > 0) `in`.skipBytes(nameLen)

        setPixelFormat()
        setEncodings(intArrayOf(-223, -224, 0)) // DesktopSize, LastRect, Raw
        listener.onConnected(fbWidth, fbHeight)

        // First full-frame request
        sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, false)
    }

    private fun setPixelFormat() {
        val out = output!!
        out.writeByte(0)        // SetPixelFormat
        out.writeByte(0)        // padding
        out.writeByte(32)       // bits-per-pixel
        out.writeByte(24)       // depth
        out.writeByte(0)        // bigEndianFlag = false
        out.writeByte(1)        // trueColourFlag
        out.writeShort(255)     // red-max
        out.writeShort(255)     // green-max
        out.writeShort(255)     // blue-max
        out.writeByte(16)       // red-shift
        out.writeByte(8)        // green-shift
        out.writeByte(0)        // blue-shift
        out.write(ByteArray(3)) // padding
        out.flush()
    }

    private fun setEncodings(encodings: IntArray) {
        val out = output!!
        out.writeByte(2)        // SetEncodings
        out.writeByte(0)        // padding
        out.writeShort(encodings.size)
        for (e in encodings) out.writeInt(e)
        out.flush()
    }

    private fun sendFramebufferUpdateRequest(x: Int, y: Int, w: Int, h: Int, incremental: Boolean) {
        val out = output ?: return
        out.writeByte(3)        // FramebufferUpdateRequest
        out.writeByte(if (incremental) 1 else 0)
        out.writeShort(x)
        out.writeShort(y)
        out.writeShort(w)
        out.writeShort(h)
        out.flush()
    }

    /** Send a pointer event. mask: 1=left, 2=mid, 4=right, 0=none. */
    fun pointerEvent(x: Int, y: Int, mask: Int) {
        val out = output ?: return
        try {
            out.writeByte(5)    // PointerEvent
            out.writeByte(mask)
            out.writeShort(x)
            out.writeShort(y)
            out.flush()
        } catch (_: Exception) { /* connection gone */ }
    }

    fun keyEvent(keysym: Int, down: Boolean) {
        val out = output ?: return
        try {
            out.writeByte(4)    // KeyEvent
            out.writeByte(if (down) 1 else 0)
            out.writeShort(keysym)
            out.flush()
        } catch (_: Exception) { /* connection gone */ }
    }

    private fun receiveLoop(listener: Listener) {
        val `in` = input!!
        while (running) {
            val msgType = `in`.readUnsignedByte()
            when (msgType) {
                0 -> { // FramebufferUpdate
                    `in`.readUnsignedByte() // padding
                    val numRects = `in`.readUnsignedShort()
                    for (i in 0 until numRects) {
                        val rx = `in`.readUnsignedShort()
                        val ry = `in`.readUnsignedShort()
                        val rw = `in`.readUnsignedShort()
                        val rh = `in`.readUnsignedShort()
                        val encoding = `in`.readInt()
                        when (encoding) {
                            0 -> { // Raw
                                val pixels = decodeRaw(rw, rh)
                                listener.onFramebuffer(rx, ry, rw, rh, pixels)
                            }
                            -224 -> { /* LastRect: end of this update */ }
                            -223 -> { // DesktopSize
                                fbWidth = rw
                                fbHeight = rh
                                listener.onDesktopSize(rw, rh)
                            }
                            else -> throw IOException("Unsupported encoding $encoding in rect $i")
                        }
                    }
                    sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true)
                }
                1 -> { // SetColourMapEntries (should not happen in truecolor)
                    `in`.readUnsignedByte()
                    `in`.readUnsignedShort()
                    val n = `in`.readUnsignedShort()
                    `in`.skipBytes(n * 3)
                }
                2 -> { /* Bell - ignore */ }
                3 -> { // ServerCutText
                    `in`.readUnsignedByte()
                    `in`.readUnsignedByte()
                    val len = `in`.readInt()
                    `in`.skipBytes(len)
                }
                else -> throw IOException("Unknown server message type $msgType")
            }
        }
    }

    private fun decodeRaw(w: Int, h: Int): IntArray {
        val `in` = input!!
        val count = w * h
        val raw = ByteArray(count * 4)
        `in`.readFully(raw)
        val pixels = IntArray(count)
        var p = 0
        for (i in 0 until count) {
            val v = (raw[p].toInt() and 0xFF) or
                    ((raw[p + 1].toInt() and 0xFF) shl 8) or
                    ((raw[p + 2].toInt() and 0xFF) shl 16) or
                    ((raw[p + 3].toInt() and 0xFF) shl 24)
            val r = (v ushr 16) and 0xFF
            val g = (v ushr 8) and 0xFF
            val b = v and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            p += 4
        }
        return pixels
    }

    fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    companion object {
        private const val TAG = "VncClient"
    }
}
