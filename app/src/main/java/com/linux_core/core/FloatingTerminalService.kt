package com.linux_core.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import kotlin.concurrent.thread

/**
 * FloatingTerminalService — plovoucí terminálové okno přes ostatní aplikace.
 *
 * Spouští se příkazem `nh float` (nová session) nebo `nh float here`
 * (přesun aktuální session z TerminalActivity do overlaye).
 *
 * Dva stavy:
 *  - EXPANDED: plovoucí okno s titulkovou lištou (tažení, průhlednost,
 *    minimalizace, zavření) + Termux TerminalView + rohová resize úchytka.
 *  - MINIMIZED: malý kulatý chat-head (Messenger-like), tažení, tap = obnovit,
 *    dlouhý stisk = zavřít.
 *
 * Nastavení (pozice, rozměr, průhlednost, pozice bubliny) se ukládá do
 * SharedPreferences "float_terminal".
 *
 * Vyžaduje SYSTEM_ALERT_WINDOW (Settings.canDrawOverlays) — bez něj LocalApiServer
 * otevře systémové nastavení a overlay se nespustí.
 */
class FloatingTerminalService : Service() {

    companion object {
        private const val TAG = "FloatTerminal"
        const val ACTION_NEW = "com.linux_core.float.NEW"
        const val ACTION_ATTACH = "com.linux_core.float.ATTACH"
        const val ACTION_CLOSE = "com.linux_core.float.CLOSE"
        const val EXTRA_SESSION_ID = "session_id"
        private const val NOTIF_ID = 4242
        private const val CHANNEL_ID = "float_terminal"
        private val ALPHA_PRESETS = floatArrayOf(1.0f, 0.85f, 0.7f, 0.55f, 0.4f)

        @Volatile
        var isShowing = false
            private set

        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun requestOverlayPermission(context: Context) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open overlay settings: ${e.message}")
            }
        }
    }

    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("float_terminal", MODE_PRIVATE) }

    private var windowRoot: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var terminalView: TerminalView? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var session: TerminalSession? = null
    private var borrowed = false
    private var minimized = false
    private var alphaIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        alphaIndex = prefs.getInt("alpha_index", 0).coerceIn(0, ALPHA_PRESETS.size - 1)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            ACTION_CLOSE -> {
                closeFloat(returnBorrowed = true)
                return START_NOT_STICKY
            }
            ACTION_ATTACH -> {
                val sid = intent.getStringExtra(EXTRA_SESSION_ID)
                attachExisting(sid)
            }
            else -> createNewSession() // ACTION_NEW i null (restart)
        }
        return START_NOT_STICKY
    }

    // ─── Session lifecycle ──────────────────────────────────────────────

    private fun activeDistroRootfsDirName(): String {
        val marker = File(filesDir, "nh/.active_distro")
        val spec = try {
            if (marker.exists()) marker.readText().trim() else ""
        } catch (e: Exception) { "" }
        return when {
            spec.startsWith("docker:") -> "nh/distro/docker/${spec.removePrefix("docker:")}"
            spec == "kali" || spec == "parrot" -> "nh/distro/$spec"
            File(filesDir, "nh/distro/parrot").exists() -> "nh/distro/parrot"
            File(filesDir, "nh/distro/kali").exists() -> "nh/distro/kali"
            else -> "nh/distro/kali"
        }
    }

    private fun createNewSession() {
        if (session != null) {
            if (minimized) restoreFromBubble() else ensureExpanded()
            return
        }
        thread {
            try {
                val distro = activeDistroRootfsDirName()
                val isDocker = distro.startsWith("nh/distro/docker/")
                val cfg = ProotManager.setupProotEnvironment(
                    applicationContext, distro, false, null, false, isDocker
                )
                mainHandler.post {
                    try {
                        // Nejdřív zobraz okno (vytvoří terminalView a přidá ho do WM),
                        // teprve pak vytvoř session s tímto view — stejný vzor jako
                        // TerminalActivity (view v hierarchii před createSession).
                        showExpanded()
                        val tv = terminalView ?: run { Log.e(TAG, "no terminalView after showExpanded"); stopSelf(); return@post }
                        val s = TerminalService.createSession(applicationContext, cfg, tv) { err ->
                            Log.e(TAG, "Float session error: $err")
                        }
                        session = s
                        borrowed = false
                        bindSessionToView()
                    } catch (e: Exception) {
                        Log.e(TAG, "createSession failed", e)
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "setupProotEnvironment failed", e)
                mainHandler.post { stopSelf() }
            }
        }
    }

    private fun attachExisting(sessionId: String?) {
        val s = sessionId?.let { TerminalService.getSessionById(it) }
        if (s == null || !s.isRunning) {
            Log.w(TAG, "attach: session '$sessionId' not found/running — creating new")
            createNewSession()
            return
        }
        session = s
        borrowed = true
        TerminalService.floatedSessionIds.add(sessionId!!)
        TerminalService.detachView(s)
        // TerminalActivity přepne na jinou session (callback běží na main threadu)
        mainHandler.post { TerminalService.onSessionFloated?.invoke(sessionId) }
        showExpanded()
        bindSessionToView()
    }

    private fun bindSessionToView() {
        val s = session ?: return
        val v = terminalView ?: return
        // Odložit attach až po layoutu okna — TerminalView potřebuje znát svou
        // velikost, jinak by se emulator inicializoval s 0 sloupci.
        v.post {
            TerminalService.attachView(s, v)
            v.requestFocus()
            v.onScreenUpdated()
        }
    }

    private fun closeFloat(returnBorrowed: Boolean) {
        val s = session
        val sid = s?.let { TerminalService.getSessionId(it) }
        removeExpanded()
        removeBubble()
        if (s != null) {
            TerminalService.detachView(s)
            if (borrowed) {
                if (sid != null) TerminalService.floatedSessionIds.remove(sid)
                if (returnBorrowed) {
                    // Vrácení do TerminalActivity — ten si session zase attachne
                    mainHandler.post { TerminalService.onSessionReturned?.invoke(sid ?: "") }
                    val i = Intent(this, com.linux_core.ui.terminal.TerminalActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    if (sid != null) i.putExtra("returnSessionId", sid)
                    try { startActivity(i) } catch (e: Exception) {
                        Log.w(TAG, "Failed to return session to activity: ${e.message}")
                    }
                }
                // !returnBorrowed: session zůstává v TerminalService (viditelná v draweru)
            } else {
                TerminalService.removeSession(s)
            }
        }
        session = null
        isShowing = false
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    // ─── Expanded window ────────────────────────────────────────────────

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun ensureExpanded() {
        if (windowRoot == null) showExpanded()
    }

    private fun showExpanded() {
        removeExpanded()
        removeBubble()
        minimized = false

        val dm = resources.displayMetrics
        val defW = (dm.widthPixels * 0.85f).toInt()
        val defH = (dm.heightPixels * 0.55f).toInt()

        val params = WindowManager.LayoutParams(
            prefs.getInt("w", defW),
            prefs.getInt("h", defH),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", 20)
            y = prefs.getInt("y", 120)
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        windowParams = params

        val root = buildWindowView(params)
        root.alpha = ALPHA_PRESETS[alphaIndex]
        windowRoot = root
        try {
            wm.addView(root, params)
            isShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "addView failed (overlay permission?)", e)
            windowRoot = null
            stopSelf()
        }
    }

    private fun buildWindowView(params: WindowManager.LayoutParams): FrameLayout {
        val root = FrameLayout(this)

        val panelBg = GradientDrawable().apply {
            setColor(Color.parseColor("#F2111318"))
            cornerRadius = dp(10f)
            setStroke(dp(1f).toInt(), Color.parseColor("#2a2d38"))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
        }
        root.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // ── Title bar: drag handle + ovládací tlačítka ──
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1a1d26"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f).toInt(), 0, dp(4f).toInt(), 0)
        }
        val title = TextView(this).apply {
            text = "⬢ NH FLOAT"
            setTextColor(Color.parseColor("#8b93a7"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        titleBar.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
            weight = 1f
        })

        fun titleButton(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(10f).toInt(), 0, dp(10f).toInt(), 0)
                setOnClickListener { onClick() }
            }
        }
        titleBar.addView(titleButton("◐") { cycleAlpha() })
        titleBar.addView(titleButton("▁") { minimize() })
        titleBar.addView(titleButton("✕") { closeFloat(returnBorrowed = true) })
        panel.addView(titleBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(40f).toInt()))

        // ── Terminal view ──
        val tv = TerminalView(this, null).apply {
            setBackgroundColor(Color.BLACK)
            setTextSize(prefs.getInt("font_size", 11))
            setTerminalViewClient(FloatViewClient())
            isFocusable = true
            isFocusableInTouchMode = true
        }
        terminalView = tv
        panel.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Tažení okna za titulkovou lištu ──
        var dragStartX = 0f; var dragStartY = 0f; var startWinX = 0; var startWinY = 0
        titleBar.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = ev.rawX; dragStartY = ev.rawY
                    startWinX = params.x; startWinY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startWinX + (ev.rawX - dragStartX).toInt()
                    params.y = startWinY + (ev.rawY - dragStartY).toInt()
                    windowRoot?.let { wm.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP -> { persistWindow(params); true }
                else -> false
            }
        }

        // ── Resize úchytka (pravý dolní roh) ──
        val handle = TextView(this).apply {
            text = "◢"
            setTextColor(Color.parseColor("#5a6172"))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        root.addView(handle, FrameLayout.LayoutParams(
            dp(30f).toInt(), dp(30f).toInt(), Gravity.BOTTOM or Gravity.END))

        var rsStartX = 0f; var rsStartY = 0f; var rsStartW = 0; var rsStartH = 0
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rsStartX = ev.rawX; rsStartY = ev.rawY
                    rsStartW = params.width; rsStartH = params.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm2 = resources.displayMetrics
                    params.width = (rsStartW + (ev.rawX - rsStartX).toInt())
                        .coerceIn(dp(240f).toInt(), dm2.widthPixels)
                    params.height = (rsStartH + (ev.rawY - rsStartY).toInt())
                        .coerceIn(dp(160f).toInt(), dm2.heightPixels)
                    windowRoot?.let { wm.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP -> { persistWindow(params); true }
                else -> false
            }
        }

        return root
    }

    private fun persistWindow(params: WindowManager.LayoutParams) {
        prefs.edit()
            .putInt("x", params.x).putInt("y", params.y)
            .putInt("w", params.width).putInt("h", params.height)
            .apply()
    }

    private fun cycleAlpha() {
        alphaIndex = (alphaIndex + 1) % ALPHA_PRESETS.size
        val a = ALPHA_PRESETS[alphaIndex]
        windowRoot?.alpha = a
        prefs.edit().putInt("alpha_index", alphaIndex).apply()
        Log.i(TAG, "Alpha set to ${(a * 100).toInt()}%")
    }

    private fun removeExpanded() {
        windowRoot?.let {
            try { wm.removeView(it) } catch (e: Exception) { Log.w(TAG, "removeView: ${e.message}") }
        }
        windowRoot = null
        terminalView = null
    }

    // ─── Bubble (chat-head) ─────────────────────────────────────────────

    private fun minimize() {
        removeExpanded()
        minimized = true
        showBubble()
    }

    private fun showBubble() {
        removeBubble()
        val dm = resources.displayMetrics
        val size = dp(56f).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubble_x", dm.widthPixels - size - 24)
            y = prefs.getInt("bubble_y", dm.heightPixels / 2)
        }
        bubbleParams = params

        val bubble = TextView(this).apply {
            text = "⌨"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E61a1d26"))
                setStroke(dp(1.5f).toInt(), Color.parseColor("#3d8bfd"))
            }
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var downTime = 0L
        bubble.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    moved = false; downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (dx * dx + dy * dy > dp(10f) * dp(10f)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        bubbleView?.let { wm.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit().putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply()
                    } else if (System.currentTimeMillis() - downTime > 500) {
                        // dlouhý stisk = zavřít
                        closeFloat(returnBorrowed = false)
                    } else {
                        restoreFromBubble()
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        try {
            wm.addView(bubble, params)
            isShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "bubble addView failed", e)
            bubbleView = null
        }
    }

    private fun restoreFromBubble() {
        removeBubble()
        minimized = false
        showExpanded()
        bindSessionToView()
    }

    private fun removeBubble() {
        bubbleView?.let {
            try { wm.removeView(it) } catch (e: Exception) { Log.w(TAG, "removeBubble: ${e.message}") }
        }
        bubbleView = null
    }

    // ─── Terminal view client (overlay varianta) ────────────────────────

    private inner class FloatViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1.0f

        override fun onSingleTapUp(e: MotionEvent) {
            terminalView?.let { v ->
                v.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                @Suppress("DEPRECATION")
                imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onEmulatorSet() {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                session.write("\r")
                return true
            }
            val arrow = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
                KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
                KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
                else -> null
            }
            if (arrow != null) {
                session.write(arrow)
                return true
            }
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            if (ctrlDown) {
                val upper = codePoint.toChar().uppercaseChar().code
                if (upper in 64..95) {
                    session.write(Character.toString((upper - 64).toChar()))
                    return true
                }
            }
            return false // běžné znaky píše TerminalView sám
        }

        // TerminalViewClient log metody (rozhraní je vyžaduje)
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Stack trace", e) }
    }

    // ─── Foreground notification ────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Plovoucí terminál", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val closeIntent = Intent(this, FloatingTerminalService::class.java).setAction(ACTION_CLOSE)
        val closePi = PendingIntent.getService(this, 1, closeIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Plovoucí terminál")
            .setContentText("NH Float aktivní")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .addAction(0, "Zavřít", closePi)
            .build()
    }

    override fun onDestroy() {
        removeExpanded()
        removeBubble()
        isShowing = false
        // Vlastněná session, kterou uživatel nezavřel, zůstává v TerminalService
        // (objeví se v draweru po návratu do aplikace).
        super.onDestroy()
    }
}
