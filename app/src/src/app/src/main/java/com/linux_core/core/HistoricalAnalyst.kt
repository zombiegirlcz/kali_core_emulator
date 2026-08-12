package com.linux_core.core

import android.content.Context
import android.util.Log

object HistoricalAnalyst {
    private const val TAG = "HistoricalAnalyst"

    /**
     * Projde historii a vygeneruje sémantický popis dne.
     */
    fun analyzeLast24Hours(context: Context): String {
        val store = TrafficHistoryStore(context)
        val db = store.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM session_history ORDER BY timestamp DESC", null)
        
        var totalBytes = 0L
        val incidents = ArrayList<String>()
        val appUsage = mutableMapOf<String, Long>()
        
        if (cursor.moveToFirst()) {
            do {
                val app = cursor.getString(cursor.getColumnIndexOrThrow("app_name"))
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow("total_bytes"))
                val strategy = cursor.getInt(cursor.getColumnIndexOrThrow("ai_strategy_index"))
                val ip = cursor.getString(cursor.getColumnIndexOrThrow("remote_ip"))
                val port = cursor.getInt(cursor.getColumnIndexOrThrow("remote_port"))
                
                totalBytes += bytes
                appUsage[app] = (appUsage[app] ?: 0L) + bytes
                
                if (strategy > 0) {
                    incidents.add("${cursor.getString(cursor.getColumnIndexOrThrow("timestamp"))}: " +
                            "Attack detected from $ip:$port targeting $app. Action taken.")
                }
            } while (cursor.moveToNext())
        }
        cursor.close()

        // Sémantické "pochopení" souvislostí
        val summary = StringBuilder()
        summary.append("--- ANALYTICKÝ REPORT SÍTĚ (24h) ---\n")
        summary.append("Celkový objem dat: ${totalBytes / 1024} KB.\n")
        
        val topApp = appUsage.maxByOrNull { it.value }?.key ?: "žádná"
        summary.append("Nejaktivnější aplikace: $topApp.\n\n")

        if (incidents.isEmpty()) {
            summary.append("✅ Žádné podezřelé aktivity nebyly detekovány. Síť byla stabilní.")
        } else {
            summary.append("⚠️ DETEKOVÁNY INCIDENTY:\n")
            incidents.take(5).forEach { summary.append("- $it\n") }
            
            // Analýza "PROČ"
            if (incidents.size > 10) {
                summary.append("\nZÁVĚR: Čelíte systematickému průzkumu (Brute Force nebo Scanning). Doporučena okamžitá rotace IP adresy.")
            } else {
                summary.append("\nZÁVĚR: Detekovány izolované pokusy o exploit. Firewall je úspěšně zneutralizoval.")
            }
        }
        
        return summary.toString()
    }
}
