package com.linux_core.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class TrafficHistoryStore(context: Context) : SQLiteOpenHelper(context, "traffic_intelligence.db", null, 1) {
    
    override fun onCreate(db: SQLiteDatabase) {
        // Tabulka pro 24h analytické záznamy
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun logSession(appName: String, ip: String, port: Int, domain: String?, bytes: Long, entropy: Float, strategy: Int) {
        try {
            val db = writableDatabase
            db.execSQL("""
                INSERT INTO session_history (app_name, remote_ip, remote_port, domain, total_bytes, max_entropy, ai_strategy_index, risk_score)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, arrayOf(appName, ip, port, domain ?: "unknown", bytes, entropy, strategy, if(strategy > 0) 100 else 0))
            
            // Automatické promazávání starších dat než 24 hodin
            db.execSQL("DELETE FROM session_history WHERE timestamp < datetime('now', '-1 day')")
        } catch (e: Exception) {
            Log.e("HistoryStore", "Failed to log session: ${e.message}")
        }
    }
}
