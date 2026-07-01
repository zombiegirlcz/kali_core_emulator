package com.linux_core.security

import android.util.Log

object TlsClientHelloParser {

    private const val TAG = "TlsClientHelloParser"
    private val TLS_VERSION_MINORS = listOf<Int>(1, 2, 3, 4)
    private const val EXT_SERVER_NAME = 0x0000

    fun isTlsClientHello(data: ByteArray, offset: Int = 0, len: Int = data.size): Boolean {
        if (len < 6) return false
        val contentType = data[offset].toInt() and 0xFF
        val versionMajor = data[offset + 1].toInt() and 0xFF
        val versionMinor = data[offset + 2].toInt() and 0xFF
        val handshakeType = data[offset + 5].toInt() and 0xFF
        if (contentType != 0x16) return false
        if (versionMajor != 0x03) return false
        if (versionMinor !in TLS_VERSION_MINORS) return false
        if (handshakeType != 0x01) return false
        return true
    }

    fun extractSni(data: ByteArray, offset: Int = 0, len: Int = data.size): String? {
        try {
            if (!isTlsClientHello(data, offset, len)) return null
            var pos = offset + 5
            if (len < pos + 4) return null
            val handshakeLen = ((data[pos].toInt() and 0xFF) shl 16) or
                               ((data[pos + 1].toInt() and 0xFF) shl 8) or
                               (data[pos + 2].toInt() and 0xFF)
            pos += 3
            if (pos >= len) return null
            val clientVersion = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            val random = ByteArray(32)
            if (pos + 32 > len) return null
            System.arraycopy(data, pos, random, 0, 32)
            pos += 32
            if (pos >= len) return null
            val sessionIdLen = data[pos].toInt() and 0xFF
            pos += 1 + sessionIdLen
            if (pos + 2 > len) return null
            val cipherSuitesLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2 + cipherSuitesLen
            if (pos >= len) return null
            val compressionLen = data[pos].toInt() and 0xFF
            pos += 1 + compressionLen
            if (pos + 2 > len) return null
            val extensionsLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            val extensionsEnd = pos + extensionsLen
            if (extensionsEnd > len) return null
            while (pos + 4 <= extensionsEnd) {
                val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val extLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                pos += 4
                if (extType == EXT_SERVER_NAME && pos + 2 <= extensionsEnd) {
                    val listLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    pos += 2
                    val listEnd = pos + listLen
                    if (listEnd > extensionsEnd) return null
                    while (pos + 3 <= listEnd) {
                        val nameType = data[pos].toInt() and 0xFF
                        val nameLen = ((data[pos + 1].toInt() and 0xFF) shl 8) or (data[pos + 2].toInt() and 0xFF)
                        pos += 3
                        if (nameType == 0x00 && pos + nameLen <= listEnd) {
                            return String(data, pos, nameLen, Charsets.US_ASCII)
                        }
                        pos += nameLen
                    }
                    return null
                }
                pos += extLen
            }
        } catch (e: Exception) {
            Log.w(TAG, "SNI parse failed: ${e.message}")
        }
        return null
    }
}
