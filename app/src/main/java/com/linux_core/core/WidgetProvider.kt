package com.linux_core.core

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.os.Binder
import android.os.Process
import com.linux_core.R
import com.linux_core.core.TerminalService
import com.linux_core.core.VpnCaptureService
import com.linux_core.ui.terminal.TerminalActivity
import java.io.File

class WidgetProvider : AppWidgetProvider() {

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val stopIntent = Intent(context, WidgetRenderService::class.java).apply {
            action = WidgetRenderService.ACTION_STOP
        }
        context.startService(stopIntent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH_WIDGET -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, WidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                onUpdate(context, appWidgetManager, appWidgetIds)
            }
            ACTION_CELL_CLICK -> {
                if (!isCallerSelf(context)) return
                val cellIndex = intent.getIntExtra(EXTRA_CELL_INDEX, -1)
                val cell = notebookCells.getOrNull(cellIndex)
                val scriptCmd = cell?.command ?: ""
                Log.d("WidgetProvider", "Cell clicked: index=$cellIndex, cmd=$scriptCmd")
                executeScriptInTerminal(context, cellIndex, scriptCmd)
            }
            ACTION_ARROW_CLICK -> {
                if (!isCallerSelf(context)) return
                val direction = intent.getIntExtra(EXTRA_ARROW_DIR, 0)
                Log.d("WidgetProvider", "Arrow clicked: direction=$direction")
                val renderIntent = Intent(context, WidgetRenderService::class.java).apply {
                    action = WidgetRenderService.ACTION_ROTATE
                    putExtra(WidgetRenderService.EXTRA_DIRECTION, direction)
                }
                context.startService(renderIntent)
                triggerUpdate(context)
            }
            ACTION_ACTION_CLICK -> {
                if (!isCallerSelf(context)) return
                val actionType = intent.getStringExtra(EXTRA_ACTION_TYPE) ?: ""
                Log.d("WidgetProvider", "Action clicked: $actionType")
                executeAction(context, actionType)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.linux_core.REFRESH_WIDGET"
        const val ACTION_CELL_CLICK = "com.linux_core.CELL_CLICK"
        const val ACTION_ARROW_CLICK = "com.linux_core.ARROW_CLICK"
        const val ACTION_ACTION_CLICK = "com.linux_core.ACTION_CLICK"
        const val EXTRA_CELL_INDEX = "cell_index"
        const val EXTRA_ARROW_DIR = "arrow_dir"
        const val EXTRA_ACTION_TYPE = "action_type"
        const val EXTRA_SCRIPT_CMD = "script_cmd"

        val notebookCells: MutableList<NotebookCellData> = mutableListOf()

        private val defaultCells = listOf(
            "SÍŤ" to "ip addr show",
            "VPN" to "curl -s http://127.0.0.1:1337/vpn",
            "LOGY" to "tail -n 10 /var/log/syslog 2>/dev/null || echo 'no syslog'",
            "SKEN" to "nmap -sn 192.168.1.0/24 2>/dev/null || echo 'nmap not found'"
        )

        fun loadNotebookCells(context: Context) {
            notebookCells.clear()
            var loaded = false
            for (rootfsName in listOf("nh/distro/kali", "nh/distro/parrot")) {
                val confFile = File(context.filesDir, "$rootfsName/root/notebook_scripts.conf")
                if (confFile.exists()) {
                    try {
                        confFile.readLines().forEach { line ->
                            val eq = line.indexOf('=')
                            if (eq > 0) {
                                val name = line.substring(0, eq).trim()
                                val cmd = line.substring(eq + 1).trim()
                                if (name.isNotEmpty() && cmd.isNotEmpty()) {
                                    notebookCells.add(NotebookCellData(name, cmd))
                                }
                            }
                        }
                        loaded = true
                        Log.d("WidgetProvider", "Loaded ${notebookCells.size} cells from ${confFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.w("WidgetProvider", "Failed to read notebook config: ${e.message}")
                    }
                    break
                }
            }
            if (!loaded) {
                defaultCells.forEach { (name, cmd) ->
                    notebookCells.add(NotebookCellData(name, cmd))
                }
                Log.d("WidgetProvider", "Using default ${notebookCells.size} cells")
            }
        }

        data class NotebookCellData(
            val name: String,
            val command: String,
            var lastOutput: String = "",
            var lastRunTime: Long = 0L
        )

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val sessionCount = TerminalService.getActiveSessionCount()
            Log.d("WidgetProvider", "updateAppWidget called for id=$appWidgetId, sessions=$sessionCount")
            loadNotebookCells(context)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Zajisti, že WidgetRenderService běží (pro cube face rendering)
            val renderServiceIntent = Intent(context, WidgetRenderService::class.java).apply {
                action = WidgetRenderService.ACTION_START
            }
            context.startService(renderServiceIntent)

            views.setTextViewText(R.id.widget_status, "● $sessionCount aktivních session")

            // 4 notebook cells
            for (i in 0 until 4) {
                val cell = notebookCells.getOrNull(i) ?: continue
                val cellViewId = when (i) {
                    0 -> R.id.cell_1
                    1 -> R.id.cell_2
                    2 -> R.id.cell_3
                    3 -> R.id.cell_4
                    else -> continue
                }
                views.setTextViewText(cellViewId, "${cell.name}: ${cell.command.take(25)}...")
                views.setTextColor(cellViewId, 0xFF00FF41.toInt())
                views.setOnClickPendingIntent(cellViewId, createCellClickIntent(context, i))
            }

            // Arrows
            views.setOnClickPendingIntent(R.id.arrow_prev, createArrowIntent(context, -1))
            views.setOnClickPendingIntent(R.id.arrow_next, createArrowIntent(context, +1))

            // Action buttons
            views.setTextViewText(R.id.action_btn_1, "VPN ON/OFF")
            views.setOnClickPendingIntent(R.id.action_btn_1, createActionIntent(context, "vpn_toggle"))

            views.setTextViewText(R.id.action_btn_2, "BOOT KALI")
            views.setOnClickPendingIntent(R.id.action_btn_2, createActionIntent(context, "kali"))

            views.setTextViewText(R.id.action_btn_3, "BOOT PARROT")
            views.setOnClickPendingIntent(R.id.action_btn_3, createActionIntent(context, "parrot"))

            views.setTextViewText(R.id.action_btn_4, "STOP ALL")
            views.setOnClickPendingIntent(R.id.action_btn_4, createActionIntent(context, "stop_all"))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun triggerUpdate(context: Context) {
            val intent = Intent(context, WidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }
            context.sendBroadcast(intent)
        }

        private fun createCellClickIntent(context: Context, cellIndex: Int): PendingIntent {
            val intent = Intent(context, WidgetProvider::class.java)
            intent.action = ACTION_CELL_CLICK
            intent.putExtra(EXTRA_CELL_INDEX, cellIndex)
            return PendingIntent.getBroadcast(context, 200 + cellIndex, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun createArrowIntent(context: Context, direction: Int): PendingIntent {
            val intent = Intent(context, WidgetProvider::class.java)
            intent.action = ACTION_ARROW_CLICK
            intent.putExtra(EXTRA_ARROW_DIR, direction)
            return PendingIntent.getBroadcast(context, 100 + if (direction > 0) 1 else 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun createActionIntent(context: Context, actionType: String): PendingIntent {
            val intent = Intent(context, WidgetProvider::class.java)
            intent.action = ACTION_ACTION_CLICK
            intent.putExtra(EXTRA_ACTION_TYPE, actionType)
            return PendingIntent.getBroadcast(context, 300 + actionType.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun isCallerSelf(context: Context): Boolean {
            val callingUid = Binder.getCallingUid()
            return callingUid == Process.myUid() || callingUid == context.applicationInfo.uid
        }

        private fun startTerminalWithRootfs(context: Context, rootfsDirName: String) {
            val intent = Intent(context, TerminalActivity::class.java)
            intent.putExtra("rootfsDirName", rootfsDirName)
            intent.putExtra("mountStorage", false)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        }

        private fun sendVpnCommand(context: Context, action: String) {
            val vpnIntent = Intent(context, VpnCaptureService::class.java).apply { this.action = action }
            context.startService(vpnIntent)
        }

        private fun executeScriptInTerminal(context: Context, cellIndex: Int, script: String) {
            if (TerminalService.getActiveSessionCount() > 1) {
                Log.w("WidgetProvider", "Multiple sessions active — targeting first session. " +
                    "Notebook scripts may run on wrong distro.")
            }
            val session = TerminalService.sessions.firstOrNull()
            if (session != null && script.isNotBlank()) {
                session.write("$script\n")
                notebookCells.getOrNull(cellIndex)?.let { cell ->
                    cell.lastRunTime = System.currentTimeMillis()
                }
                Log.d("WidgetProvider", "Script sent to terminal: $script")
            } else {
                Log.w("WidgetProvider", "No active terminal session — cannot execute script")
            }
            triggerUpdate(context)
        }

        private fun executeAction(context: Context, actionType: String) {
            when (actionType) {
                "vpn_start" -> sendVpnCommand(context, VpnCaptureService.ACTION_START)
                "vpn_stop" -> sendVpnCommand(context, VpnCaptureService.ACTION_STOP)
                "vpn_toggle" -> {
                    val action = if (VpnCaptureService.isRunning()) VpnCaptureService.ACTION_STOP else VpnCaptureService.ACTION_START
                    sendVpnCommand(context, action)
                }
                "kali" -> startTerminalWithRootfs(context, "nh/distro/kali")
                "parrot" -> startTerminalWithRootfs(context, "nh/distro/parrot")
                "stop_all" -> {
                    val stopIntent = Intent(context, TerminalService::class.java)
                    stopIntent.action = TerminalService.ACTION_STOP_ALL
                    context.startService(stopIntent)
                }
            }
            triggerUpdate(context)
        }
    }
}
