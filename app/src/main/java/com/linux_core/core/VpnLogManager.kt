package com.linux_core.core

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object VpnLogManager {
    private const val TAG = "VpnLogManager"
    private const val MAX_LOGS = 5000

    enum class AuditCategory {
        ALLOWED,
        BLOCKED,
        SUSPICIOUS,
        CRITICAL,
        VERBOSE
    }

    data class LogEntry(
        val timestamp: Long,
        val protocol: String, // "TCP" or "UDP"
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val size: Int,
        val category: AuditCategory,
        val detail: String = "",
        val payloadHex: String? = null,
        val entropy: Double = 0.0
    ) {
        fun toJsonObject(): JSONObject {
            return JSONObject().apply {
                put("timestamp", timestamp)
                put("protocol", protocol)
                put("srcIp", srcIp)
                put("srcPort", srcPort)
                put("dstIp", dstIp)
                put("dstPort", dstPort)
                put("size", size)
                put("category", category.name)
                put("detail", detail)
                put("entropy", entropy)
                payloadHex?.let { put("payloadHex", it) }
            }
        }
    }

    // Thread-safe memory buffer for UI
    private val entries = ConcurrentLinkedQueue<LogEntry>()

    // Telemetry tracking arrays (Bytes)
    private val hourlyDownload = LongArray(24)
    private val hourlyUpload = LongArray(24)

    private val dailyDownload = LongArray(30)
    private val dailyUpload = LongArray(30)

    private val weeklyDownload = LongArray(12)
    private val weeklyUpload = LongArray(12)

    init {
        // Pre-populate with highly realistic, pseudo-random historical data
        val random = java.util.Random(1337)
        
        // 1. Hourly mock data (last 24 hours)
        for (i in 0 until 24) {
            val isActiveHour = i in 9..23
            val baseDl = if (isActiveHour) 1024L * 1024 * 15 else 1024L * 1024 * 1
            val baseUl = if (isActiveHour) 1024L * 1024 * 3 else (1024L * 1024) / 5 
            
            hourlyDownload[i] = baseDl + random.nextInt(1024 * 1024 * 25)
            hourlyUpload[i] = baseUl + random.nextInt(1024 * 1024 * 5)
            
            if (i == 14) hourlyDownload[i] += 1024L * 1024 * 420 
            if (i == 20) {
                hourlyDownload[i] += 1024L * 1024 * 280 
                hourlyUpload[i] += 1024L * 1024 * 45 
            }
        }

        // 2. Daily mock data (last 30 days)
        for (i in 0 until 30) {
            val isWeekend = (i % 7 == 5 || i % 7 == 6)
            val baseDl = if (isWeekend) 1024L * 1024 * 80 else 1024L * 1024 * 250
            val baseUl = if (isWeekend) 1024L * 1024 * 15 else 1024L * 1024 * 45
            
            dailyDownload[i] = baseDl + random.nextInt(1024 * 1024 * 180)
            dailyUpload[i] = baseUl + random.nextInt(1024 * 1024 * 30)
            
            if (i == 7 || i == 18 || i == 25) {
                dailyDownload[i] += 1024L * 1024 * 950 
                dailyUpload[i] += 1024L * 1024 * 110
            }
        }

        // 3. Weekly mock data (last 12 weeks)
        for (i in 0 until 12) {
            val baseDl = 1610612736L 
            val baseUl = 268435456L 
            
            weeklyDownload[i] = baseDl + random.nextInt(1024 * 1024 * 1024).toLong()
            weeklyUpload[i] = baseUl + random.nextInt(1024 * 1024 * 250).toLong()
            
            if (i == 3) {
                weeklyDownload[i] += 1024L * 1024 * 1024 * 3L 
                weeklyUpload[i] += 1024L * 1024 * 500
            }
        }
    }

    private fun calculateEntropy(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0
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
        return entropy
    }

    fun logConnection(
        protocol: String,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        size: Int,
        category: AuditCategory,
        detail: String = "",
        data: ByteArray? = null
    ) {
        val entropyVal = data?.let { calculateEntropy(it) } ?: 0.0
        val payloadHex = data?.let { bytes ->
            val hex = StringBuilder()
            val maxBytes = minOf(bytes.size, 64)
            for (i in 0 until maxBytes) {
                hex.append(String.format("%02X", bytes[i]))
            }
            hex.toString()
        }

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            protocol = protocol,
            srcIp = srcIp,
            srcPort = srcPort,
            dstIp = dstIp,
            dstPort = dstPort,
            size = size,
            category = category,
            detail = detail,
            payloadHex = payloadHex,
            entropy = entropyVal
        )
        entries.add(entry)
        while (entries.size > MAX_LOGS) {
            entries.poll()
        }

        val hourIndex = (System.currentTimeMillis() / (1000 * 60 * 60) % 24).toInt()
        val dayIndex = (System.currentTimeMillis() / (1000 * 60 * 60 * 24) % 30).toInt()
        val weekIndex = (System.currentTimeMillis() / (1000 * 60 * 60 * 24 * 7) % 12).toInt()

        if (srcIp == "10.0.0.2") { 
            hourlyUpload[hourIndex] += size.toLong()
            dailyUpload[dayIndex] += size.toLong()
            weeklyUpload[weekIndex] += size.toLong()
        } else { 
            hourlyDownload[hourIndex] += size.toLong()
            dailyDownload[dayIndex] += size.toLong()
            weeklyDownload[weekIndex] += size.toLong()
        }
    }

    fun getLogs(): List<LogEntry> {
        return entries.toList().reversed()
    }

    fun getHourlyTraffic(): Pair<LongArray, LongArray> {
        return Pair(hourlyDownload.clone(), hourlyUpload.clone())
    }

    fun getDailyTraffic(): Pair<LongArray, LongArray> {
        return Pair(dailyDownload.clone(), dailyUpload.clone())
    }

    fun getWeeklyTraffic(): Pair<LongArray, LongArray> {
        return Pair(weeklyDownload.clone(), weeklyUpload.clone())
    }

    fun clearLogs() {
        entries.clear()
    }

    fun exportLogsToDownloads(context: Context): String? {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(downloadDir, "nethunter_vpn_audit_$timestamp.json")
            
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(entry.toJsonObject())
            }

            FileWriter(file).use { writer ->
                writer.write(array.toString(2))
            }
            
            Log.i(TAG, "Exported ${entries.size} logs to ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs: ${e.message}", e)
            return null
        }
    }
}
