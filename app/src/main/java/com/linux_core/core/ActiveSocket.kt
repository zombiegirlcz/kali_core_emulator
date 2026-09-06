package com.linux_core.core

data class ActiveSocket(
    val protocol: String,
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
    val state: String,
    val bytesSent: Long,
    val bytesReceived: Long,
    val speedUpload: Long,
    val speedDownload: Long,
    val appName: String,
    val packageName: String?,
    val flagEmoji: String,
    val isTlsMitm: Boolean = false,
    val sni: String? = null
)
