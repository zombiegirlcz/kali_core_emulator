package com.linux_core.shizuku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Odinstalace appky → smazat její Shizuku grant (`shizuku_permissions`), aby
 * zmizela ze správce a po reinstalaci nezdědila staré oprávnění.
 * `PACKAGE_FULLY_REMOVED` = odinstalace bez `-k` (ne update); patří mezi
 * výjimky implicitních broadcastů, takže funguje z manifestu.
 */
class ShizukuPackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(pkg)) return
        prefs.edit().remove(pkg).apply()
        Log.i("ShizukuPkgRemoved", "grant odebrán: $pkg (odinstalováno)")
    }

    companion object {
        const val PREFS = "shizuku_permissions"
    }
}
