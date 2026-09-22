package com.linux_core.ui.terminal

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.linux_core.core.rootfs.DEFAULT_BOOT_MODE
import com.linux_core.core.rootfs.ProotConfig
import com.linux_core.core.rootfs.ProotManager
import com.linux_core.core.rootfs.RootfsManager
import com.linux_core.core.rootfs.loadBootMode
import com.linux_core.core.terminal.HistoryManager
import com.linux_core.core.terminal.TerminalService
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TerminalActivity : ComponentActivity() {
    companion object {
        internal const val TAG = "TerminalActivity"

        @Volatile
        var instance: TerminalActivity? = null
    }

    private val vpnPrepareLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpnServiceDirectly()
            } else {
                android.widget.Toast
                    .makeText(this, "VPN permission denied", android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
        }

    internal lateinit var terminalView: TerminalView
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorText: TextView
    internal var config: ProotConfig? = null
    internal var currentSession: TerminalSession? = null
    private val viewClient = TerminalViewClientImpl()

    // History and suggestions
    internal lateinit var historyManager: HistoryManager
    internal val currentCommand = StringBuilder()
    internal lateinit var suggestionBar: HorizontalScrollView
    internal lateinit var suggestionContainer: LinearLayout

    // Debounce handler pro návrhový bar. Vytváření Buttonů na main threadu
    // PŘI každém code pointu (uvnitř IME commitText → inputCodePoint) MIUI
    // vyhodnotí jako jank a zkompenzuje to re-komitováním znaku → „multi input“
    // (každé písmeno se vloží 2–5×). Srazíme záplavu keystroke na jednu
    // aktualizaci a bar přestavíme JEN když se sada návrhů skutečně změnila.
    internal val suggestionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal val rebuildSuggestionsRunnable = Runnable { rebuildSuggestions() }
    internal var lastSuggestedList: List<String>? = null

    // Drawer-based Session Management
    internal lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    internal lateinit var sessionDrawerContainer: LinearLayout
    internal var activeDrawerTab = "ALL" // "ALL", "KALI", "PARROT"
    internal val drawerTabButtons = HashMap<String, Button>()
    private lateinit var drawerView: FrameLayout
    internal lateinit var drawerViewContentLayout: LinearLayout
    internal lateinit var drawerHeader: TextView
    internal lateinit var tabLayout: LinearLayout
    internal lateinit var btnAddSession: Button
    internal var isDrawerExpanded = false
    private lateinit var topBar: LinearLayout
    internal lateinit var statusTitle: TextView

    // ::statusTitle.isInitialized funguje jen lexikálně uvnitř této třídy —
    // extension funkce v jiném souboru (TerminalSessionDrawer.kt) se musí ptát přes tohle.
    internal val isStatusTitleInitialized: Boolean
        get() = ::statusTitle.isInitialized

    // ── Services Panel State ──
    internal var isServicesExpanded = false
    internal var expandedService: String? = null // "adb" or null
    internal lateinit var servicesPanel: LinearLayout
    internal lateinit var servicesDetailPanel: LinearLayout
    internal lateinit var btnServicesToggle: Button
    internal lateinit var btnAdb: Button
    internal val servicesUpdateHandler = Handler(Looper.getMainLooper())
    internal val servicesPoller =
        object : Runnable {
            override fun run() {
                // ADB indikátor se musí aktualizovat VŽDY (i při sbaleném panelu) —
                // jinak tečka zůstane svítit, i když daemon spadl. TCP probe je levná.
                // Detail se obnoví jen když je panel otevřený.
                thread {
                    try {
                        val adbSt =
                            com.linux_core.core.terminal.ShellDaemonClient
                                .status()
                        runOnUiThread { updateServiceIndicator("adb", btnAdb, adbSt.running) }

                        if (isServicesExpanded) {
                            runOnUiThread { expandedService?.let { updateServiceDetail(it) } }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "servicesPoller error: ${e.message}")
                    }
                }
                servicesUpdateHandler.postDelayed(this, if (isServicesExpanded) 5000 else 15000)
            }
        }

    internal var drawerUpdateHandler = Handler(Looper.getMainLooper())
    internal val drawerRamUpdater =
        object : Runnable {
            override fun run() {
                if (drawerLayout.isDrawerOpen(Gravity.START)) {
                    updateSessionDrawer()
                    drawerUpdateHandler.postDelayed(this, 3000)
                }
            }
        }

    // Keyboard Toolbar and Special Keypad Panel states
    var customCtrlActive = false
    var customAltActive = false
    var customShiftActive = false
    internal lateinit var btnCtrl: Button
    internal lateinit var btnAlt: Button
    internal lateinit var btnShift: Button
    internal lateinit var btnToggleKeypad: Button
    internal lateinit var specialKeypadPanel: LinearLayout
    internal lateinit var tabContainer: LinearLayout
    internal lateinit var keysContainer: LinearLayout
    internal var activeKeyboardTab = "SYMBOLS"
    internal val tabsList = listOf("CONTROL", "SYMBOLS", "NAVIGATION", "CTRL COMBOS", "F-KEYS")
    internal val tabButtons = HashMap<String, Button>()

    // X11 Desktop (external launcher) integration fields
    internal var activeViewMode = "CLI" // "CLI" or "GUI"
    private lateinit var btnCli: Button
    private lateinit var btnGui: Button
    private lateinit var guiContainer: FrameLayout
    private lateinit var guiPlaceholderLayout: LinearLayout
    private lateinit var guiPlaceholderTitle: TextView
    private lateinit var guiPlaceholderDesc: TextView
    private lateinit var btnStartGui: Button
    private lateinit var guiProgress: ProgressBar
    private lateinit var toolbarScroll: View
    private val guiScope = CoroutineScope(Dispatchers.Main + Job())
    internal var pendingNanoCommand: String? = null

    // PiP: uložené visibility chrome prvků před vstupem do PiP (obnovení při návratu)
    private val pipSavedVisibility = HashMap<View, Int>()

    // External X11 launcher app (renders the desktop; this app only runs the X server)
    private val EXTERNAL_LAUNCHER_PKG = "com.linux_core.xlauncher"

    var terminalFontSizeFloat = 32f

    fun changeTerminalFontSize(scale: Float) {
        val dampenedScale = 1.0f + (scale - 1.0f) * 0.15f
        terminalFontSizeFloat *= dampenedScale
        terminalFontSizeFloat = terminalFontSizeFloat.coerceIn(8f, 72f)
        terminalView.setTextSize(terminalFontSizeFloat.toInt())
        getSharedPreferences("terminal_prefs", MODE_PRIVATE)
            .edit()
            .putFloat("font_size", terminalFontSizeFloat)
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        instance = this
        super.onCreate(savedInstanceState)
        com.linux_core.core.ImmersiveMode
            .enterImmersive(this)
        val prefs = getSharedPreferences("terminal_prefs", MODE_PRIVATE)
        terminalFontSizeFloat = prefs.getFloat("font_size", 32f)

        viewClient.setActivity(this)
        historyManager = HistoryManager(this)

        // Root DrawerLayout container
        drawerLayout =
            androidx.drawerlayout.widget.DrawerLayout(this).apply {
                layoutParams =
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }

        // Main content vertical container
        val mainLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
                layoutParams =
                    DrawerLayout.LayoutParams(DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT)
            }

        // Active top bar with menu button, spacer, and GUI switch
        topBar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = createRoundedDrawable(Color.parseColor("#07080a"), 0f)
                val padVert = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()
                setPadding(8, padVert, 8, padVert)
                layoutParams =
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.VISIBLE // Visible by default in both CLI and GUI
            }

        // Hamburger Menu button on the left of topBar to slide drawer open
        val btnMenu =
            Button(this).apply {
                text = "☰"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#00FF41"))
                background = createRoundedDrawable(Color.parseColor("#0f1017"), 6f, Color.parseColor("#1e2026"), 1f)
                setPadding(12, 0, 12, 0)
                val params =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt(),
                        ).apply {
                            setMargins(8, 0, 8, 0)
                        }
                layoutParams = params
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    isDrawerExpanded = true
                    val maxWidthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 280f, resources.displayMetrics).toInt()
                    val dParams = drawerView.layoutParams as DrawerLayout.LayoutParams
                    dParams.width = maxWidthPx
                    drawerView.layoutParams = dParams
                    drawerView.requestLayout()
                    updateSessionDrawer()
                    drawerLayout.openDrawer(Gravity.START)
                }
            }
        topBar.addView(btnMenu)

        // Menu button to finish activity and return to MainActivity
        val btnGoToMenu =
            Button(this).apply {
                text = "🏠"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = createRoundedDrawable(Color.parseColor("#0f1017"), 6f, Color.parseColor("#1e2026"), 1f)
                setPadding(12, 0, 12, 0)
                val params =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt(),
                        ).apply {
                            setMargins(0, 0, 8, 0)
                        }
                layoutParams = params
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    finish()
                }
            }
        topBar.addView(btnGoToMenu)

        val spacer1 =
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
        topBar.addView(spacer1)

        // ── Distro title + Services toggle ──
        val distroRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        statusTitle =
            TextView(this).apply {
                text = "🐉 KALI"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD))
                setTextColor(Color.parseColor("#00FF41"))
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }
        distroRow.addView(statusTitle)

        btnServicesToggle =
            Button(this).apply {
                text = "▼"
                textSize = 9f
                setTextColor(Color.parseColor("#00FF41"))
                background = null
                setPadding(4, 0, 4, 0)
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    toggleServicesPanel()
                }
            }
        distroRow.addView(btnServicesToggle)

        topBar.addView(distroRow)

        val spacer2 =
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
        topBar.addView(spacer2)

        // CLI/GUI Switch on the right side of topBar
        val guiToggleLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = createRoundedDrawable(Color.parseColor("#0c0d12"), 6f, Color.parseColor("#1e2026"), 1f)
                setPadding(2, 2, 2, 2)
                layoutParams =
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(8, 0, 8, 0)
                    }
                gravity = Gravity.CENTER
            }

        btnCli =
            Button(this).apply {
                text = "🐚 CLI"
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.BLACK)
                background = createRoundedDrawable(Color.parseColor("#00FF41"), 4f)
                setPadding(10, 0, 10, 0)
                val params =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics).toInt(),
                    )
                layoutParams = params
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    switchViewMode("CLI")
                }
            }

        btnGui =
            Button(this).apply {
                text = "🖥️ GUI"
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.WHITE)
                background = createRoundedDrawable(Color.parseColor("#0c0d12"), 4f)
                setPadding(10, 0, 10, 0)
                val params =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics).toInt(),
                    )
                layoutParams = params
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    switchViewMode("GUI")
                }
            }

        guiToggleLayout.addView(btnCli)
        guiToggleLayout.addView(btnGui)

        topBar.addView(guiToggleLayout)

        mainLayout.addView(topBar)

        // ── Services Panel (collapsible) ──
        servicesPanel = buildServicesPanel()
        mainLayout.addView(servicesPanel)

        servicesDetailPanel = buildServicesDetailPanel()
        mainLayout.addView(servicesDetailPanel)

        val topBarDivider =
            View(this).apply {
                setBackgroundColor(Color.parseColor("#1e2026"))
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics).toInt(),
                    )
            }
        mainLayout.addView(topBarDivider)

        // Terminal view container (takes weight = 1f to fill remaining screen space)
        val terminalContainer =
            FrameLayout(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }

        terminalView = TerminalView(this, null)
        // Zapni mouse reporting v Termux TerminalView (stejne jako Termux:Preferences -> Mouse),
        // aby nano s "set mouse" reagovalo na dotyk/tah (pohyb kurzoru) misto scrollovani.
        try {
            getSharedPreferences("termux_preferences", MODE_PRIVATE)
                .edit()
                .putBoolean("mouse_enabled", true)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable mouse pref: ${e.message}")
        }
        terminalView.setBackgroundColor(Color.BLACK)
        terminalView.setTextSize(terminalFontSizeFloat.toInt())
        terminalView.setTerminalViewClient(viewClient)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true

        terminalView.setOnClickListener {
            Log.d(TAG, "TerminalView clicked - requesting focus")
            if (specialKeypadPanel.visibility == View.VISIBLE) {
                toggleSpecialKeypad(false)
            } else {
                showSoftKeyboard()
            }
        }

        terminalContainer.addView(
            terminalView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        errorLayout = buildErrorOverlay()
        terminalContainer.addView(
            errorLayout,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        // Add GUI webview container
        guiContainer =
            FrameLayout(this).apply {
                layoutParams =
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                visibility = View.GONE
            }

        // Custom Cyber-styled VNC Placeholder
        guiPlaceholderLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#07080A"))
                setPadding(32, 32, 32, 32)
                layoutParams =
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }

        guiPlaceholderTitle =
            TextView(this).apply {
                text = "X11 Graphical Desktop"
                setTextColor(Color.parseColor("#00FF41"))
                textSize = 20f
                setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
                val params =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            setMargins(0, 0, 0, 8)
                        }
                layoutParams = params
            }
        guiPlaceholderLayout.addView(guiPlaceholderTitle)

        guiPlaceholderDesc =
            TextView(this).apply {
                text =
                    "Start a fully interactive XFCE4 desktop inside Kali/Parrot.\n(On the first boot, packages will be installed automatically)"
                setTextColor(Color.parseColor("#A9B1D6"))
                textSize = 13f
                gravity = Gravity.CENTER
                val params =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            setMargins(0, 0, 0, 24)
                        }
                layoutParams = params
            }
        guiPlaceholderLayout.addView(guiPlaceholderDesc)

        guiProgress =
            ProgressBar(this).apply {
                visibility = View.GONE
                val params =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            setMargins(0, 0, 0, 16)
                        }
                layoutParams = params
            }
        guiPlaceholderLayout.addView(guiProgress)

        btnStartGui =
            Button(this).apply {
                text = "OPEN DESKTOP LAUNCHER"
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.parseColor("#00FF41")) // Sleek green action button
                textSize = 12f
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(24, 12, 24, 12)
                val params =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                layoutParams = params
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onEnterGuiMode()
                }
            }
        guiPlaceholderLayout.addView(btnStartGui)

        guiContainer.addView(guiPlaceholderLayout)
        terminalContainer.addView(guiContainer)

        mainLayout.addView(terminalContainer)

        // Suggestions Bar
        val suggBar = buildSuggestionBar()
        mainLayout.addView(suggBar)

        // Horizontal scrollable Extra Keys Toolbar
        toolbarScroll = buildExtraKeysToolbar()
        mainLayout.addView(toolbarScroll)

        // Custom Special Keypad Panel (grid overlays Android keyboard space)
        val keypadPanel = buildSpecialKeypadPanel()
        mainLayout.addView(keypadPanel)

        // Left drawer container (takes 70dp width initially, sliding from START)
        drawerView =
            FrameLayout(this).apply {
                val params =
                    DrawerLayout
                        .LayoutParams(
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 70f, resources.displayMetrics).toInt(),
                            DrawerLayout.LayoutParams.MATCH_PARENT,
                        ).apply {
                            gravity = Gravity.START
                        }
                layoutParams = params
                setBackgroundColor(Color.parseColor("#08090d"))
            }

        drawerViewContentLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                setPadding(8, 16, 8, 16)
            }
        drawerView.addView(drawerViewContentLayout)

        drawerHeader =
            TextView(this).apply {
                text = "🐚 NETHUNTER SESSIONS"
                setTextColor(Color.parseColor("#00FF41"))
                textSize = 15f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                gravity = Gravity.CENTER_HORIZONTAL
                val params =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            setMargins(0, 0, 0, 24)
                        }
                layoutParams = params
            }
        drawerViewContentLayout.addView(drawerHeader)

        tabLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val params =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            setMargins(0, 0, 0, 16)
                        }
                layoutParams = params
                weightSum = 3f
            }

        val createDrawerTabButton = { title: String, tabCode: String ->
            val btn = Button(this)
            btn.apply {
                text = title
                textSize = 9f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(0, 4, 0, 4)
                val params =
                    LinearLayout
                        .LayoutParams(
                            0,
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt(),
                            1f,
                        ).apply {
                            setMargins(2, 0, 2, 0)
                        }
                layoutParams = params
                setOnClickListener {
                    btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    activeDrawerTab = tabCode
                    updateSessionDrawer()
                }
            }
            btn
        }

        val tabAll = createDrawerTabButton("ALL", "ALL")
        val tabKali = createDrawerTabButton("KALI", "KALI")
        val tabParrot = createDrawerTabButton("PARROT", "PARROT")

        drawerTabButtons["ALL"] = tabAll
        drawerTabButtons["KALI"] = tabKali
        drawerTabButtons["PARROT"] = tabParrot

        tabLayout.addView(tabAll)
        tabLayout.addView(tabKali)
        tabLayout.addView(tabParrot)
        drawerViewContentLayout.addView(tabLayout)

        val drawerScroll =
            ScrollView(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                isVerticalScrollBarEnabled = true
            }

        sessionDrawerContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            }
        drawerScroll.addView(sessionDrawerContainer)
        drawerViewContentLayout.addView(drawerScroll)

        btnAddSession =
            Button(this).apply {
                text = "+ NEW SESSION"
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.parseColor("#00FF41")) // Sleek green action button
                textSize = 12f
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(24, 12, 24, 12)
                val params =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            setMargins(0, 16, 0, 0)
                        }
                layoutParams = params
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    addNewSession()
                }
            }
        drawerViewContentLayout.addView(btnAddSession)

        val dragHandle =
            View(this).apply {
                val params =
                    FrameLayout
                        .LayoutParams(
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt(),
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ).apply {
                            gravity = Gravity.END
                        }
                layoutParams = params
                setBackgroundColor(Color.TRANSPARENT)
            }

        var dragStartX = 0f
        var initialWidth = 0
        dragHandle.setOnTouchListener { _, event ->
            val minWidthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 70f, resources.displayMetrics).toInt()
            val maxWidthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 280f, resources.displayMetrics).toInt()
            val threshold = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 175f, resources.displayMetrics).toInt()

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    initialWidth = drawerView.width
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val newWidth = (initialWidth + dx.toInt()).coerceIn(minWidthPx, maxWidthPx)

                    val params = drawerView.layoutParams as DrawerLayout.LayoutParams
                    params.width = newWidth
                    drawerView.layoutParams = params
                    drawerView.requestLayout()

                    val isNowExpanded = newWidth >= threshold
                    if (isNowExpanded != isDrawerExpanded) {
                        isDrawerExpanded = isNowExpanded
                        updateSessionDrawer()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val finalWidth = if (isDrawerExpanded) maxWidthPx else minWidthPx
                    val params = drawerView.layoutParams as DrawerLayout.LayoutParams
                    params.width = finalWidth
                    drawerView.layoutParams = params
                    drawerView.requestLayout()
                    updateSessionDrawer()
                    true
                }

                else -> {
                    false
                }
            }
        }
        drawerView.addView(dragHandle)

        // Assemble root DrawerLayout
        drawerLayout.addView(mainLayout)
        drawerLayout.addView(drawerView)
        setContentView(drawerLayout)

        drawerLayout.addDrawerListener(
            object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerOpened(drawerView: View) {
                    startDrawerRamUpdateLoop()
                }

                override fun onDrawerClosed(drawerView: View) {
                    stopDrawerRamUpdateLoop()
                }
            },
        )

        handleFileIntent(intent)
        registerFloatCallbacks()
        setupAndStartSession()
    }

    /** Callbacky pro FloatingTerminalService (`nh float here` / návrat session). */
    private fun registerFloatCallbacks() {
        TerminalService.onSessionFloated = { id ->
            runOnUiThread { handleSessionFloated(id) }
        }
        TerminalService.onSessionReturned = { id ->
            runOnUiThread { handleSessionReturned(id) }
        }
    }

    /** Session byla přesunuta do plovoucího okna — přepni na jinou, nebo vytvoř novou. */
    private fun handleSessionFloated(id: String) {
        val cur = currentSession
        if (cur != null && TerminalService.getSessionId(cur) == id) {
            val others = TerminalService.sessions.filter { it != cur && it.isRunning }
            if (others.isNotEmpty()) {
                switchToSession(others.last())
            } else {
                currentSession = null
                addNewSession()
            }
        }
        updateSessionDrawer()
    }

    /** Session se vrátila z plovoucího okna — attachni ji zpět. */
    private fun handleSessionReturned(id: String) {
        val s = TerminalService.getSessionById(id) ?: return
        if (s.isRunning) switchToSession(s)
    }

    internal fun switchViewMode(mode: String) {
        activeViewMode = mode
        if (mode == "CLI") {
            btnCli.setTextColor(Color.BLACK)
            btnCli.background = createRoundedDrawable(Color.parseColor("#00FF41"), 4f)
            btnGui.setTextColor(Color.WHITE)
            btnGui.background = createRoundedDrawable(Color.parseColor("#0c0d12"), 4f)

            terminalView.visibility = View.VISIBLE
            suggestionBar.visibility =
                if (historyManager.getSuggestions(currentCommand.toString()).isNotEmpty()) View.VISIBLE else View.GONE
            toolbarScroll.visibility = View.VISIBLE
            guiContainer.visibility = View.GONE

            topBar.visibility = View.VISIBLE

            showSoftKeyboard()
        } else {
            btnGui.setTextColor(Color.BLACK)
            btnGui.background = createRoundedDrawable(Color.parseColor("#00FF41"), 4f)
            btnCli.setTextColor(Color.WHITE)
            btnCli.background = createRoundedDrawable(Color.parseColor("#0c0d12"), 4f)

            terminalView.visibility = View.GONE
            suggestionBar.visibility = View.GONE
            toolbarScroll.visibility = View.GONE
            specialKeypadPanel.visibility = View.GONE
            guiContainer.visibility = View.VISIBLE

            topBar.visibility = View.VISIBLE

            // Hide soft keyboard when switching to GUI
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)

            onEnterGuiMode()
        }
        updateTopbarTitle()
    }

    private fun isPortOpen(port: Int): Boolean =
        try {
            java.net.Socket("127.0.0.1", port).use { true }
        } catch (_: Exception) {
            false
        }

    /**
     * Called when the user enters GUI mode (or taps the desktop button).
     * This host app only runs the X server (via `nh desktop start`); the actual
     * desktop rendering happens in the separate X11 launcher app.
     */
    private fun onEnterGuiMode() {
        guiPlaceholderTitle.text = "X11 Graphical Desktop"
        guiPlaceholderDesc.text = "Starting desktop in guest container and opening the external X11 launcher…"
        guiPlaceholderLayout.visibility = View.VISIBLE
        btnStartGui.visibility = View.GONE
        guiProgress.visibility = View.VISIBLE
        guiScope.launch {
            try {
                val desktopReady =
                    withContext(Dispatchers.IO) {
                        ensureDesktopStarted()
                        // Wait for the X server (Linux-X11 port 6000) to accept connections
                        // before launching the renderer. Without this the launcher is
                        // started too early and hits "connection refused"; the desktop
                        // session may also be reaped before the launcher retries.
                        var waitedMs = 0L
                        while (!isPortOpen(6000) && waitedMs < 30000) {
                            delay(500)
                            waitedMs += 500
                        }
                        isPortOpen(6000)
                    }
                if (desktopReady) {
                    launchExternalLauncher()
                } else {
                    withContext(Dispatchers.Main) {
                        Toast
                            .makeText(
                                this@TerminalActivity,
                                "Desktop did not start (Linux-X11 port 6000 not listening). Check 'nh desktop status'.",
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    guiProgress.visibility = View.GONE
                    btnStartGui.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Start the X server in the guest (no-op if it is already listening on :1 / TCP 6000). */
    private suspend fun ensureDesktopStarted() {
        if (isPortOpen(6000)) return
        withContext(Dispatchers.IO) {
            try {
                val bootScript = File(filesDir, "usr/bin/boot").absolutePath
                val builder = ProcessBuilder("/system/bin/sh", bootScript, "--", "nethunter-desktop", "start")
                builder.directory(filesDir)
                builder.redirectErrorStream(true)
                // Fire-and-forget: linux-x11 keeps running as a long-lived process,
                // so do NOT block on waitFor(). Readiness is polled via the port above.
                builder.start()
                Log.i(TAG, "Desktop start requested (boot -- nethunter-desktop start)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start desktop: ${e.message}")
            }
        }
    }

    /** Open the standalone X11 launcher app; prompt to install it if missing. */
    private fun launchExternalLauncher() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(EXTERNAL_LAUNCHER_PKG)
            if (intent != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "Install the NetHunter X11 Launcher ($EXTERNAL_LAUNCHER_PKG)", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch external launcher: ${e.message}")
        }
    }

    private fun startVpnServiceDirectly() {
        val intent =
            Intent(this, com.linux_core.core.VpnCaptureService::class.java).apply {
                action = com.linux_core.core.VpnCaptureService.ACTION_START
            }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val intent =
            Intent(this, com.linux_core.core.VpnCaptureService::class.java).apply {
                action = com.linux_core.core.VpnCaptureService.ACTION_STOP
            }
        startService(intent)
    }

    override fun onResume() {
        super.onResume()
        // Poller běží vždy (i se sbaleným panelem) — ADB tečka musí odpovídat realitě.
        servicesUpdateHandler.removeCallbacks(servicesPoller)
        servicesUpdateHandler.post(servicesPoller)
        Log.d(TAG, "onResume - requesting focus")
        terminalView.requestFocus()
        if (specialKeypadPanel.visibility != View.VISIBLE) {
            showSoftKeyboard()
        }

        val serviceSessions =
            TerminalService.sessions.filter {
                val sid = TerminalService.getSessionId(it)
                sid == null || !TerminalService.floatedSessionIds.contains(sid)
            }
        if (serviceSessions.isNotEmpty()) {
            if (currentSession == null) {
                switchToSession(serviceSessions[0])
            }
            updateSessionDrawer()
        }
        if (drawerLayout.isDrawerOpen(Gravity.START)) {
            startDrawerRamUpdateLoop()
        }
    }

    override fun onPause() {
        super.onPause()
        servicesUpdateHandler.removeCallbacks(servicesPoller)
        stopDrawerRamUpdateLoop()
    }

    /**
     * Picture-in-Picture: při odchodu z aplikace s běžící session automaticky
     * do PiP (16:9). Terminál zůstává viditelný a živý nad ostatními aplikacemi.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPictureInPictureMode && currentSession?.isRunning == true) {
            try {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams
                        .Builder()
                        .setAspectRatio(android.util.Rational(16, 9))
                        .build(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "PiP enter failed: ${e.message}")
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // V PiP schováme všechen chrome (topbar, panely, lišty, drawer) —
        // zůstane jen terminál. Původní visibility si pamatujeme pro návrat.
        val chrome =
            listOfNotNull(
                if (::topBar.isInitialized) topBar else null,
                if (::servicesPanel.isInitialized) servicesPanel else null,
                if (::servicesDetailPanel.isInitialized) servicesDetailPanel else null,
                if (::suggestionBar.isInitialized) suggestionBar else null,
                if (::toolbarScroll.isInitialized) toolbarScroll else null,
                if (::specialKeypadPanel.isInitialized) specialKeypadPanel else null,
                if (::drawerView.isInitialized) drawerView else null,
            )
        if (isInPictureInPictureMode) {
            pipSavedVisibility.clear()
            chrome.forEach { v ->
                pipSavedVisibility[v] = v.visibility
                v.visibility = View.GONE
            }
            drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        } else {
            chrome.forEach { v -> pipSavedVisibility[v]?.let { v.visibility = it } }
            pipSavedVisibility.clear()
            drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
            terminalView.requestFocus()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // ashell escape: pokud je extra nastavený na novém intentu (např. singleTask
        // nedovolil vytvoření nové instance), spustíme ashell session v téhle aktivitě
        if ((
                intent.getBooleanExtra("ashellMode", false) ||
                    intent.getStringExtra("rootfsDirName") == "ashell-host"
            ) &&
            intent.getStringExtra("rootfsDirName") != "ashell-adb"
        ) {
            startAshellSession()
            return
        }
        // ashell-adb: shell pod uid 2000 přes shell_daemon
        if (intent.getStringExtra("rootfsDirName") == "ashell-adb") {
            startAdbShellSession()
            return
        }
        handleFileIntent(intent)
        // singleTask: restartovat session pokud nový intent míří na jiný rootfs
        setupAndStartSession()
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        com.linux_core.core.VpnCaptureService.onStateChangeListener = null
        TerminalService.onSessionFloated = null
        TerminalService.onSessionReturned = null
        servicesUpdateHandler.removeCallbacks(servicesPoller)
        super.onDestroy()
        guiScope.cancel()
        for (session in TerminalService.sessions) {
            TerminalService.detachView(session)
        }
        currentSession = null
    }

    // Volané i mimo balíček z TerminalService (session lifecycle callbacky) —
    // proto zůstává jako plnohodnotná členská funkce, ne extension.
    fun updateSessionDrawer() = updateSessionDrawerImpl()

    private fun buildErrorOverlay(): LinearLayout {
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1a1a2e"))
                setPadding(48, 64, 48, 64)
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
        errorText =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                setTypeface(Typeface.MONOSPACE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        layout.addView(errorText)
        return layout
    }

    private fun setupAndStartSession() {
        Log.i(TAG, "setupAndStartSession")
        // Session vrácená z plovoucího okna (nh float here → zavřít) má přednost
        intent.getStringExtra("returnSessionId")?.let { rid ->
            intent.removeExtra("returnSessionId")
            val s = TerminalService.getSessionById(rid)
            if (s != null && s.isRunning) {
                Log.i(TAG, "Re-attaching session returned from float: $rid")
                switchToSession(s)
                return
            }
        }
        // Sessiony dočasně v plovoucím okně se nesmí znovu attachnout zde
        val activeSessions =
            TerminalService.sessions.filter {
                val sid = TerminalService.getSessionId(it)
                sid == null || !TerminalService.floatedSessionIds.contains(sid)
            }
        if (activeSessions.isNotEmpty()) {
            // MULTI-ROOTFS: nový intent na jiný rootfs NESMÍ zabíjet existující
            // sessiony — Kali i Parrot můžou běžet současně (drawer je umí
            // vypsat a přepínat). Chování:
            //   1. existuje session stejného rootfs  → jen se na ni přepneme,
            //   2. neexistuje                        → vytvoří se NOVÁ session
            //      (ostatní distra běží dál na pozadí).
            val newRootfsDirName = intent.getStringExtra("rootfsDirName")
            if (newRootfsDirName != null) {
                val sameRootfs =
                    activeSessions.firstOrNull { s ->
                        val sid = TerminalService.getSessionId(s)
                        val d = if (sid != null) TerminalService.sessionDistros[sid] else null
                        // null distro = stará/neznámá session — default je kali
                        d == newRootfsDirName || (d == null && newRootfsDirName == "nh/distro/kali")
                    }
                if (sameRootfs != null) {
                    Log.i(TAG, "Attaching to existing active session (same rootfs: $newRootfsDirName)")
                    val mountStorageSaved = getSharedPreferences("vpn_settings", MODE_PRIVATE).getBoolean("mount_storage", false)
                    lifecycleScope.launch(Dispatchers.IO) {
                        val cfg =
                            try {
                                val isDocker =
                                    intent.getBooleanExtra("isDockerImage", false) ||
                                        newRootfsDirName.startsWith("docker-") ||
                                        newRootfsDirName.startsWith("oci-") ||
                                        newRootfsDirName.startsWith("nh/distro/docker/")
                                val distroId2 = newRootfsDirName.substringAfterLast("/")
                                val bootMode2 = loadBootMode(this@TerminalActivity, distroId2, DEFAULT_BOOT_MODE)
                                ProotManager.setupProotEnvironment(
                                    this@TerminalActivity,
                                    newRootfsDirName,
                                    mountStorageSaved,
                                    null,
                                    false,
                                    isDocker,
                                    bootMode2,
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Attach setup failed for $newRootfsDirName", e)
                                null
                            }
                        withContext(Dispatchers.Main) {
                            config = cfg
                            switchToSession(sameRootfs)
                        }
                    }
                    return
                }
                Log.i(
                    TAG,
                    "No running session for $newRootfsDirName — creating NEW session " +
                        "(${activeSessions.size} existing session(s) of other rootfs keep running)",
                )
                // Fall through to create new session below — nic se nemaže.
            } else {
                // No new intent — attach to existing
                Log.i(TAG, "Attaching to existing active session (no new intent)")
                val lastSession = activeSessions.last()
                val sessionId = TerminalService.getSessionId(lastSession)
                val distroName = if (sessionId != null) TerminalService.sessionDistros[sessionId] ?: "nh/distro/kali" else "nh/distro/kali"
                val mountStorageSaved = getSharedPreferences("vpn_settings", MODE_PRIVATE).getBoolean("mount_storage", false)
                lifecycleScope.launch(Dispatchers.IO) {
                    val cfg =
                        try {
                            val isDocker =
                                intent.getBooleanExtra("isDockerImage", false) ||
                                    distroName.startsWith("docker-") ||
                                    distroName.startsWith("oci-") ||
                                    distroName.startsWith("nh/distro/docker/")
                            val distroId3 = distroName.substringAfterLast("/")
                            val bootMode3 = loadBootMode(this@TerminalActivity, distroId3, DEFAULT_BOOT_MODE)
                            ProotManager.setupProotEnvironment(
                                this@TerminalActivity,
                                distroName,
                                mountStorageSaved,
                                null,
                                false,
                                isDocker,
                                bootMode3,
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Attach setup failed for $distroName", e)
                            null
                        }
                    withContext(Dispatchers.Main) {
                        config = cfg
                        switchToSession(lastSession)
                    }
                }
                return
            }
        }
        val rootfsDirName = intent.getStringExtra("rootfsDirName") ?: "nh/distro/kali"
        val mountStorage = intent.getBooleanExtra("mountStorage", false)
        val customCommand = intent.getStringExtra("customCommand")
        val ashellMode = intent.getBooleanExtra("ashellMode", false)
        // Docker image: rozpozná se podle extra, prefixu adresáře nebo fallback na .docker_image soubor
        val isDockerImage =
            intent.getBooleanExtra("isDockerImage", false) ||
                rootfsDirName.startsWith("docker-") ||
                rootfsDirName.startsWith("oci-") ||
                rootfsDirName.startsWith("nh/distro/docker/") ||
                File(filesDir, "$rootfsDirName/.docker_image").exists()

        // ashell: escape z prootu do host app shellu (/system/bin/sh, bez PRoot)
        if ((ashellMode || rootfsDirName == "ashell-host") &&
            rootfsDirName != "ashell-adb"
        ) {
            startAshellSession()
            return
        }

        // ashell-adb: shell pod uid 2000 přes shell_daemon
        if (rootfsDirName == "ashell-adb") {
            startAdbShellSession()
            return
        }

        val rootfsDir = File(filesDir, rootfsDirName)
        val setupDoneFile = File(rootfsDir, "root/.setup_done")

        if (!setupDoneFile.exists()) {
            android.app.AlertDialog
                .Builder(this)
                .setTitle("Detekce Rootu")
                .setMessage(
                    "Má Vaše zařízení ROOT oprávnění (Magisk / KernelSU)?\n\nPokud zvolíte 'Ano', nebudou se vytvářet falešné mock soubory pro systémové příkazy (jako systemctl, sysctl, atd.), protože je nebudete potřebovat.",
                ).setPositiveButton("Ano") { _, _ ->
                    startSetup(rootfsDirName, mountStorage, customCommand, true, isDockerImage)
                }.setNegativeButton("Ne") { _, _ ->
                    startSetup(rootfsDirName, mountStorage, customCommand, false, isDockerImage)
                }.setCancelable(false)
                .show()
        } else {
            startSetup(rootfsDirName, mountStorage, customCommand, false, isDockerImage)
        }
    }

    /**
     * ashell — spustí interaktivní host shell (bez PRoot, mimo guest).
     * Používá /system/bin/sh s HOME = filesDir. Slouží jako escape z prootu.
     * Automaticky přidá do PATH cesty k binárkám z nainstalovaných distribucí
     * (Kali, Parrot, Docker), aby byly dostupné i mimo PRoot container.
     */
    private fun startAdbShellSession() {
        Log.i(TAG, "startAdbShellSession: shell pod uid 2000 přes shell_daemon")
        val cwd = filesDir

        // Spustit libshelldaemon.so --attach jako command pro TerminalSession.
        // Binarka je extrahovana z jniLibs do nativeLibraryDir (deploy cestou
        // (libshelldaemon.so z jniLibs), NIKOLI v filesDir.
        // ShellDaemonDeployTest hleda presne tento retezec "ShellDaemonClient.binaryPath"
        // v source kodu (guard proti navratu na filesDir/shell_daemon) — nerozdelovat na 2 radky.
        @Suppress("ktlint:standard:chain-method-continuation")
        val daemonBin = com.linux_core.core.terminal.ShellDaemonClient.binaryPath(applicationContext)
        if (!daemonBin.exists()) {
            showError("libshelldaemon.so nenalezena v nativeLibraryDir")
            return
        }

        val token =
            com.linux_core.core.terminal.ShellDaemonClient
                .ensureToken(applicationContext)
        val cmd =
            arrayOf(
                daemonBin.absolutePath,
                "--attach",
                "--port=13341",
                "--token=$token",
            )

        val env =
            mutableListOf(
                "HOME=${filesDir.absolutePath}",
                "USER=shell",
                "TERM=xterm-256color",
                "ANDROID_DATA=/data",
                "ANDROID_ROOT=/system",
            )

        val cfg =
            com.linux_core.core.rootfs.ProotConfig(
                command = cmd,
                cwd = cwd.absolutePath,
                env = env.toTypedArray(),
                prootPath = "",
                rootfsDir = "(adb-shell)",
            )
        config = cfg
        startTerminalSession(cfg)
    }

    private fun startAshellSession() {
        Log.i(TAG, "startAshellSession: escape proot → host app shell")
        val cwd = filesDir

        // Host-side nástroje (mimo proot) pro kontext com.linux_core.
        // ashell je ESCAPE z PRootu → čistý host shell. Žádné distro (proot)
        // cesty do PATH nepatří: glibc binárky z rootfs na Android hostu
        // stejně neběží (rseq → SIGSYS) a míchání cest všech distro způsobuje
        // nejednoznačnou rezoluci. nh CLI je v filesDir, host nástroje
        // (boot, proot, nano, rg, ...) v filesDir/usr/bin — obojí níže.
        // Adresáře files/usr/{bin,lib} vytvoří a binárky z assets sem nasadí
        // ProotManager.setupProotEnvironment (fáze deploy).
        val hostPrefix = File(filesDir, "usr")
        val hostPrefixBin = File(hostPrefix, "bin")
        val hostPrefixLib = File(hostPrefix, "lib")

        // Sestav PATH: Android host cesty + filesDir (nh CLI) + host PREFIX/bin
        val basePath = "/system/bin:/system/xbin:/vendor/bin"
        val extraPaths = listOf(filesDir.absolutePath, hostPrefixBin.absolutePath).joinToString(":")
        val fullPath = "$basePath:$extraPaths"

        Log.i(TAG, "ashell PATH: $fullPath")
        Log.i(TAG, "ashell PREFIX=${hostPrefix.absolutePath} (bin/lib ready)")

        val cmd = arrayOf("/system/bin/sh", "-i")
        // ashell.conf -> ENV skript pro interaktivní mksh (čte $ENV při startu):
        // vynech `block` řádky (ty řeší /shell API), expanduj ${FILES_DIR}.
        var ashellEnvScript: String? = null
        try {
            val conf = File(filesDir, "ashell.conf")
            if (conf.exists()) {
                val filtered =
                    conf
                        .readLines()
                        .filterNot {
                            val t = it.trim()
                            t == "block" || t.startsWith("block ") || t.startsWith("block\t")
                        }.joinToString("\n") { it.replace("\${FILES_DIR}", filesDir.absolutePath) }
                val envFile = File(filesDir, ".ashell_env")
                envFile.writeText(filtered + "\n")
                ashellEnvScript = envFile.absolutePath
                Log.i(TAG, "ashell env script ready: $ashellEnvScript")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ashell.conf -> ENV failed: ${e.message}")
        }
        val env =
            mutableListOf(
                "HOME=${filesDir.absolutePath}",
                "USER=app",
                "PATH=$fullPath",
                "PREFIX=${hostPrefix.absolutePath}",
                "LD_LIBRARY_PATH=${hostPrefixLib.absolutePath}:/system/lib64:/system/lib",
                "TERM=xterm-256color",
                "ANDROID_DATA=/data",
                "ANDROID_ROOT=/system",
            )
        // ENV skript se sourcuje po startu shellu — unset LD_LIBRARY_PATH z configu
        // tím bezpečně přebije spawn hodnotu výše (viz SIGBUS lesson v AGENTS.md).
        ashellEnvScript?.let { env.add("ENV=$it") }
        val cfg =
            com.linux_core.core.rootfs.ProotConfig(
                command = cmd,
                cwd = cwd.absolutePath,
                env = env.toTypedArray(),
                prootPath = "",
                rootfsDir = "(host)",
            )
        config = cfg
        startTerminalSession(cfg)
    }

    private fun startSetup(
        rootfsDirName: String,
        mountStorage: Boolean,
        customCommand: String?,
        hasRoot: Boolean,
        isDockerImage: Boolean = false,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result =
                try {
                    val distroId4 = rootfsDirName.substringAfterLast("/")
                    val bootMode4 = loadBootMode(this@TerminalActivity, distroId4, DEFAULT_BOOT_MODE)
                    ProotManager.setupProotEnvironment(
                        this@TerminalActivity,
                        rootfsDirName,
                        mountStorage,
                        customCommand,
                        hasRoot,
                        isDockerImage,
                        bootMode4,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Setup failed for $rootfsDirName", e)
                    null
                }
            withContext(Dispatchers.Main) {
                if (result == null) {
                    showError("Setup failed: $rootfsDirName")
                } else {
                    config = result
                    startTerminalSession(result)
                }
            }
        }
    }

    private fun startTerminalSession(config: ProotConfig) {
        Log.i(TAG, "startTerminalSession")
        val session =
            try {
                TerminalService.createSession(this, config, terminalView) { showError(it) }
            } catch (e: Exception) {
                showError("Session error: ${e.message}")
                return
            }

        switchToSession(session)
        updateSessionDrawer()

        pendingNanoCommand?.let { cmd ->
            pendingNanoCommand = null
            terminalView.postDelayed({
                session.write("\u0003\u0015$cmd\r")
            }, 2500)
        }
    }

    fun showSoftKeyboard() {
        terminalView.requestFocus()
        terminalView.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

            @Suppress("DEPRECATION")
            val success = imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            Log.d(TAG, "showSoftInput request sent, success=$success")
        }, 300)
    }

    internal fun showError(message: String) {
        Log.e(TAG, "Error: $message")
        errorText.text = message
        errorLayout.visibility = View.VISIBLE
        terminalView.visibility = View.GONE
    }
}
