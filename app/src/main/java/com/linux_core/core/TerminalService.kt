package com.linux_core.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.linux_core.MainActivity
import java.util.concurrent.CopyOnWriteArrayList

class TerminalService : Service() {
    companion object {
        private const val TAG = "TerminalService"
        const val CHANNEL_ID = "terminal_sessions"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP_ALL = "com.linux_core.STOP_ALL"

        @Volatile
        private var instance: TerminalService? = null

        val sessions = CopyOnWriteArrayList<TerminalSession>()
        val sessionClients = HashMap<TerminalSession, ViewHostSessionClient>()

        fun getInstance(): TerminalService? = instance

        fun isRunning(): Boolean = instance != null

        fun getActiveSessionCount(): Int = sessions.size

        fun createSession(
            context: Context,
            config: ProotConfig,
            view: TerminalView?,
            onError: (String) -> Unit
        ): TerminalSession {
            startService(context)
            val client = ViewHostSessionClient(view, onError)
            val session = TerminalSession(
                config.command[0], config.cwd, config.command, config.env, 1000, client
            )
            sessions.add(session)
            sessionClients[session] = client
            instance?.updateNotification()
            WidgetProvider.triggerUpdate(context)
            Log.i(TAG, "Session created. Total sessions: ${sessions.size}")
            return session
        }

        fun removeSession(session: TerminalSession) {
            session.finishIfRunning()
            sessions.remove(session)
            sessionClients.remove(session)
            instance?.updateNotification()
            instance?.let { WidgetProvider.triggerUpdate(it) }
            Log.i(TAG, "Session removed. Remaining: ${sessions.size}")
            if (sessions.isEmpty()) {
                instance?.stopForeground(STOP_FOREGROUND_REMOVE)
                instance?.stopSelf()
            }
        }

        fun attachView(session: TerminalSession, view: TerminalView?) {
            val client = sessionClients[session]
            client?.currentView = view
            if (view != null) {
                view.attachSession(session)
                view.post { view.onScreenUpdated() }
            }
        }

        fun detachView(session: TerminalSession) {
            val client = sessionClients[session]
            client?.currentView = null
        }

        private fun startService(context: Context) {
            val intent = Intent(context, TerminalService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        LocalApiServer.start(applicationContext)
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            stopAll()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun stopAll() {
        Log.i(TAG, "Stopping all sessions")
        sessions.forEach { sessionClients[it]?.currentView = null }
        sessions.forEach { it.finishIfRunning() }
        sessions.clear()
        sessionClients.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val count = sessions.size
        val contentText = when {
            count == 0 -> "No active sessions"
            count == 1 -> "1 active session"
            else -> "$count active sessions"
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )

        val exitIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TerminalService::class.java).apply {
                action = ACTION_STOP_ALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NetHunter AI Operator")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(openIntent)
            .setOngoing(count > 0)
            .setPriority(if (count > 0) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Exit All",
                exitIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Terminal Sessions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active terminal sessions in background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun pendingIntentImmutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    override fun onDestroy() {
        instance = null
        LocalApiServer.stop()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }
}

class ViewHostSessionClient(
    @Volatile var currentView: TerminalView?,
    private val onError: (String) -> Unit
) : TerminalSessionClient {
    private var dataCount = 0

    override fun onTextChanged(session: TerminalSession) {
        dataCount++
        currentView?.onScreenUpdated()
    }

    override fun onTitleChanged(session: TerminalSession) {}

    override fun onSessionFinished(session: TerminalSession) {
        Log.i("TermSession", "onSessionFinished: exitStatus=${session.exitStatus}")
        currentView = null
        Handler(Looper.getMainLooper()).post {
            TerminalService.removeSession(session)
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val context = currentView?.context ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("terminal", text)
        clipboard.setPrimaryClip(clip)
    }
    
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val context = currentView?.context ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).coerceToText(context).toString()
            session.write(text)
        }
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 0
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Stack trace", e) }
}
