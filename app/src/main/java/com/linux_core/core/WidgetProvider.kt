package com.linux_core.core

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.linux_core.R
import com.linux_core.ui.terminal.TerminalActivity

class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Refresh all widgets when receiving a trigger broadcast
        if (intent.action == ACTION_REFRESH_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, WidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.linux_core.REFRESH_WIDGET"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val sessionCount = TerminalService.getActiveSessionCount()
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            views.setTextViewText(R.id.widget_status, "Active Sessions: $sessionCount")

            // Setup Kali launch Intent
            val kaliIntent = Intent(context, TerminalActivity::class.java).apply {
                putExtra("rootfsDirName", "kali-arm64")
                putExtra("mountStorage", false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val kaliPending = PendingIntent.getActivity(context, 101, kaliIntent, pendingFlag)
            views.setOnClickPendingIntent(R.id.btn_launch_kali, kaliPending)

            // Setup Parrot launch Intent
            val parrotIntent = Intent(context, TerminalActivity::class.java).apply {
                putExtra("rootfsDirName", "parrot-arm64")
                putExtra("mountStorage", false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val parrotPending = PendingIntent.getActivity(context, 102, parrotIntent, pendingFlag)
            views.setOnClickPendingIntent(R.id.btn_launch_parrot, parrotPending)

            // Instruct the widget manager to update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun triggerUpdate(context: Context) {
            val intent = Intent(context, WidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }
            context.sendBroadcast(intent)
        }
    }
}
