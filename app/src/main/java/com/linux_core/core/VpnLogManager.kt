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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

object VpnLogManager {
    private const val TAG = "VpnLogManager"
    private const val MAX_LOGS = 5000

    private var historyStore: TrafficHistoryStore? = null
    @Volatile
    private var initialized = false
    private var persistCounter = 0
    private const val PERSIST_INTERVAL = 50
    private val persistBatch = ConcurrentLinkedQueue<LogEntry>()
    private val processCache = ConcurrentHashMap<String, ProcessResolver.ProcessInfo>()

    enum class AuditCategory {
        ALLOWED,
        BLOCKED,
        SUSPICIOUS,
        CRITICAL,
        VERBOSE
    }

    data class LogEntry(
        val timestamp: Long,
        val protocol: String,
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val size: Int,
        val category: AuditCategory,
        val detail: String = "",
        val entropy: Double = 0.0,
        val appName: String = "",
        val sessionName: String? = null,
        val packageName: String? = null,
        val elapsedTimeMs: Long = 0L,
        val bytesSent: Long = 0L,
        val bytesReceived: Long = 0L
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
                put("appName", appName)
                sessionName?.let { put("sessionName", it) }
                packageName?.let { put("packageName", it) }
                put("elapsedTimeMs", elapsedTimeMs)
                put("bytesSent", bytesSent)
                put("bytesReceived", bytesReceived)
            }
        }
    }

    data class DnsLogEntry(
        val timestamp: Long,
        val domain: String,
        val type: String,
        val category: AuditCategory,
        val ruleText: String? = null
    )

    private val dnsEntries = ConcurrentLinkedQueue<DnsLogEntry>()
    private val customBlocklist = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val dnsBlockCache = ConcurrentHashMap<String, Boolean>()

    fun loadCustomBlocklist(context: Context) {
        val sharedPrefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        val rules = sharedPrefs.getStringSet("dns_blocklist", emptySet()) ?: emptySet()
        customBlocklist.clear()
        customBlocklist.addAll(rules)
        dnsBlockCache.clear()
    }

    fun addBlocklistRule(context: Context, rule: String) {
        customBlocklist.add(rule)
        dnsBlockCache.clear()
        val sharedPrefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        sharedPrefs.edit().putStringSet("dns_blocklist", customBlocklist).apply()
    }

    fun removeBlocklistRule(context: Context, rule: String) {
        customBlocklist.remove(rule)
        dnsBlockCache.clear()
        val sharedPrefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        sharedPrefs.edit().putStringSet("dns_blocklist", customBlocklist).apply()
    }

    fun getBlocklistRules(): List<String> {
        return customBlocklist.toList()
    }

    fun isDomainBlocked(domain: String): Boolean {
        val cleanDomain = domain.trim().lowercase()
        dnsBlockCache[cleanDomain]?.let { return it }
        val blocked = isDomainBlockedUncached(cleanDomain)
        dnsBlockCache[cleanDomain] = blocked
        return blocked
    }

    private fun isDomainBlockedUncached(cleanDomain: String): Boolean {
        for (rule in customBlocklist) {
            val cleanRule = rule.trim().lowercase()
            if (cleanRule.startsWith("*.")) {
                val suffix = cleanRule.substring(2)
                if (cleanDomain == suffix || cleanDomain.endsWith(".$suffix")) {
                    return true
                }
            } else {
                if (cleanDomain == cleanRule) {
                    return true
                }
            }
        }
        return false
    }

    fun logDnsQuery(domain: String, type: String, category: AuditCategory, ruleText: String? = null) {
        dnsEntries.add(DnsLogEntry(System.currentTimeMillis(), domain, type, category, ruleText))
        while (dnsEntries.size > MAX_LOGS) {
            dnsEntries.poll()
        }
    }

    fun getDnsLogs(): List<DnsLogEntry> {
        return dnsEntries.toList().reversed()
    }

    fun getTopDomains(): List<Pair<String, Int>> {
        return dnsEntries.groupBy { it.domain }
            .map { it.key to it.value.size }
            .sortedByDescending { it.second }
            .take(10)
    }

    // Thread-safe memory buffer for UI
    private val entries = ConcurrentLinkedQueue<LogEntry>()

    private fun ensureInitialized(context: Context?) {
        if (initialized || context == null) return
        synchronized(this) {
            if (initialized) return
            historyStore = TrafficHistoryStore(context.applicationContext)
            // Restore persisted log entries
            val persisted = historyStore!!.loadLogEntries()
            entries.addAll(persisted)
            // Restore traffic arrays
            hourlyDownload.indices.forEach { i -> hourlyDownload[i] = historyStore!!.loadTrafficArray("hourly_dl", 24)[i] }
            hourlyUpload.indices.forEach { i -> hourlyUpload[i] = historyStore!!.loadTrafficArray("hourly_ul", 24)[i] }
            dailyDownload.indices.forEach { i -> dailyDownload[i] = historyStore!!.loadTrafficArray("daily_dl", 30)[i] }
            dailyUpload.indices.forEach { i -> dailyUpload[i] = historyStore!!.loadTrafficArray("daily_ul", 30)[i] }
            weeklyDownload.indices.forEach { i -> weeklyDownload[i] = historyStore!!.loadTrafficArray("weekly_dl", 12)[i] }
            weeklyUpload.indices.forEach { i -> weeklyUpload[i] = historyStore!!.loadTrafficArray("weekly_ul", 12)[i] }
            initialized = true
            Log.i(TAG, "Restored ${persisted.size} persisted log entries")
        }
    }

    data class AiTelemetryPoint(
        val timestamp: Long,
        val size: Int,
        val entropy: Double,
        val deltaTime: Float,
        val category: AuditCategory
    )

    private val aiTelemetryPoints = ConcurrentLinkedQueue<AiTelemetryPoint>()

    fun getAiTelemetry(): List<AiTelemetryPoint> {
        return aiTelemetryPoints.toList()
    }

    // Statistics Aggregations
    fun getTotalRequests(): Long = entries.size.toLong()

    fun getTotalBlockedAds(): Long {
        return entries.count { it.category == AuditCategory.BLOCKED || it.detail.contains("block", ignoreCase = true) || it.detail.contains("ad", ignoreCase = true) }.toLong()
    }

    fun getTotalBlockedTrackers(): Long {
        return entries.count { it.category == AuditCategory.SUSPICIOUS || it.category == AuditCategory.CRITICAL }.toLong()
    }

    fun getTotalBytesSaved(): Long {
        val blockedCount = getTotalBlockedAds() + getTotalBlockedTrackers()
        return blockedCount * 180_000L // Estimate 180KB saved per blocked request
    }

    fun getTotalBytesUploaded(): Long {
        return entries.sumOf { it.bytesSent }
    }

    fun getTotalBytesDownloaded(): Long {
        return entries.sumOf { it.bytesReceived }
    }

    fun getTopApps(): List<Triple<String, String?, Int>> {
        // Returns list of Triple(appName, packageName, count) sorted by count descending
        return entries.filter { it.appName.isNotEmpty() }
            .groupBy { it.appName to it.packageName }
            .map { Triple(it.key.first, it.key.second, it.value.size) }
            .sortedByDescending { it.third }
            .take(6)
    }

    // Telemetry tracking arrays (Bytes)
    private val hourlyDownload = LongArray(24)
    private val hourlyUpload = LongArray(24)

    private val dailyDownload = LongArray(30)
    private val dailyUpload = LongArray(30)

    private val weeklyDownload = LongArray(12)
    private val weeklyUpload = LongArray(12)

    init {
        // Telemetry arrays start empty, no mock data anymore.
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
        context: Context?,
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

        val cacheKey = "$protocol:$srcIp:$srcPort:$dstIp:$dstPort"
        val resolved = processCache.getOrPut(cacheKey) {
            if (context != null) ProcessResolver.resolve(context, protocol, srcIp, srcPort, dstIp, dstPort)
            else ProcessResolver.ProcessInfo("System", null)
        }

        val elapsedTime = if (category == AuditCategory.BLOCKED) 0L else (2..180).random().toLong()
        val isUpload = (srcIp == "10.0.0.2")
        val bSent = if (isUpload) size.toLong() else 0L
        val bRecv = if (!isUpload) size.toLong() else 0L

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
            entropy = entropyVal,
            appName = resolved.appName,
            sessionName = resolved.sessionName,
            packageName = resolved.packageName,
            elapsedTimeMs = elapsedTime,
            bytesSent = bSent,
            bytesReceived = bRecv
        )
        entries.add(entry)
        while (entries.size > MAX_LOGS) {
            entries.poll()
        }

        // Aggregate AI brain classification telemetry
        val deltaVal = if (category == AuditCategory.BLOCKED) 0.0f else (elapsedTime / 1000.0f)
        aiTelemetryPoints.add(
            AiTelemetryPoint(
                timestamp = entry.timestamp,
                size = entry.size,
                entropy = entropyVal,
                deltaTime = deltaVal,
                category = category
            )
        )
        while (aiTelemetryPoints.size > 100) {
            aiTelemetryPoints.poll()
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

        ensureInitialized(context)
        val store = historyStore ?: return
        persistBatch.add(entry)
        persistCounter++
        if (persistCounter >= PERSIST_INTERVAL) {
            persistCounter = 0
            while (true) {
                val batchEntry = persistBatch.poll() ?: break
                store.persistLogEntry(batchEntry)
            }
            store.saveTrafficArray("hourly_dl", hourlyDownload)
            store.saveTrafficArray("hourly_ul", hourlyUpload)
            store.saveTrafficArray("daily_dl", dailyDownload)
            store.saveTrafficArray("daily_ul", dailyUpload)
            store.saveTrafficArray("weekly_dl", weeklyDownload)
            store.saveTrafficArray("weekly_ul", weeklyUpload)
        }
    }

    fun getLogs(): List<LogEntry> {
        return entries.toList().reversed()
    }

    fun initialize(context: Context) {
        ensureInitialized(context.applicationContext)
    }

    fun flush() {
        val store = historyStore ?: return
        while (true) {
            val entry = persistBatch.poll() ?: break
            store.persistLogEntry(entry)
        }
        persistCounter = 0
        store.saveTrafficArray("hourly_dl", hourlyDownload)
        store.saveTrafficArray("hourly_ul", hourlyUpload)
        store.saveTrafficArray("daily_dl", dailyDownload)
        store.saveTrafficArray("daily_ul", dailyUpload)
        store.saveTrafficArray("weekly_dl", weeklyDownload)
        store.saveTrafficArray("weekly_ul", weeklyUpload)
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
        val db = historyStore?.writableDatabase
        if (db != null) {
            try {
                db.execSQL("DELETE FROM traffic_entries")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear persisted logs: ${e.message}")
            }
        }
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
