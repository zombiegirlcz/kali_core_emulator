package com.linux_core.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MitmTrafficStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "mitm_traffic.db",
    null,
    1
) {
    data class Record(
        val id: Long,
        val timestamp: Long,
        val sessionPort: Int,
        val host: String?,
        val direction: String,
        val method: String?,
        val path: String?,
        val status: Int?,
        val headers: Map<String, String>,
        val body: String,
        val bodySize: Int,
        val bodyTruncated: Boolean,
        val alpn: String?,
        val detail: String?
    )

    companion object {
        private const val TAG = "MitmTrafficStore"
        private const val MAX_ROWS = 5000
        private val instances = ConcurrentHashMap<Context, MitmTrafficStore>()

        fun get(context: Context): MitmTrafficStore {
            val app = context.applicationContext
            return instances.getOrPut(app) { MitmTrafficStore(app) }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE mitm_http (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                session_port INTEGER NOT NULL,
                host TEXT,
                direction TEXT NOT NULL,
                method TEXT,
                path TEXT,
                status INTEGER,
                headers TEXT,
                body TEXT,
                body_size INTEGER NOT NULL DEFAULT 0,
                body_truncated INTEGER NOT NULL DEFAULT 0,
                alpn TEXT,
                detail TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_mitm_ts ON mitm_http(ts DESC)")
        db.execSQL("CREATE INDEX idx_mitm_host ON mitm_http(host)")
        db.execSQL("CREATE INDEX idx_mitm_port ON mitm_http(session_port)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS mitm_http")
        onCreate(db)
    }

    fun logSessionEvent(sessionPort: Int, alpn: String?, detail: String) {
        insert(
            timestamp = System.currentTimeMillis(),
            sessionPort = sessionPort,
            host = null,
            direction = "EVENT",
            method = null,
            path = null,
            status = null,
            headers = emptyMap(),
            body = "",
            bodySize = 0,
            bodyTruncated = false,
            alpn = alpn,
            detail = detail
        )
    }

    fun logMessage(
        sessionPort: Int,
        host: String?,
        message: ParsedHttpMessage,
        alpn: String? = null
    ) {
        val direction = if (message.isRequest) "REQUEST" else "RESPONSE"
        val resolvedHost = host ?: message.headers["host"]?.substringBefore(':')
        val contentType = message.headers["content-type"]
        val bodyText = MitmHttpFormatter.bodyToDisplay(message.body, contentType)
        insert(
            timestamp = System.currentTimeMillis(),
            sessionPort = sessionPort,
            host = resolvedHost,
            direction = direction,
            method = message.method,
            path = message.path,
            status = message.status,
            headers = message.headers,
            body = bodyText,
            bodySize = message.body.size,
            bodyTruncated = message.bodyTruncated,
            alpn = alpn,
            detail = null
        )
    }

    private fun insert(
        timestamp: Long,
        sessionPort: Int,
        host: String?,
        direction: String,
        method: String?,
        path: String?,
        status: Int?,
        headers: Map<String, String>,
        body: String,
        bodySize: Int,
        bodyTruncated: Boolean,
        alpn: String?,
        detail: String?
    ) {
        try {
            val headersJson = JSONObject()
            headers.forEach { (k, v) -> headersJson.put(k, v) }
            val cv = ContentValues().apply {
                put("ts", timestamp)
                put("session_port", sessionPort)
                put("host", host)
                put("direction", direction)
                put("method", method)
                put("path", path)
                status?.let { put("status", it) }
                put("headers", headersJson.toString())
                put("body", body)
                put("body_size", bodySize)
                put("body_truncated", if (bodyTruncated) 1 else 0)
                put("alpn", alpn)
                put("detail", detail)
            }
            writableDatabase.insert("mitm_http", null, cv)
            trimOldRows()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert MITM record: ${e.message}")
        }
    }

    private fun trimOldRows() {
        try {
            writableDatabase.execSQL(
                "DELETE FROM mitm_http WHERE id NOT IN " +
                    "(SELECT id FROM mitm_http ORDER BY ts DESC LIMIT $MAX_ROWS)"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trim MITM rows: ${e.message}")
        }
    }

    fun query(
        limit: Int = 50,
        since: Long = 0,
        host: String? = null,
        grep: String? = null,
        sessionPort: Int? = null
    ): List<Record> {
        val result = mutableListOf<Record>()
        try {
            val where = StringBuilder("ts > ?")
            val args = mutableListOf(since.toString())
            if (!host.isNullOrBlank()) {
                where.append(" AND host LIKE ?")
                args.add("%$host%")
            }
            if (sessionPort != null) {
                where.append(" AND session_port = ?")
                args.add(sessionPort.toString())
            }
            if (!grep.isNullOrBlank()) {
                where.append(" AND (body LIKE ? OR path LIKE ? OR method LIKE ? OR headers LIKE ? OR detail LIKE ? OR host LIKE ?)")
                repeat(6) { args.add("%$grep%") }
            }
            val sql = "SELECT * FROM mitm_http WHERE $where ORDER BY ts DESC LIMIT ?"
            args.add(limit.toString())
            val c = readableDatabase.rawQuery(sql, args.toTypedArray())
            while (c.moveToNext()) {
                result.add(cursorToRecord(c))
            }
            c.close()
            result.reverse()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query MITM records: ${e.message}")
        }
        return result
    }

    fun queryForSessionSnapshots(activePorts: Set<Int>, perPort: Int = 10): List<Pair<Int, String>> {
        if (activePorts.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Int, String>>()
        for (port in activePorts) {
            val records = query(limit = perPort, sessionPort = port)
            if (records.isEmpty()) continue
            val sb = StringBuilder()
            for (r in records) {
                when (r.direction) {
                    "REQUEST" -> {
                        sb.append("[CLIENT->SERVER] ${r.method ?: "?"} ${r.host ?: ""}${r.path ?: ""}\n")
                        r.headers.forEach { (k, v) ->
                            if (k !in setOf("host", "connection", "accept", "accept-encoding")) {
                                sb.append("[CLIENT->SERVER] ${k.replaceFirstChar { it.uppercase() }}: $v\n")
                            }
                        }
                        if (r.body.isNotBlank()) {
                            sb.append("[CLIENT->SERVER] ${r.body.take(512)}\n")
                        }
                    }
                    "RESPONSE" -> {
                        sb.append("[SERVER->CLIENT] HTTP/1.1 ${r.status ?: "?"}\n")
                        r.headers.forEach { (k, v) ->
                            sb.append("[SERVER->CLIENT] ${k.replaceFirstChar { it.uppercase() }}: $v\n")
                        }
                        if (r.body.isNotBlank()) {
                            sb.append("[SERVER->CLIENT] ${r.body.take(512)}\n")
                        }
                    }
                    "EVENT" -> {
                        sb.append("[EVENT] ${r.detail ?: ""} ALPN=${r.alpn ?: "?"}\n")
                    }
                }
            }
            out.add(port to sb.toString().trimEnd())
        }
        return out
    }

    fun toJsonArray(records: List<Record>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (r in records) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("ts", r.timestamp)
                    put("session_port", r.sessionPort)
                    put("host", r.host)
                    put("direction", r.direction)
                    put("method", r.method)
                    put("path", r.path)
                    r.status?.let { put("status", it) }
                    put("headers", JSONObject(r.headers))
                    put("body", r.body)
                    put("body_size", r.bodySize)
                    put("body_truncated", r.bodyTruncated)
                    put("alpn", r.alpn)
                    put("detail", r.detail)
                }
            )
        }
        return arr
    }

    fun toPrettyText(records: List<Record>): String {
        if (records.isEmpty()) return ""
        val sb = StringBuilder()
        for (r in records) {
            when (r.direction) {
                "EVENT" -> {
                    sb.append("── ${r.detail ?: "session"}")
                    if (r.alpn != null) sb.append(" (ALPN=${r.alpn})")
                    sb.append(" ──\n")
                }
                "REQUEST" -> {
                    sb.append(MitmHttpFormatter.prettyLine(r)).append('\n')
                    r.headers.filterKeys { it !in setOf("host") }.forEach { (k, v) ->
                        sb.append("         ${k.replaceFirstChar { it.uppercase() }}: $v\n")
                    }
                    if (r.body.isNotBlank()) {
                        sb.append(formatBodyBlock(r.body, r.bodyTruncated)).append('\n')
                    }
                }
                "RESPONSE" -> {
                    sb.append(MitmHttpFormatter.prettyLine(r)).append('\n')
                    if (r.body.isNotBlank()) {
                        sb.append(formatBodyBlock(r.body, r.bodyTruncated)).append('\n')
                    }
                }
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun formatBodyBlock(body: String, truncated: Boolean): String {
        val lines = body.lines()
        val preview = if (lines.size > 40) {
            lines.take(40).joinToString("\n") + "\n         … (${lines.size - 40} more lines)"
        } else {
            body
        }
        val suffix = if (truncated) " [truncated]" else ""
        return preview.lines().joinToString("\n") { "         $it" } + suffix
    }

    private fun cursorToRecord(c: android.database.Cursor): Record {
        val headersJson = try {
            JSONObject(c.getString(c.getColumnIndexOrThrow("headers")) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val headers = mutableMapOf<String, String>()
        headersJson.keys().forEach { key ->
            headers[key] = headersJson.optString(key)
        }
        val statusIdx = c.getColumnIndexOrThrow("status")
        val status = if (c.isNull(statusIdx)) null else c.getInt(statusIdx)
        return Record(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            timestamp = c.getLong(c.getColumnIndexOrThrow("ts")),
            sessionPort = c.getInt(c.getColumnIndexOrThrow("session_port")),
            host = c.getString(c.getColumnIndexOrThrow("host")),
            direction = c.getString(c.getColumnIndexOrThrow("direction")) ?: "EVENT",
            method = c.getString(c.getColumnIndexOrThrow("method")),
            path = c.getString(c.getColumnIndexOrThrow("path")),
            status = status,
            headers = headers,
            body = c.getString(c.getColumnIndexOrThrow("body")) ?: "",
            bodySize = c.getInt(c.getColumnIndexOrThrow("body_size")),
            bodyTruncated = c.getInt(c.getColumnIndexOrThrow("body_truncated")) == 1,
            alpn = c.getString(c.getColumnIndexOrThrow("alpn")),
            detail = c.getString(c.getColumnIndexOrThrow("detail"))
        )
    }
}
