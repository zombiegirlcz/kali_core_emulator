package com.linux_core.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Watchdog — periodicke buzeni pres AlarmManager (exact + allow-while-idle).
 *
 * Proc: START_STICKY resi restart po LMK killu jen castecne a OEM killery
 * (MIUI/HyperOS "clean all") ho casteji ignoruji. Alarm pretrva LMK i doze
 * (setExactAndAllowWhileIdle se spusti v maintenance window); po force-stop
 * od uzivatele se zrusi systemem — proti tomu obrana neni (a nema byt).
 *
 * Chovani: kazdy tick zkontroluje, zda ma bezet background (cron) session
 * (boot_autostart pref). Pokud service neběží nebo nemá žádné sessiony →
 * startForegroundService + BackgroundBoot.start(). Pak naplánuje další tick
 * (self-rescheduling — jediný alarm v systému, žádný repeating drift).
 *
 * Plánování: TerminalService.onStartCommand (service alive → alarm běží),
 * BootReceiver cestu kryje BackgroundBoot; stopAll() alarm ruší (uživatel
 * explicitně ukončil → watchdog nesmí oživovat).
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Naplánovat další tick hned — ať tick nikdy nezapomeneme obnovit.
        schedule(context)

        val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("boot_autostart", true)) {
            Log.i(TAG, "boot_autostart off — watchdog idle")
            return
        }

        if (TerminalService.isAliveWithSessions()) {
            Log.d(TAG, "Service alive with sessions — OK")
            return
        }

        Log.w(TAG, "Watchdog: service/session dead — reviving")
        try {
            val svc = Intent(context, TerminalService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog: service start failed: ${e.message}")
        }
        BackgroundBoot.start(context)
    }

    companion object {
        private const val TAG = "Watchdog"
        private const val ACTION_TICK = "com.linux_core.WATCHDOG_TICK"
        private const val REQUEST_CODE = 1001

        /** Interval tikání — 10 minut. Kratší = častější buzení = vyšší spotřeba
         *  bez reálného přínosu (cron joby jsou minutové). */
        private const val INTERVAL_MS = 10L * 60_000L

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                // allowWhileIdle: probouzí i v Doze (maintenance window);
                // exact: OEM úsporné režimy ho neposouvají na horizont hodin.
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + INTERVAL_MS,
                    pi
                )
            } catch (e: Exception) {
                // SecurityException na některých OEM ROM bez exact alarm práva —
                // fallback na inexact variantu (stále lepší než nic).
                try {
                    am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + INTERVAL_MS,
                        pi
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "Watchdog schedule failed: ${e2.message}")
                }
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
            Log.i(TAG, "Watchdog cancelled (user exit)")
        }
    }
}
