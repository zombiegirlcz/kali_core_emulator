package com.linux_core.core

import java.nio.ByteBuffer

class IpHeader(val buffer: ByteBuffer, val offset: Int) {
    val version: Int
        get() = (buffer.get(offset).toInt() shr 4) and 0x0F
        
    val ihl: Int
        get() = (buffer.get(offset).toInt() and 0x0F) * 4
        
    val protocol: Int
        get() = buffer.get(offset + 9).toInt() and 0xFF
        
    var totalLength: Int
        get() = buffer.getShort(offset + 2).toInt() and 0xFFFF
        set(value) {
            buffer.putShort(offset + 2, value.toShort())
        }
        
    var sourceAddress: Int
        get() = buffer.getInt(offset + 12)
        set(value) {
            buffer.putInt(offset + 12, value)
        }
        
    var destinationAddress: Int
        get() = buffer.getInt(offset + 16)
        set(value) {
            buffer.putInt(offset + 16, value)
        }
        
    fun computeChecksum() {
        buffer.putShort(offset + 10, 0)
        val limit = offset + ihl
        var sum = 0
        var i = offset
        while (i < limit) {
            val word = (buffer.get(i).toInt() and 0xFF shl 8) or (buffer.get(i + 1).toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.inv() and 0xFFFF).toShort()
        buffer.putShort(offset + 10, checksum)
    }
}

class TcpHeader(val buffer: ByteBuffer, val offset: Int) {
    var sourcePort: Int
        get() = buffer.getShort(offset).toInt() and 0xFFFF
        set(value) {
            buffer.putShort(offset, value.toShort())
        }
        
    var destinationPort: Int
        get() = buffer.getShort(offset + 2).toInt() and 0xFFFF
        set(value) {
            buffer.putShort(offset + 2, value.toShort())
        }
        
    var seqNum: Long
        get() = buffer.getInt(offset + 4).toLong() and 0xFFFFFFFFL
        set(value) {
            buffer.putInt(offset + 4, value.toInt())
        }
        
    var ackNum: Long
        get() = buffer.getInt(offset + 8).toLong() and 0xFFFFFFFFL
        set(value) {
            buffer.putInt(offset + 8, value.toInt())
        }
        
    val dataOffset: Int
        get() = ((buffer.get(offset + 12).toInt() shr 4) and 0x0F) * 4
        
    var flags: Int
        get() = buffer.get(offset + 13).toInt() and 0xFF
        set(value) {
            buffer.put(offset + 13, value.toByte())
        }
        
    val isSYN: Boolean
        get() = (flags and 0x02) != 0
        
    val isACK: Boolean
        get() = (flags and 0x10) != 0
        
    val isFIN: Boolean
        get() = (flags and 0x01) != 0
        
    val isRST: Boolean
        get() = (flags and 0x04) != 0

    val isPSH: Boolean
        get() = (flags and 0x08) != 0

    fun computeChecksum(ip: IpHeader) {
        buffer.putShort(offset + 16, 0)
        
        val tcpLen = ip.totalLength - ip.ihl
        var sum = 0
        
        // Pseudo header
        sum += (ip.sourceAddress shr 16) and 0xFFFF
        sum += ip.sourceAddress and 0xFFFF
        sum += (ip.destinationAddress shr 16) and 0xFFFF
        sum += ip.destinationAddress and 0xFFFF
        sum += ip.protocol
        sum += tcpLen
        
        var i = offset
        val limit = offset + tcpLen
        while (i < limit - 1) {
            val word = (buffer.get(i).toInt() and 0xFF shl 8) or (buffer.get(i + 1).toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i == limit - 1) {
            val word = buffer.get(i).toInt() and 0xFF shl 8
            sum += word
        }
        
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        
        val checksum = (sum.inv() and 0xFFFF).toShort()
        buffer.putShort(offset + 16, checksum)
    }
}

class UdpHeader(val buffer: ByteBuffer, val offset: Int) {
    var sourcePort: Int
        get() = buffer.getShort(offset).toInt() and 0xFFFF
        set(value) {
            buffer.putShort(offset, value.toShort())
        }
        
    var destinationPort: Int
        get() = buffer.getShort(offset + 2).toInt() and 0xFFFF
        set(value) {
            buffer.putShort(offset + 2, value.toShort())
        }
        
    var length: Int
        get() = buffer.getShort(offset + 4).toInt() and 0xFFFF
        set(value) {
            buffer.putShort(offset + 4, value.toShort())
        }
        
    fun computeChecksum(ip: IpHeader) {
        buffer.putShort(offset + 6, 0)
        
        var sum = 0
        sum += (ip.sourceAddress shr 16) and 0xFFFF
        sum += ip.sourceAddress and 0xFFFF
        sum += (ip.destinationAddress shr 16) and 0xFFFF
        sum += ip.destinationAddress and 0xFFFF
        sum += ip.protocol
        sum += length
        
        var i = offset
        val limit = offset + length
        while (i < limit - 1) {
            val word = (buffer.get(i).toInt() and 0xFF shl 8) or (buffer.get(i + 1).toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i == limit - 1) {
            val word = buffer.get(i).toInt() and 0xFF shl 8
            sum += word
        }
        
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        
        val checksum = (sum.inv() and 0xFFFF).toShort()
        buffer.putShort(offset + 6, checksum)
    }
}
