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
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.linux_core.MainActivity
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.jvm.Volatile

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

        val sessionIds = java.util.concurrent.ConcurrentHashMap<TerminalSession, String>()
        val idToSession = java.util.concurrent.ConcurrentHashMap<String, TerminalSession>()
        val ignoredSessionIds = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val sessionNames = java.util.concurrent.ConcurrentHashMap<String, String>()
        val sessionDistros = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** Sessiony dočasně přesunuté do plovoucího okna (FloatingTerminalService).
         *  TerminalActivity je přeskakuje při attach-to-existing a callbacky níže
         *  řeší přepnutí/ vrácení session mezi aktivitou a overlayem. */
        val floatedSessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        @Volatile var onSessionFloated: ((String) -> Unit)? = null
        @Volatile var onSessionReturned: ((String) -> Unit)? = null

        fun getSessionById(id: String): TerminalSession? = idToSession[id]

        /** Session ID of the headless background (cron) boot, if one is active.
         *  Guards against duplicate cron sessions (MED-2) and drives auto-
         *  relaunch after a clean death (MED-3). */
        @Volatile var backgroundBootSessionId: String? = null
        @Volatile var backgroundBootReloads = 0
        /** Timestamp posledního úspěšného startu cron session — resetuje
         *  backoff counter, když session žila stabilně (> STABLE_MS). */
        @Volatile var backgroundBootStartedAt = 0L
        private const val STABLE_MS = 5L * 60_000L

        /** Watchdog/revive check: service běží A má aspoň jednu session. */
        fun isAliveWithSessions(): Boolean =
            instance != null && sessions.isNotEmpty()

        // BUG 5 FIX: Atomic monotonicky rostoucí counter, který se kombinuje
        // s nanoTime pro 100% unikátní session ID i při paralelních createSession
        // voláních ve stejné ms (např. hamburger + BOOT UP tlačítko najednou).
        // Dříve: "session_${currentTimeMillis()}" – kolize na úrovni ms.
        private val sessionCounter = java.util.concurrent.atomic.AtomicLong(0L)

        fun getSessionId(session: TerminalSession): String? {
            return sessionIds[session]
        }

        fun getSessionDistro(session: TerminalSession): String {
            val id = getSessionId(session) ?: return "nh/distro/kali"
            return sessionDistros[id] ?: "nh/distro/kali"
        }

        fun getSessionName(session: TerminalSession): String? {
            val id = getSessionId(session) ?: return null
            return sessionNames[id]
        }

        fun setSessionName(session: TerminalSession, name: String) {
            val id = getSessionId(session) ?: return
            sessionNames[id] = name
            // Trigger drawer update in TerminalActivity if active on the main thread
            Handler(Looper.getMainLooper()).post {
                try {
                    com.linux_core.ui.terminal.TerminalActivity.instance?.updateSessionDrawer()
                } catch (e: Exception) {
                    Log.e("TerminalService", "Failed to update session drawer on name change: ${e.message}")
                }
            }
        }

        fun isSessionVpnIgnored(session: TerminalSession): Boolean {
            val id = getSessionId(session) ?: return false
            return ignoredSessionIds[id] ?: false
        }

        fun isSessionVpnIgnoredById(sessionId: String): Boolean {
            return ignoredSessionIds[sessionId] ?: false
        }

        fun setSessionVpnIgnored(sessionId: String, ignored: Boolean) {
            ignoredSessionIds[sessionId] = ignored
            // Trigger drawer update in TerminalActivity if active on the main thread
            Handler(Looper.getMainLooper()).post {
                try {
                    com.linux_core.ui.terminal.TerminalActivity.instance?.updateSessionDrawer()
                } catch (e: Exception) {
                    Log.e("TerminalService", "Failed to update session drawer: ${e.message}")
                }
            }
        }

        fun getInstance(): TerminalService? = instance

        fun isRunning(): Boolean = instance != null

        fun getActiveSessionCount(): Int = sessions.size

        private fun getSessionPid(session: TerminalSession): Int {
            return try {
                val field = session.javaClass.getDeclaredField("mPid")
                field.isAccessible = true
                field.get(session) as Int
            } catch (e: Exception) {
                Log.w(TAG, "Could not get PID from session via reflection: ${e.message}")
                -1
            }
        }

        fun createSession(
            context: Context,
            config: ProotConfig,
            view: TerminalView?,
            onError: (String) -> Unit
        ): TerminalSession {
            startService(context)

            // BUG 5 FIX: Celé vytvoření session je v synchronized bloku, aby se
            // zabránilo kolizím ID při paralelních voláních (např. hamburger
            // tlačítko + BOOT UP). ID se skládá z:
            //   - nanoTime (časová složka, nekolizní v rámci procesu)
            //   - atomic counter (pořadové číslo, garantuje unikátnost i pro
            //     dvě volání v jednom nanoTime)
            //   - PID (bezpečnostní rezerva, kdyby se counter z nějakého
            //     důvodu přetočil – což je u Long prakticky nemožné)
            val session: TerminalSession
            var sessionId: String
            synchronized(this) {
                val seq = sessionCounter.incrementAndGet()
                val nanos = System.nanoTime()
                val pid = android.os.Process.myPid()
                sessionId = "session_${pid}_${nanos}_$seq"
                while (idToSession.containsKey(sessionId)) {
                    // Extrémně vzácné: počkej na další nanoTime + inkrementuj
                    sessionId = "session_${pid}_${System.nanoTime()}_${sessionCounter.incrementAndGet()}"
                }

                val newEnv = config.env.toMutableList().apply {
                    add("NETHUNTER_SESSION_ID=$sessionId")
                    // Proot se startuje s prazdnym env — TERM musi byt nastaven rucne,
                    // jinak nefunguji barvy (dircolors), terminfo klavesy ani prekreslovani
                    add("TERM=xterm-256color")
                    // Vynutit UTF-8 locale na potomku PTY, aby nano/shell/editory
                    // interpretovaly multibyte diakritiku (ě, š, č, ž, í) správně
                    // místo fallbacku na LC_CTYPE=POSIX, který mangluje vložený UTF-8 text.
                    // C.UTF-8 je vestavěné v glibc a funguje bez ohledu na jazyk.
                    add("LANG=C.UTF-8")
                    add("LC_CTYPE=C.UTF-8")
                }.toTypedArray()

                val client = ViewHostSessionClient(view, onError)
                session = TerminalSession(
                    config.command[0], config.cwd, config.command, newEnv, 1000, client
                )

                sessionIds[session] = sessionId
                idToSession[sessionId] = session
                sessionDistros[sessionId] = try {
                    java.io.File(config.rootfsDir).relativeTo(java.io.File(config.cwd)).path
                } catch (_: Exception) {
                    java.io.File(config.rootfsDir).name
                }

                sessions.add(session)
                sessionClients[session] = client
            }
            instance?.updateNotification()
            WidgetProvider.triggerUpdate(context)
            Log.i(TAG, "Session created (id=$sessionId). Total sessions: ${sessions.size}")
            return session
        }

        fun removeSession(session: TerminalSession) {
            val pid = getSessionPid(session)
            session.finishIfRunning()
            
            if (pid > 0) {
                try {
                    // Force kill the entire process group to prevent zombies/leaks
                    Runtime.getRuntime().exec("kill -9 -$pid")
                    Log.i(TAG, "Force killed process group -$pid")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to kill process group for PID $pid: ${e.message}")
                }
            }

            sessions.remove(session)
            sessionClients.remove(session)
            
            // Clean up session ID mappings
            val id = sessionIds.remove(session)
            val wasBackground = (id != null && id == backgroundBootSessionId)
            if (wasBackground) backgroundBootSessionId = null
            if (id != null) {
                idToSession.remove(id)
                ignoredSessionIds.remove(id)
                sessionNames.remove(id)
                sessionDistros.remove(id)
            }
            
            instance?.updateNotification()
            instance?.let { WidgetProvider.triggerUpdate(it) }
            Log.i(TAG, "Session removed. Remaining: ${sessions.size}")
            if (sessions.isEmpty()) {
                // Background (cron) session died on its own (guest crash / proot
                // error). If autostart is on, relaunch it (bounded) instead of
                // silently stopping the service so cron keeps running.
                val reload = wasBackground &&
                    instance?.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
                        ?.getBoolean("boot_autostart", true) == true &&
                    backgroundBootReloads < 3
                if (reload) {
                    // Stabilní běh > STABLE_MS resetuje backoff counter: 3 crashy
                    // za sebou v průběhu dne nesmí trvale vypnout cron obnovu.
                    if (backgroundBootStartedAt > 0 &&
                        System.currentTimeMillis() - backgroundBootStartedAt > STABLE_MS) {
                        backgroundBootReloads = 0
                    }
                    backgroundBootReloads++
                    Log.i(TAG, "Background cron session ended — relaunching (attempt $backgroundBootReloads)")
                    val app = instance?.applicationContext
                    if (app != null) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (TerminalService.backgroundBootSessionId == null) BackgroundBoot.start(app)
                        }, 20_000L)
                    }
                    return
                }
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

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "NetHunter:TerminalServiceWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "WakeLock acquired for TerminalService")
            }
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NetHunter:TerminalServiceWifiLock")
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL, "NetHunter:TerminalServiceWifiLock")
                }.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "WifiLock acquired for TerminalService")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
            Log.i(TAG, "WakeLock released for TerminalService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock: ${e.message}")
        }
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wifiLock = null
            Log.i(TAG, "WifiLock released for TerminalService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WifiLock: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        acquireLocks()
        createNotificationChannel()
        LocalApiServer.start(applicationContext)
        // Watchdog alarm: service alive → alarm musí běžet (revive po LMK/OEM killu).
        WatchdogReceiver.schedule(this)
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            stopAll()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        WatchdogReceiver.schedule(this)

        // START_STICKY restart: the system killed the app — bring the background
        // cron session back so cron automation keeps running.
        if (intent == null) {
            val prefs = getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("boot_autostart", true) && sessions.isEmpty()) {
                Log.i(TAG, "Restart after kill — resuming background boot (cron)")
                BackgroundBoot.start(applicationContext)
            }
        }
        return START_STICKY
    }

    /**
     * Swipe z recents / OEM "clean all" — systém (hlavně MIUI/HyperOS) zabije
     * process group. Service má šanci se restartovat PŘED smrtí: pokud běží
     * sessiony nebo je zapnutý autostart, pustíme startForegroundService znovu
     * (z onTaskRemoved je to povolené — app je ve foreground-service stavu).
     * android:stopWithTask="false" v manifestu zajišťuje, že se tahle metoda
     * vůbec zavolá a service není stopnut automaticky.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved — OEM swipe-kill defense")
        val prefs = getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        val wantAlive = sessions.isNotEmpty() || prefs.getBoolean("boot_autostart", true)
        if (wantAlive) {
            try {
                val restart = Intent(applicationContext, TerminalService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(restart)
                } else {
                    applicationContext.startService(restart)
                }
                WatchdogReceiver.schedule(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "onTaskRemoved revive failed: ${e.message}")
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun stopAll() {
        Log.i(TAG, "Stopping all sessions")
        sessions.forEach { session ->
            sessionClients[session]?.currentView = null
            val pid = getSessionPid(session)
            session.finishIfRunning()
            if (pid > 0) {
                try {
                    Runtime.getRuntime().exec("kill -9 -$pid")
                    Log.i(TAG, "Force killed session process group -$pid")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to kill group for PID $pid: ${e.message}")
                }
            }
        }
        sessions.clear()
        sessionClients.clear()
        backgroundBootSessionId = null
        backgroundBootReloads = 0
        // Uživatel explicitně ukončil — watchdog nesmí app oživovat.
        WatchdogReceiver.cancel(this)
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
            // Ongoing VŽDY když service běží: swipenutelná notifikace = slabší
            // pozice v LMK a OEM killer ji bere jako "app nepoužívaná".
            .setOngoing(true)
            // PRIORITY_MIN = pozvánka k zabití na OEM ROM; LOW je minimum pro FGS.
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
        releaseLocks()
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
        
        // Clean the text copied from the terminal buffer:
        // 1. Remove NUL/null bytes (\u0000) which cause truncation/strange characters
        // 2. Split by newline, trim trailing spaces on each line, and join back
        val cleanedText = text.replace("\u0000", "")
            .split("\n")
            .joinToString("\n") { it.trimEnd() }

        val clip = android.content.ClipData.newPlainText("terminal", cleanedText)
        clipboard.setPrimaryClip(clip)
    }
    
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val context = currentView?.context ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).coerceToText(context).toString()
            // Použít oficiální Termux bracketed-paste přes TerminalEmulator.paste():
            //  1) odstraní ESC a C1 control znaky, 2) normalizuje \n/\r\n na \r,
            //  3) zabalí do \e[200~/\e[201~ POUZE když běžící aplikace (např. nano)
            //     aktivovala bracketed paste (DECSET 2004) — jinak se sekvence
            //     nepoužijí a text jde RAW, čímž neunikne ovládacím zkratkám (^K, ^M…).
            val emulator = session.getEmulator()
            if (emulator != null) {
                emulator.paste(text)
            } else {
                session.write(text)
            }
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
