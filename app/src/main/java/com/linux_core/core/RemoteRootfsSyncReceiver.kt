package com.linux_core.core

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
        Log.i(TAG, "Remote rootfs sync triggered")

        Thread {
            try {
                val scripts = RemoteRootfsCatalog.fetchDistroScriptsSync(context, forceRefresh = true)
                Log.i(TAG, "Catalog refreshed: ${scripts.size} distro scripts")

                var pulledAny = false
                for (entry in scripts) {
                    val targetDir = File(context.filesDir, "${RootfsManager.NH_DISTRO_DIR}/docker/${entry.slug}")
                    if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
                        Log.d(TAG, "Skip ${entry.distroName} — already pulled")
                        continue
                    }

                    Log.i(TAG, "Auto-pulling ${entry.distroName} (${entry.tarballUrl})")
                    try {
                        runBlocking {
                            RootfsManager.pullRootfsFromUrl(context, entry.tarballUrl).collect { (progress, status) ->
                                Log.d(TAG, "Auto-pull ${entry.distroName}: $progress% — $status")
                            }
                        }

                        // Write bootstrap.sh from the script's heredoc
                        if (entry.bootstrapScript.isNotEmpty()) {
                            val bootstrapFile = File(targetDir, "bootstrap.sh")
                            if (!bootstrapFile.exists()) {
                                bootstrapFile.writeText(entry.bootstrapScript)
                                bootstrapFile.setExecutable(true, false)
                            }
                        }

                        // Write entrypoint.sh if not present
                        val entrypointFile = File(targetDir, "root/entrypoint.sh")
                        if (!entrypointFile.exists()) {
                            entrypointFile.parentFile?.mkdirs()
                            entrypointFile.writeText("#!/bin/sh\nexec /bin/bash --login\n")
                            entrypointFile.setExecutable(true, false)
                        }

                        // Write .docker_image marker
                        File(targetDir, ".docker_image").writeText(
                            "image=${entry.scriptName}\n" +
                            "pulled_at=${System.currentTimeMillis()}\n" +
                            "source=remote-script\n" +
                            "script=${entry.scriptName}\n"
                        )

                        Log.i(TAG, "Auto-pull complete: ${entry.distroName}")
                        pulledAny = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Auto-pull failed for ${entry.distroName}: ${e.message}")
                    }
                }

                if (pulledAny) {
                    Log.i(TAG, "Some distros were auto-pulled — will appear in Docker window on next launch")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Catalog refresh failed, scheduling retry: ${e.message}")
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
