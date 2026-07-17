package com.linux_core.security

import android.util.Log

object TlsClientHelloParser {

    private const val TAG = "TlsClientHelloParser"
    private val TLS_VERSION_MINORS = listOf<Int>(1, 2, 3, 4)
    private const val EXT_SERVER_NAME = 0x0000
    private const val EXT_ALPN = 0x0010
    val DOH_ALPN_PROTOCOLS = setOf("h2", "http/1.1")
    val DOH_INDICATOR = "application/dns-message"

    fun isTlsClientHello(data: ByteArray, offset: Int = 0, len: Int = data.size): Boolean {
        if (len < 45) return false
        val contentType = data[offset].toInt() and 0xFF
        val versionMajor = data[offset + 1].toInt() and 0xFF
        val versionMinor = data[offset + 2].toInt() and 0xFF
        val handshakeType = data[offset + 5].toInt() and 0xFF
        if (contentType != 0x16) return false
        if (versionMajor != 0x03) return false
        if (versionMinor !in TLS_VERSION_MINORS) return false
        if (handshakeType != 0x01) return false
        val sessionIdLen = data[offset + 43].toInt() and 0xFF
        if (offset + 44 + sessionIdLen + 2 > len) return false
        return true
    }

    /**
     * Walk to the start of the extensions block. Returns (extensionsStart, extensionsEnd) or null.
     * The returned range covers only the extension TLVs themselves (not the length prefix).
     */
    private fun extensionsRange(data: ByteArray, offset: Int, len: Int): IntRange? {
        if (!isTlsClientHello(data, offset, len)) return null
        var pos = offset + 5
        if (len < pos + 4) return null
        pos += 4
        if (pos >= len) return null
        pos += 2 + 32
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
        return pos until extensionsEnd
    }

    fun extractSni(data: ByteArray, offset: Int = 0, len: Int = data.size): String? {
        val range = extensionsRange(data, offset, len) ?: return null
        var pos = range.first
        val end = range.last + 1
        while (pos + 4 <= end) {
            val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val extLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            pos += 4
            if (extType == EXT_SERVER_NAME) {
                val extEnd = pos + extLen
                if (extEnd > end) return null
                val listLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                var p = pos + 2
                val listEnd = p + listLen
                if (listEnd > extEnd) return null
                while (p + 3 <= listEnd) {
                    val nameType = data[p].toInt() and 0xFF
                    val nameLen = ((data[p + 1].toInt() and 0xFF) shl 8) or (data[p + 2].toInt() and 0xFF)
                    p += 3
                    if (nameType == 0x00 && p + nameLen <= listEnd) {
                        return String(data, p, nameLen, Charsets.US_ASCII)
                    }
                    p += nameLen
                }
                return null
            }
            pos += extLen
        }
        return null
    }

    /**
     * Returns the list of ALPN protocol names offered by the client.
     * Example: ["h2", "http/1.1"].
     */
    fun extractAlpn(data: ByteArray, offset: Int = 0, len: Int = data.size): List<String> {
        val range = extensionsRange(data, offset, len) ?: return emptyList()
        var pos = range.first
        val end = range.last + 1
        while (pos + 4 <= end) {
            val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val extLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            pos += 4
            if (extType == EXT_ALPN) {
                val extEnd = pos + extLen
                if (extEnd > end) return emptyList()
                val listLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                var p = pos + 2
                val listEnd = p + listLen
                if (listEnd > extEnd) return emptyList()
                val result = ArrayList<String>()
                while (p < listEnd) {
                    val protoLen = data[p].toInt() and 0xFF
                    p += 1
                    if (p + protoLen > listEnd) return emptyList()
                    result.add(String(data, p, protoLen, Charsets.US_ASCII))
                    p += protoLen
                }
                return result
            }
            pos += extLen
        }
        return emptyList()
    }

    /**
     * True if the ClientHello advertises DoH (HTTP/2 or HTTP/1.1 + application/dns-message).
     */
    fun isDohClientHello(data: ByteArray, offset: Int = 0, len: Int = data.size): Boolean {
        val alpn = extractAlpn(data, offset, len)
        if (alpn.isEmpty()) return false
        val hasHttp = alpn.any { it in DOH_ALPN_PROTOCOLS }
        if (!hasHttp) return false
        return alpn.contains(DOH_INDICATOR)
    }

    fun resolveFallbackHost(sni: String?, dstIp: String): String =
        sni ?: dstIp
}
