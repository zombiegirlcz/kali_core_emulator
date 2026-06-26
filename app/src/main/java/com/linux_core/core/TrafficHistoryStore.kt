package com.linux_core.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray

class TrafficHistoryStore(context: Context) : SQLiteOpenHelper(context, "traffic_intelligence.db", null, 2) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS traffic_entries (
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
                CREATE TABLE IF NOT EXISTS traffic_arrays (
                    name TEXT PRIMARY KEY,
                    data TEXT NOT NULL
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
            """, arrayOf(appName, ip, port, domain ?: "unknown", bytes, entropy, strategy, if (strategy > 0) 100 else 0))
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
                put("payload_hex", entry.payloadHex)
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
                            payloadHex = c.getString(c.getColumnIndexOrThrow("payload_hex")),
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
