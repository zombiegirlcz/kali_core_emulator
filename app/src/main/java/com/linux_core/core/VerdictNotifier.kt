package com.linux_core.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Zobrazí Android notifikaci s Allow/Deny tlačítky pro nerozhodnutý síťový flow.
 *
 * Voláno z [VerdictEngine] nebo [LocalApiServer].
 * [VerdictReceiver] zachytí kliknutí a zapíše verdikt přes [TrafficAggregator].
 *
 * Režimy (klíč `verdict_notify_mode` v SharedPreferences):
 *   - `notification` (default) — zobrazí notifikaci, 30s timeout → auto Deny
 *   - `silent_auto` — žádná notifikace, vše nechá na AI
 */
object VerdictNotifier {
    private const val TAG = "VerdictNotifier"
    private const val CHANNEL_ID = "verdict_decisions"
    private const val TIMEOUT_MS = 30_000L
    const val ACTION_ALLOW = "com.linux_core.action.VERDICT_ALLOW"
    const val ACTION_DENY = "com.linux_core.action.VERDICT_DENY"

    private val handler = Handler(Looper.getMainLooper())
    private val timeoutTasks = ConcurrentHashMap<String, Runnable>()

    /**
     * Zobrazí notifikaci s dotazem na uživatele.
     *
     * @param address IP adresa k rozhodnutí
     * @param question Text dotazu (např. "Allow connection to 185.220.101.4:443?")
     * @param confidence AI confidence (zobrazí se v notifikaci)
     * @param context Android Context
     */
    fun notify(
        address: String,
        question: String,
        confidence: Double,
        context: Context
    ) {
        val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        val mode = prefs.getString("verdict_notify_mode", "notification") ?: "notification"

        if (mode == "silent_auto") {
            // Tichý režim — nechat na AI, neotravovat uživatele
            Log.d(TAG, "Silent mode — skipping notification for $address")
            return
        }

        createChannel(context)

        val notificationId = address.hashCode().and(0x7FFFFFFF) // ensure positive
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // ── Allow intent ──────────────────────────────────────────
        val allowIntent = Intent(context, VerdictReceiver::class.java).apply {
            action = ACTION_ALLOW
            putExtra("address", address)
            putExtra("notification_id", notificationId)
        }
        val allowPending = PendingIntent.getBroadcast(
            context, notificationId, allowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Deny intent ───────────────────────────────────────────
        val denyIntent = Intent(context, VerdictReceiver::class.java).apply {
            action = ACTION_DENY
            putExtra("address", address)
            putExtra("notification_id", notificationId)
        }
        val denyPending = PendingIntent.getBroadcast(
            context, notificationId + 1, denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ Uncertain network flow")
            .setContentText(question)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$question\nAI confidence: ${"%.0f".format(confidence * 100)}%"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(TIMEOUT_MS)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Deny", denyPending)
            .addAction(android.R.drawable.ic_menu_compass, "Allow", allowPending)
            .setAutoCancel(true)
            .build()

        manager.notify("verdict", notificationId, notification)

        // ── Auto-deny timeout ─────────────────────────────────────
        val timeoutTask = Runnable {
            Log.w(TAG, "Verdict timeout for $address — auto-denying")
            TrafficAggregator.getInstance()?.setVerdict(
                address = address,
                verdict = "blocked",
                source = "timeout",
                confidence = 0.0,
                note = "Auto-deny after 30s timeout"
            )
            manager.cancel("verdict", notificationId)
        }
        timeoutTasks[address] = timeoutTask
        handler.postDelayed(timeoutTask, TIMEOUT_MS)
    }

    /**
     * Zruší timeout task pro adresu (volá se z [VerdictReceiver] při kliknutí).
     */
    fun cancelTimeout(address: String) {
        timeoutTasks.remove(address)?.let { handler.removeCallbacks(it) }
    }

    private fun createChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Network Verdict Decisions",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "User confirmation required for uncertain network flows"
                    setShowBadge(true)
                    enableVibration(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}

/**
 * BroadcastReceiver pro Allow/Deny akce z [VerdictNotifier] notifikace.
 *
 * Registrován v AndroidManifest.xml jako exported=false.
 * Zpracovává dvě akce:
 *   - [VerdictNotifier.ACTION_ALLOW] → verdict='allowed'
 *   - [VerdictNotifier.ACTION_DENY]  → verdict='blocked'
 */
class VerdictReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra("address") ?: return
        val notificationId = intent.getIntExtra("notification_id", 0)
        val isAllow = intent.action == VerdictNotifier.ACTION_ALLOW

        // Zrušit timeout task
        VerdictNotifier.cancelTimeout(address)

        val verdict = if (isAllow) "allowed" else "blocked"
        Log.i(TAG, "User verdict: $verdict for $address")

        TrafficAggregator.getInstance()?.setVerdict(
            address = address,
            verdict = verdict,
            source = "user_confirmed",
            confidence = 1.0,
            note = if (isAllow) "Approved by user" else "Denied by user"
        )

        // Dismiss the notification
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel("verdict", notificationId)
    }

    companion object {
        private const val TAG = "VerdictReceiver"
    }
}
