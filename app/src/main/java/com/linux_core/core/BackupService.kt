package com.linux_core.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.linux_core.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BackupService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backupJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BACKUP -> startBackup(intent)
            ACTION_STOP_BACKUP -> stopBackup()
        }
        return START_NOT_STICKY
    }

    private fun startBackup(intent: Intent) {
        val distroId = intent.getStringExtra(EXTRA_DISTRO_ID) ?: return
        val distro = RootfsManager.DISTROS.find { it.id == distroId } ?: return

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing backup…", 0))

        backupJob?.cancel()
        backupJob = serviceScope.launch {
            try {
                RootfsManager.backupRootfs(this@BackupService, distro).collect { (progress, status) ->
                    updateNotification(status, progress)
                }
                updateNotification("Backup complete!", 100)
            } catch (e: Exception) {
                Log.e("BackupService", "Backup failed: ${e.message}", e)
                updateNotification("Backup failed: ${e.message}", 0)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopBackup() {
        backupJob?.cancel()
        backupJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(status: String, progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status, progress))
    }

    private fun buildNotification(status: String, progress: Int): android.app.Notification {
        val cancelIntent = Intent(this, BackupService::class.java).apply {
            action = ACTION_STOP_BACKUP
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Backing up rootfs…")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(progress < 100)
            .setProgress(100, progress, false)
            .addAction(android.R.drawable.ic_media_pause, "Cancel", cancelPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Rootfs Backup",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress of rootfs backup operations"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START_BACKUP = "com.linux_core.START_BACKUP"
        const val ACTION_STOP_BACKUP = "com.linux_core.STOP_BACKUP"
        const val EXTRA_DISTRO_ID = "extra_distro_id"
        const val CHANNEL_ID = "backup_channel"
        const val NOTIFICATION_ID = 13342

        fun startBackup(context: Context, distroId: String) {
            val intent = Intent(context, BackupService::class.java).apply {
                action = ACTION_START_BACKUP
                putExtra(EXTRA_DISTRO_ID, distroId)
            }
            context.startForegroundService(intent)
        }

        fun stopBackup(context: Context) {
            val intent = Intent(context, BackupService::class.java).apply {
                action = ACTION_STOP_BACKUP
            }
            context.startService(intent)
        }
    }
}
