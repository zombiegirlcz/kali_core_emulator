package com.linux_core.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class OffensiveActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actionId = intent.getIntExtra("action_id", -1)
        val allowed = intent.getBooleanExtra("allowed", false)
        if (actionId != -1) {
            OffensiveEngine.pendingActions[actionId]?.complete(allowed)
            OffensiveEngine.pendingActions.remove(actionId)
        }
        // Dismiss the notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel("offensive_action", actionId)
    }
}
