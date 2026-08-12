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
 * Requires RECEIVE_BOOT_COMPLETED. Controlled by the "boot_autostart" pref
 * (default ON, toggle in MainActivity). The actual boot work runs on a
 * background thread inside BackgroundBoot.
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
