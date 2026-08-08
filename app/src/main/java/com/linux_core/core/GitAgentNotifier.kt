package com.linux_core.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import android.util.Log

object GitAgentNotifier {
    private const val TAG = "GitAgentNotifier"
    private const val CHANNEL_ID = "git_agent_actions"
    private const val CHANNEL_NAME = "Git Agent Actions"
    private const val CHANNEL_DESC = "Interactive notifications for git-agent actions (push/merge/none)"

    fun init(context: Context) {
        createChannel(context)
    }

    private fun createChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, android.app.NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = CHANNEL_DESC
                        setShowBadge(true)
                        enableVibration(true)
                        enableLights(true)
                    }
                manager.createNotificationChannel(channel)
                Log.i(TAG, "Created notification channel: $CHANNEL_ID")
            }
        }
    }

    fun showInteractiveNotification(
        context: Context,
        repoId: String,
        repoPath: String,
        branch: String,
        commitMsg: String,
        notificationId: Int
    ) {
        init(context)

        // PUSH action
        val pushIntent = Intent(context, GitAgentActionReceiver::class.java).apply {
            putExtra("repo_id", repoId)
            putExtra("action", "push")
            putExtra("repo_path", repoPath)
            putExtra("branch", branch)
        }
        val pushPendingIntent = PendingIntent.getBroadcast(
            context,
            repoId.hashCode() + 1,
            pushIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // MERGE action
        val mergeIntent = Intent(context, GitAgentActionReceiver::class.java).apply {
            putExtra("repo_id", repoId)
            putExtra("action", "merge")
            putExtra("repo_path", repoPath)
            putExtra("branch", branch)
        }
        val mergePendingIntent = PendingIntent.getBroadcast(
            context,
            repoId.hashCode() + 2,
            mergeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // NONE action
        val noneIntent = Intent(context, GitAgentActionReceiver::class.java).apply {
            putExtra("repo_id", repoId)
            putExtra("action", "none")
            putExtra("repo_path", repoPath)
            putExtra("branch", branch)
        }
        val nonePendingIntent = PendingIntent.getBroadcast(
            context,
            repoId.hashCode() + 3,
            noneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shortMsg = commitMsg.take(60) + if (commitMsg.length > 60) "..." else ""

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("✅ Git: ${repoPath.split("/").last()}")
            .setContentText("$shortMsg — tap action below")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$shortMsg\n\nRepo: $repoPath\nBranch: $branch\n\nChoose action:"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 250, 100, 250))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "NONE", nonePendingIntent)
            .addAction(android.R.drawable.ic_menu_compass, "MERGE", mergePendingIntent)
            .addAction(android.R.drawable.ic_menu_save, "PUSH", pushPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(CHANNEL_ID, notificationId, notification)
        Log.i(TAG, "Interactive notification posted: repo=$repoPath action=$notificationId")
    }

    fun cancel(context: Context, notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(CHANNEL_ID, notificationId)
    }
}
