package com.linux_core.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class TrafficHistoryStore(context: Context) : SQLiteOpenHelper(context, "traffic_intelligence.db", null, 4) {

    companion object {
        private const val TAG = "TrafficHistoryStore"
        private const val MAX_PERSISTED_LOGS = 2000
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE session_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                app_name TEXT,
                remote_ip TEXT,
                remote_port INTEGER,
                domain TEXT,
                total_bytes INTEGER,
                max_entropy REAL,
                ai_strategy_index INTEGER,
                risk_score INTEGER
            )
        """)
        db.execSQL("""
            CREATE TABLE traffic_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                protocol TEXT NOT NULL,
                src_ip TEXT,
                src_port INTEGER,
                dst_ip TEXT,
                dst_port INTEGER,
                size INTEGER,
                category TEXT NOT NULL,
                detail TEXT,
                entropy REAL,
                app_name TEXT,
                session_name TEXT,
                package_name TEXT,
                elapsed_ms INTEGER,
                bytes_sent INTEGER,
                bytes_received INTEGER,
                payload_hex TEXT
            )
        """)
        db.execSQL("""
            CREATE TABLE traffic_arrays (
                name TEXT PRIMARY KEY,
                data TEXT NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS known_addresses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address TEXT UNIQUE NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                occurrence_count INTEGER DEFAULT 1,
                avg_interval_sec REAL,
                avg_entropy REAL,
                typical_port INTEGER,
                verdict TEXT DEFAULT 'unknown',
                verdict_source TEXT DEFAULT 'ai_auto',
                verdict_confidence REAL DEFAULT 0.0,
                notified_user INTEGER DEFAULT 0,
                notes TEXT,
                trace_id TEXT,
                baseline_entropy REAL,
                baseline_interval_sec REAL,
                last_reverify_at INTEGER
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS daily_stats (
                date TEXT PRIMARY KEY,
                total_flows INTEGER DEFAULT 0,
                new_addresses INTEGER DEFAULT 0,
                blocked_count INTEGER DEFAULT 0,
                allowed_count INTEGER DEFAULT 0,
                pending_count INTEGER DEFAULT 0,
                top_entropy_address TEXT
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pending_flows (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address TEXT NOT NULL UNIQUE,
                detected_at INTEGER NOT NULL,
                brain_confidence REAL,
                escalated_to_llm INTEGER DEFAULT 0,
                expires_at INTEGER NOT NULL,
                reason TEXT DEFAULT 'low_confidence',
                sni TEXT,
                occurrence_count INTEGER DEFAULT 1
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS known_addresses (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    address TEXT UNIQUE NOT NULL,
                    first_seen INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL,
                    occurrence_count INTEGER DEFAULT 1,
                    avg_interval_sec REAL,
                    avg_entropy REAL,
                    typical_port INTEGER,
                    verdict TEXT DEFAULT 'unknown',
                    verdict_source TEXT DEFAULT 'ai_auto',
                    verdict_confidence REAL DEFAULT 0.0,
                    notified_user INTEGER DEFAULT 0,
                    notes TEXT,
                    trace_id TEXT,
                    baseline_entropy REAL,
                    baseline_interval_sec REAL,
                    last_reverify_at INTEGER
                )
            """)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS daily_stats (
                    date TEXT PRIMARY KEY,
                    total_flows INTEGER DEFAULT 0,
                    new_addresses INTEGER DEFAULT 0,
                    blocked_count INTEGER DEFAULT 0,
                    allowed_count INTEGER DEFAULT 0,
                    pending_count INTEGER DEFAULT 0,
                    top_entropy_address TEXT
                )
            """)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS pending_flows (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    address TEXT NOT NULL UNIQUE,
                    detected_at INTEGER NOT NULL,
                    brain_confidence REAL,
                    escalated_to_llm INTEGER DEFAULT 0,
                    expires_at INTEGER NOT NULL,
                    reason TEXT DEFAULT 'low_confidence',
                    sni TEXT,
                    occurrence_count INTEGER DEFAULT 1
                )
            """)
        }
    }

    fun logSession(appName: String, ip: String, port: Int, domain: String?, bytes: Long, entropy: Float, strategy: Int) {
        try {
            val db = writableDatabase
            db.execSQL("""
                INSERT INTO session_history (app_name, remote_ip, remote_port, domain, total_bytes, max_entropy, ai_strategy_index, risk_score)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, arrayOf<Any>(appName, ip, port, domain ?: "unknown", bytes, entropy, strategy, if (strategy > 0) 100 else 0))
            db.execSQL("DELETE FROM session_history WHERE timestamp < datetime('now', '-1 day')")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log session: ${e.message}")
        }
    }

    fun persistLogEntry(entry: VpnLogManager.LogEntry) {
        try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("ts", entry.timestamp)
                put("protocol", entry.protocol)
                put("src_ip", entry.srcIp)
                put("src_port", entry.srcPort)
                put("dst_ip", entry.dstIp)
                put("dst_port", entry.dstPort)
                put("size", entry.size)
                put("category", entry.category.name)
                put("detail", entry.detail)
                put("entropy", entry.entropy)
                put("app_name", entry.appName)
                put("session_name", entry.sessionName)
                put("package_name", entry.packageName)
                put("elapsed_ms", entry.elapsedTimeMs)
                put("bytes_sent", entry.bytesSent)
                put("bytes_received", entry.bytesReceived)
            }
            db.insert("traffic_entries", null, cv)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist log: ${e.message}")
        }
    }

    fun loadLogEntries(lastN: Int = MAX_PERSISTED_LOGS): List<VpnLogManager.LogEntry> {
        val result = mutableListOf<VpnLogManager.LogEntry>()
        try {
            val db = readableDatabase
            val c = db.rawQuery(
                "SELECT * FROM traffic_entries ORDER BY ts DESC LIMIT ?",
                arrayOf(lastN.toString())
            )
            if (c.moveToFirst()) {
                do {
                    val category = try {
                        VpnLogManager.AuditCategory.valueOf(
                            c.getString(c.getColumnIndexOrThrow("category"))
                        )
                    } catch (_: Exception) {
                        VpnLogManager.AuditCategory.VERBOSE
                    }
                    result.add(
                        VpnLogManager.LogEntry(
                            timestamp = c.getLong(c.getColumnIndexOrThrow("ts")),
                            protocol = c.getString(c.getColumnIndexOrThrow("protocol")),
                            srcIp = c.getString(c.getColumnIndexOrThrow("src_ip")) ?: "",
                            srcPort = c.getInt(c.getColumnIndexOrThrow("src_port")),
                            dstIp = c.getString(c.getColumnIndexOrThrow("dst_ip")) ?: "",
                            dstPort = c.getInt(c.getColumnIndexOrThrow("dst_port")),
                            size = c.getInt(c.getColumnIndexOrThrow("size")),
                            category = category,
                            detail = c.getString(c.getColumnIndexOrThrow("detail")) ?: "",
                            entropy = c.getDouble(c.getColumnIndexOrThrow("entropy")),
                            appName = c.getString(c.getColumnIndexOrThrow("app_name")) ?: "",
                            sessionName = c.getString(c.getColumnIndexOrThrow("session_name")),
                            packageName = c.getString(c.getColumnIndexOrThrow("package_name")),
                            elapsedTimeMs = c.getLong(c.getColumnIndexOrThrow("elapsed_ms")),
                            bytesSent = c.getLong(c.getColumnIndexOrThrow("bytes_sent")),
                            bytesReceived = c.getLong(c.getColumnIndexOrThrow("bytes_received"))
                        )
                    )
                } while (c.moveToNext())
            }
            c.close()
            // Trim to max
            db.execSQL("DELETE FROM traffic_entries WHERE id NOT IN (SELECT id FROM traffic_entries ORDER BY ts DESC LIMIT $MAX_PERSISTED_LOGS)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load logs: ${e.message}")
        }
        return result
    }

    // ── Nové AI tabulky: dotazovací metody ─────────────────────────────

    data class KnownAddress(
        val id: Long,
        val address: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val occurrenceCount: Int,
        val avgIntervalSec: Double?,
        val avgEntropy: Double?,
        val typicalPort: Int?,
        val verdict: String,
        val verdictSource: String,
        val verdictConfidence: Double,
        val notifiedUser: Boolean,
        val notes: String?,
        val traceId: String?,
        val baselineEntropy: Double?,
        val baselineIntervalSec: Double?,
        val lastReverifyAt: Long?
    )

    data class PendingFlow(
        val id: Long,
        val address: String,
        val detectedAt: Long,
        val brainConfidence: Double?,
        val escalatedToLlm: Boolean,
        val expiresAt: Long,
        val reason: String,
        val sni: String?,
        val occurrenceCount: Int = 1
    )

    data class DailyStat(
        val date: String,
        val totalFlows: Int,
        val newAddresses: Int,
        val blockedCount: Int,
        val allowedCount: Int,
        val pendingCount: Int,
        val topEntropyAddress: String?
    )

    fun upsertKnownAddress(
        address: String,
        entropy: Double,
        port: Int,
        now: Long
    ) {
        try {
            val db = writableDatabase
            val existing = db.rawQuery(
                "SELECT id, occurrence_count, avg_interval_sec, avg_entropy, first_seen, " +
                "baseline_entropy, baseline_interval_sec FROM known_addresses WHERE address = ?",
                arrayOf(address)
            )
            if (existing.moveToFirst()) {
                val id = existing.getLong(0)
                val count = existing.getInt(1) + 1
                val oldAvgInterval = existing.getDouble(2)
                val oldAvgEntropy = existing.getDouble(3)
                val firstSeen = existing.getLong(4)
                val baselineEntropy = if (existing.isNull(5)) null else existing.getDouble(5)
                val baselineInterval = if (existing.isNull(6)) null else existing.getDouble(6)
                existing.close()

                // If first time seeing baseline, store it
                if (baselineEntropy == null) {
                    db.execSQL(
                        "UPDATE known_addresses SET baseline_entropy = ?, baseline_interval_sec = ? WHERE id = ?",
                        arrayOf<Any?>(entropy, 0.0, id)
                    )
                }

                // Moving average entropy
                val newAvgEntropy = oldAvgEntropy + (entropy - oldAvgEntropy) / count
                val elapsed = (now - firstSeen) / 1000.0
                val newAvgInterval = if (count > 1) elapsed / (count - 1) else oldAvgInterval

                db.execSQL(
                    """UPDATE known_addresses SET
                        last_seen = ?, occurrence_count = occurrence_count + 1,
                        avg_interval_sec = ?, avg_entropy = ?,
                        typical_port = CASE WHEN typical_port IS NULL THEN ? ELSE typical_port END
                     WHERE id = ?""",
                    arrayOf<Any?>(now, newAvgInterval, newAvgEntropy, port, id)
                )
            } else {
                existing.close()
                val cv = ContentValues().apply {
                    put("address", address)
                    put("first_seen", now)
                    put("last_seen", now)
                    put("occurrence_count", 1)
                    put("avg_entropy", entropy)
                    put("typical_port", port)
                    put("baseline_entropy", entropy)
                    put("baseline_interval_sec", 0.0)
                }
                db.insertWithOnConflict("known_addresses", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "upsertKnownAddress failed: ${e.message}")
        }
    }

    fun getAddressVerdict(address: String): String? {
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                "SELECT verdict FROM known_addresses WHERE address = ? AND verdict != 'unknown'",
                arrayOf(address)
            )
            val result = if (c.moveToFirst()) c.getString(0) else null
            c.close()
            result
        } catch (e: Exception) {
            Log.e(TAG, "getAddressVerdict failed: ${e.message}")
            null
        }
    }

    fun getAddressHistory(address: String): KnownAddress? {
        return try {
            val db = readableDatabase
            val c = db.rawQuery("SELECT * FROM known_addresses WHERE address = ?", arrayOf(address))
            if (c.moveToFirst()) {
                val ka = KnownAddress(
                    id = c.getLong(0),
                    address = c.getString(1),
                    firstSeen = c.getLong(2),
                    lastSeen = c.getLong(3),
                    occurrenceCount = c.getInt(4),
                    avgIntervalSec = if (c.isNull(5)) null else c.getDouble(5),
                    avgEntropy = if (c.isNull(6)) null else c.getDouble(6),
                    typicalPort = if (c.isNull(7)) null else c.getInt(7),
                    verdict = c.getString(8) ?: "unknown",
                    verdictSource = c.getString(9) ?: "ai_auto",
                    verdictConfidence = c.getDouble(10),
                    notifiedUser = c.getInt(11) != 0,
                    notes = c.getString(12),
                    traceId = c.getString(13),
                    baselineEntropy = if (c.isNull(14)) null else c.getDouble(14),
                    baselineIntervalSec = if (c.isNull(15)) null else c.getDouble(15),
                    lastReverifyAt = if (c.isNull(16)) null else c.getLong(16)
                )
                c.close()
                ka
            } else {
                c.close()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAddressHistory failed: ${e.message}")
            null
        }
    }

    fun setVerdict(address: String, verdict: String, source: String, confidence: Double, note: String?, traceId: String?) {
        try {
            val db = writableDatabase
            db.execSQL(
                """UPDATE known_addresses SET
                    verdict = ?, verdict_source = ?, verdict_confidence = ?,
                    notes = CASE WHEN ? IS NOT NULL THEN ? ELSE notes END,
                    trace_id = CASE WHEN ? IS NOT NULL THEN ? ELSE trace_id END
                 WHERE address = ?""",
                arrayOf<Any?>(verdict, source, confidence, note, note, traceId, traceId, address)
            )
        } catch (e: Exception) {
            Log.e(TAG, "setVerdict failed: ${e.message}")
        }
    }

    fun getPendingFlows(): List<PendingFlow> {
        val result = mutableListOf<PendingFlow>()
        try {
            val db = readableDatabase
            val now = System.currentTimeMillis()
            val c = db.rawQuery(
                """SELECT k.id, k.address, k.last_seen, k.avg_entropy, 0, ?,
                    CASE WHEN k.last_reverify_at IS NOT NULL THEN 'drift' ELSE 'low_confidence' END,
                    NULL, 1
                 FROM known_addresses k
                 WHERE k.verdict = 'unknown'
                 UNION
                 SELECT p.id, p.address, p.detected_at, p.brain_confidence, p.escalated_to_llm,
                    p.expires_at, p.reason, p.sni, p.occurrence_count
                 FROM pending_flows p
                 WHERE p.expires_at > ?
                 ORDER BY last_seen DESC""",
                arrayOf(now.toString(), now.toString())
            )
            if (c.moveToFirst()) {
                do {
                    result.add(PendingFlow(
                        id = c.getLong(0),
                        address = c.getString(1),
                        detectedAt = c.getLong(2),
                        brainConfidence = if (c.isNull(3)) null else c.getDouble(3),
                        escalatedToLlm = c.getInt(4) != 0,
                        expiresAt = c.getLong(5),
                        reason = c.getString(6) ?: "low_confidence",
                        sni = c.getString(7),
                        occurrenceCount = c.getInt(8)
                    ))
                } while (c.moveToNext())
            }
            c.close()
        } catch (e: Exception) {
            Log.e(TAG, "getPendingFlows failed: ${e.message}")
        }
        return result
    }

    fun upsertPendingFlow(address: String, confidence: Double, now: Long, ttlMs: Long = 300_000L, reason: String = "low_confidence", sni: String?) {
        try {
            val db = writableDatabase
            val expiresAt = now + ttlMs

            // 1. Existuje už aktivní záznam? Increment count
            db.execSQL(
                """UPDATE pending_flows SET
                    occurrence_count = occurrence_count + 1,
                    brain_confidence = ?,
                    detected_at = ?
                 WHERE address = ? AND expires_at > ?""",
                arrayOf<Any?>(confidence, now, address, now)
            )

            // 2. Pokud neexistuje, vlož nový (INSERT OR IGNORE + NOT EXISTS)
            db.execSQL(
                """INSERT OR IGNORE INTO pending_flows
                    (address, detected_at, brain_confidence, expires_at, reason, sni, occurrence_count)
                 SELECT ?, ?, ?, ?, ?, ?, 1
                 WHERE NOT EXISTS (
                    SELECT 1 FROM pending_flows WHERE address = ? AND expires_at > ?
                 )""",
                arrayOf<Any?>(address, now, confidence, expiresAt, reason, sni, address, now)
            )
        } catch (e: Exception) {
            Log.e(TAG, "upsertPendingFlow failed: ${e.message}")
        }
    }

    fun cleanupExpiredPending() {
        try {
            val db = writableDatabase
            val deleted = db.delete("pending_flows", "expires_at < ?", arrayOf(System.currentTimeMillis().toString()))
            if (deleted > 0) Log.d(TAG, "Cleaned up $deleted expired pending flows")
        } catch (e: Exception) {
            Log.e(TAG, "cleanupExpiredPending failed: ${e.message}")
        }
    }

    fun markEscalated(flowId: Long) {
        try {
            writableDatabase.execSQL(
                "UPDATE pending_flows SET escalated_to_llm = 1 WHERE id = ?",
                arrayOf(flowId)
            )
        } catch (e: Exception) {
            Log.e(TAG, "markEscalated failed: ${e.message}")
        }
    }

    fun getOrCreateDailyStat(date: String): DailyStat? {
        try {
            val db = writableDatabase
            val c = db.rawQuery("SELECT * FROM daily_stats WHERE date = ?", arrayOf(date))
            if (c.moveToFirst()) {
                val ds = DailyStat(
                    date = c.getString(0),
                    totalFlows = c.getInt(1),
                    newAddresses = c.getInt(2),
                    blockedCount = c.getInt(3),
                    allowedCount = c.getInt(4),
                    pendingCount = c.getInt(5),
                    topEntropyAddress = c.getString(6)
                )
                c.close()
                return ds
            }
            c.close()
            // Create new daily stat
            val cv = ContentValues().apply {
                put("date", date)
                put("total_flows", 0)
                put("new_addresses", 0)
                put("blocked_count", 0)
                put("allowed_count", 0)
                put("pending_count", 0)
            }
            db.insertWithOnConflict("daily_stats", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            return DailyStat(date, 0, 0, 0, 0, 0, null)
        } catch (e: Exception) {
            Log.e(TAG, "getOrCreateDailyStat failed: ${e.message}")
            return null
        }
    }

    fun incrementDailyStat(date: String, field: String) {
        try {
            writableDatabase.execSQL(
                "UPDATE daily_stats SET $field = $field + 1 WHERE date = ?",
                arrayOf(date)
            )
        } catch (e: Exception) {
            Log.e(TAG, "incrementDailyStat($field) failed: ${e.message}")
        }
    }

    fun getAddressesNeedingReverify(intervalDays: Int = 30, minOccurrences: Int = 10): List<KnownAddress> {
        val result = mutableListOf<KnownAddress>()
        try {
            val cutoff = System.currentTimeMillis() - (intervalDays * 24L * 60 * 60 * 1000)
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT * FROM known_addresses
                 WHERE verdict != 'unknown'
                   AND occurrence_count >= ?
                   AND (last_reverify_at IS NULL OR last_reverify_at < ?)
                 LIMIT 50""",
                arrayOf(minOccurrences.toString(), cutoff.toString())
            )
            if (c.moveToFirst()) {
                do {
                    result.add(KnownAddress(
                        id = c.getLong(0), address = c.getString(1),
                        firstSeen = c.getLong(2), lastSeen = c.getLong(3),
                        occurrenceCount = c.getInt(4),
                        avgIntervalSec = if (c.isNull(5)) null else c.getDouble(5),
                        avgEntropy = if (c.isNull(6)) null else c.getDouble(6),
                        typicalPort = if (c.isNull(7)) null else c.getInt(7),
                        verdict = c.getString(8) ?: "unknown",
                        verdictSource = c.getString(9) ?: "ai_auto",
                        verdictConfidence = c.getDouble(10),
                        notifiedUser = c.getInt(11) != 0,
                        notes = c.getString(12), traceId = c.getString(13),
                        baselineEntropy = if (c.isNull(14)) null else c.getDouble(14),
                        baselineIntervalSec = if (c.isNull(15)) null else c.getDouble(15),
                        lastReverifyAt = if (c.isNull(16)) null else c.getLong(16)
                    ))
                } while (c.moveToNext())
            }
            c.close()
        } catch (e: Exception) {
            Log.e(TAG, "getAddressesNeedingReverify failed: ${e.message}")
        }
        return result
    }

    fun updateReverifyTimestamp(address: String) {
        try {
            writableDatabase.execSQL(
                "UPDATE known_addresses SET last_reverify_at = ? WHERE address = ?",
                arrayOf<Any?>(System.currentTimeMillis(), address)
            )
        } catch (e: Exception) {
            Log.e(TAG, "updateReverifyTimestamp failed: ${e.message}")
        }
    }

    fun saveTrafficArray(name: String, data: LongArray) {
        try {
            val json = JSONArray()
            data.forEach { json.put(it) }
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("name", name)
                put("data", json.toString())
            }
            db.insertWithOnConflict("traffic_arrays", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save array $name: ${e.message}")
        }
    }

    fun loadTrafficArray(name: String, defaultSize: Int): LongArray {
        val arr = LongArray(defaultSize)
        try {
            val db = readableDatabase
            val c = db.rawQuery("SELECT data FROM traffic_arrays WHERE name = ?", arrayOf(name))
            if (c.moveToFirst()) {
                val jsonArray = JSONArray(c.getString(0))
                for (i in 0 until minOf(jsonArray.length(), defaultSize)) {
                    arr[i] = jsonArray.getLong(i)
                }
            }
            c.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load array $name: ${e.message}")
        }
        return arr
    }
}
