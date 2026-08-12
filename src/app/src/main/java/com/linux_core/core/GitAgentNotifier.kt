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

        // Otevře aplikaci na tap — žádný git-agent exekuce (receiver byl smazán
        // 2026-08-09: interaktivní notifikace = jen informace + otevření app).
        val appIntent = Intent(context, com.linux_core.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("git_repo", repoPath)
            putExtra("git_branch", branch)
            putExtra("git_commit", commitMsg)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            repoId.hashCode(),
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shortMsg = commitMsg.take(60) + if (commitMsg.length > 60) "..." else ""

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("✅ Git: ${repoPath.split("/").last()}")
            .setContentText("$shortMsg — tap to open")
            .setContentIntent(contentIntent)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$shortMsg\n\nRepo: $repoPath\nBranch: $branch"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 250, 100, 250))
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(CHANNEL_ID, notificationId, notification)
        Log.i(TAG, "Git notification posted: repo=$repoPath id=$notificationId")
    }

    fun cancel(context: Context, notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(CHANNEL_ID, notificationId)
    }
}
