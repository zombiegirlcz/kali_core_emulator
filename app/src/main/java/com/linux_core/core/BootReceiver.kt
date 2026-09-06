package com.linux_core.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-starts the app in the background after device boot (and after an app
 * update) so the PRoot guest + cron automation keep running without the user
 * having to open the app.
 *
 * Also schedules the midnight remote distro catalog refresh.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Log.i(TAG, "Received $action")

        // Schedule midnight refresh of remote distro scripts
        RemoteRootfsSyncReceiver.scheduleDaily(context)

        val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("boot_autostart", true)) {
            Log.i(TAG, "boot_autostart is disabled — skipping background boot")
            return
        }

        BackgroundBoot.start(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
