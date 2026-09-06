package com.linux_core.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Refreshes the remote distro catalog from zombiegirlcz/ROOTFS-for-proot
 * at midnight and auto-pulls any new distros that aren't installed yet.
 *
 * If the network is down, schedules a retry in 15 minutes.
 */
class RemoteRootfsSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Remote rootfs sync triggered — fetching catalog only (no auto-pull)")

        Thread {
            try {
                val scripts = RemoteRootfsCatalog.fetchDistroScriptsSync(context, forceRefresh = true)
                Log.i(TAG, "Catalog refreshed: ${scripts.size} distro scripts")
            } catch (e: Exception) {
                Log.w(TAG, "Catalog fetch failed, scheduling retry: ${e.message}")
                scheduleRetry(context)
            }
        }.start()
    }

    companion object {
        private const val TAG = "RemoteRootfsSync"
        private const val REQ_SYNC = 7777
        private const val REQ_RETRY = 7778

        fun scheduleDaily(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
            val intent = Intent(context, RemoteRootfsSyncReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, REQ_SYNC, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Next midnight UTC
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }

            am.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                cal.timeInMillis,
                pi
            )
            Log.i(TAG, "Daily sync scheduled for ${java.util.Date(cal.timeInMillis)}")
        }

        fun scheduleRetry(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
            val intent = Intent(context, RemoteRootfsSyncReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, REQ_RETRY, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = android.os.SystemClock.elapsedRealtime() + 15 * 60 * 1000L
            am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            Log.i(TAG, "Retry scheduled in 15 min")
        }
    }
}
