package com.linux_core.ui.terminal

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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ProgressBar
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.drawerlayout.widget.DrawerLayout
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.linux_core.core.ProotConfig
import com.linux_core.core.ProotManager
import com.linux_core.core.TerminalService
import com.linux_core.core.KeyType
import com.linux_core.core.HackerKeyboardRows
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import androidx.lifecycle.lifecycleScope


class TerminalActivity : ComponentActivity() {
    companion object {
        private const val TAG = "TerminalActivity"
        @Volatile
        var instance: TerminalActivity? = null
    }


    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnServiceDirectly()
        } else {
            android.widget.Toast.makeText(this, "VPN permission denied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var terminalView: TerminalView
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorText: TextView
    private var config: ProotConfig? = null
    private var currentSession: TerminalSession? = null
    private val viewClient = TerminalViewClientImpl()

    // History and suggestions
    private lateinit var historyManager: com.linux_core.core.HistoryManager
    private val currentCommand = StringBuilder()
    private lateinit var suggestionBar: HorizontalScrollView
    private lateinit var suggestionContainer: LinearLayout

    // Drawer-based Session Management
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var sessionDrawerContainer: LinearLayout
    private var activeDrawerTab = "ALL" // "ALL", "KALI", "PARROT"
    private val drawerTabButtons = HashMap<String, Button>()
    private lateinit var drawerView: FrameLayout
    private lateinit var drawerViewContentLayout: LinearLayout
    private lateinit var drawerHeader: TextView
    private lateinit var tabLayout: LinearLayout
    private lateinit var btnAddSession: Button
    private var isDrawerExpanded = false
    private lateinit var topBar: LinearLayout
    private lateinit var statusTitle: TextView

    // ── Services Panel State ──
    private var isServicesExpanded = false
    private var expandedService: String? = null // "shizuku", "code", "phoenix", or null
    private lateinit var servicesPanel: LinearLayout
    private lateinit var servicesDetailPanel: LinearLayout
    private lateinit var btnServicesToggle: Button
    private lateinit var btnShizuku: Button
    private lateinit var btnCode: Button
    private lateinit var btnPhoenix: Button
    private val servicesUpdateHandler = Handler(Looper.getMainLooper())
    private val servicesPoller = object : Runnable {
        override fun run() {
            if (isServicesExpanded) {
                updateAllServiceIndicators()
                servicesUpdateHandler.postDelayed(this, 5000)
            }
        }
    }

    private var drawerUpdateHandler = Handler(Looper.getMainLooper())
    private val drawerRamUpdater = object : Runnable {
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
    private lateinit var btnCtrl: Button
    private lateinit var btnAlt: Button
    private lateinit var btnShift: Button
    private lateinit var btnToggleKeypad: Button
    private lateinit var specialKeypadPanel: LinearLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var keysContainer: LinearLayout
    private var activeKeyboardTab = "SYMBOLS"
    private val tabsList = listOf("CONTROL", "SYMBOLS", "NAVIGATION", "CTRL COMBOS", "F-KEYS")
    private val tabButtons = HashMap<String, Button>()

    // X11 GUI Desktop integration fields
    private var activeViewMode = "CLI" // "CLI" or "GUI"
    private lateinit var btnCli: Button
    private lateinit var btnGui: Button
    private lateinit var guiContainer: FrameLayout
    private lateinit var guiWebView: WebView
    private lateinit var guiPlaceholderLayout: LinearLayout
    private lateinit var guiPlaceholderTitle: TextView
    private lateinit var guiPlaceholderDesc: TextView
    private lateinit var btnStartGui: Button
    private lateinit var guiProgress: ProgressBar
    private lateinit var toolbarScroll: View
    private val guiScope = CoroutineScope(Dispatchers.Main + Job())
    private var pendingNanoCommand: String? = null
    private lateinit var btnTouchToggle: Button
    private var guiTouchMode = true // true = trackpad (default), false = direct touch

    // Trackpad state variables (Android-level touch interception)
    private var virtualCursorX = 960f
    private var virtualCursorY = 540f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchStartTime = 0L
    private var totalTouchMovement = 0f
    private var isTouchMoving = false
    private var isTwoFingerGesture = false
    private var longPressTriggered = false
    private var twoFingerStartY = 0f
    private var scrollAccum = 0f
    private val longPressHandler = Handler(Looper.getMainLooper())

    // Double tap drag lock states
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var isDragGesture = false

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
        com.linux_core.core.ImmersiveMode.enterImmersive(this)
        val prefs = getSharedPreferences("terminal_prefs", MODE_PRIVATE)
        terminalFontSizeFloat = prefs.getFloat("font_size", 32f)

        viewClient.setActivity(this)
        historyManager = com.linux_core.core.HistoryManager(this)

        // Root DrawerLayout container
        drawerLayout = androidx.drawerlayout.widget.DrawerLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Main content vertical container
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT)
        }

        // Active top bar with menu button, spacer, and GUI switch
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = createRoundedDrawable(Color.parseColor("#07080a"), 0f)
            val padVert = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()
            setPadding(8, padVert, 8, padVert)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.VISIBLE // Visible by default in both CLI and GUI
        }

        // Hamburger Menu button on the left of topBar to slide drawer open
        val btnMenu = Button(this).apply {
            text = "☰"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00FF41"))
            background = createRoundedDrawable(Color.parseColor("#0f1017"), 6f, Color.parseColor("#1e2026"), 1f)
            setPadding(12, 0, 12, 0)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt()
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
        val btnGoToMenu = Button(this).apply {
            text = "🏠"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#0f1017"), 6f, Color.parseColor("#1e2026"), 1f)
            setPadding(12, 0, 12, 0)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt()
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

        val spacer1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        topBar.addView(spacer1)

        // ── Distro title + Services toggle ──
        val distroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusTitle = TextView(this).apply {
            text = "🐉 KALI"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD))
            setTextColor(Color.parseColor("#00FF41"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        distroRow.addView(statusTitle)

        btnServicesToggle = Button(this).apply {
            text = "▼"
            textSize = 9f
            setTextColor(Color.parseColor("#00FF41"))
            background = null
            setPadding(4, 0, 4, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                toggleServicesPanel()
            }
        }
        distroRow.addView(btnServicesToggle)

        topBar.addView(distroRow)

        val spacer2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        topBar.addView(spacer2)

        // CLI/GUI Switch on the right side of topBar
        val guiToggleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = createRoundedDrawable(Color.parseColor("#0c0d12"), 6f, Color.parseColor("#1e2026"), 1f)
            setPadding(2, 2, 2, 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(8, 0, 8, 0)
            }
            gravity = Gravity.CENTER
        }

        btnCli = Button(this).apply {
            text = "🐚 CLI"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            background = createRoundedDrawable(Color.parseColor("#00FF41"), 4f)
            setPadding(10, 0, 10, 0)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics).toInt()
            )
            layoutParams = params
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                switchViewMode("CLI")
            }
        }

        btnGui = Button(this).apply {
            text = "🖥️ GUI"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#0c0d12"), 4f)
            setPadding(10, 0, 10, 0)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics).toInt()
            )
            layoutParams = params
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                switchViewMode("GUI")
            }
        }

        guiToggleLayout.addView(btnCli)
        guiToggleLayout.addView(btnGui)

        btnTouchToggle = Button(this).apply {
            text = "🖱️ Trackpad"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#0f1017"), 6f, Color.parseColor("#1e2026"), 1f)
            setPadding(12, 0, 12, 0)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt()
            ).apply {
                setMargins(8, 0, 8, 0)
            }
            layoutParams = params
            visibility = View.GONE
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                toggleTouchMode()
            }
        }
        topBar.addView(btnTouchToggle)
        topBar.addView(guiToggleLayout)

        mainLayout.addView(topBar)

        // ── Services Panel (collapsible) ──
        servicesPanel = buildServicesPanel()
        mainLayout.addView(servicesPanel)

        servicesDetailPanel = buildServicesDetailPanel()
        mainLayout.addView(servicesDetailPanel)

        val topBarDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#1e2026"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics).toInt()
            )
        }
        mainLayout.addView(topBarDivider)

        // Terminal view container (takes weight = 1f to fill remaining screen space)
        val terminalContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        terminalView = TerminalView(this, null)
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

        terminalContainer.addView(terminalView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        errorLayout = buildErrorOverlay()
        terminalContainer.addView(errorLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Add GUI webview container
        guiContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }

        guiWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // Inject WebSocket interceptor BEFORE noVNC scripts run.
                    // This captures the VNC WebSocket so we can send raw pointer events.
                    view?.evaluateJavascript("""
                (function() {
                    if (window._nhWsPatched) return;
                    window._nhWsPatched = true;
                    window._vncWs = null;

                    // Method 1: Override WebSocket constructor to capture on creation
                    var OrigWS = window.WebSocket;
                    window.WebSocket = function(url, protocols) {
                        var ws = protocols !== undefined ? new OrigWS(url, protocols) : new OrigWS(url);
                        window._vncWs = ws;
                        return ws;
                    };
                    window.WebSocket.prototype = OrigWS.prototype;
                    window.WebSocket.CONNECTING = 0;
                    window.WebSocket.OPEN = 1;
                    window.WebSocket.CLOSING = 2;
                    window.WebSocket.CLOSED = 3;

                    // Method 2: Also patch prototype.send as backup
                    var origSend = OrigWS.prototype.send;
                    OrigWS.prototype.send = function(data) {
                        if (!window._vncWs || window._vncWs.readyState !== 1) {
                            window._vncWs = this;
                        }
                        return origSend.apply(this, arguments);
                    };

                    // Define _vncPtr immediately (uses captured WS when available)
                    window._vncPtr = function(x, y, mask) {
                        var ws = window._vncWs;
                        if (!ws || ws.readyState !== 1) return;
                        var d = new Uint8Array(6);
                        d[0] = 5;  // VNC pointer event message type
                        d[1] = mask;
                        d[2] = (x >> 8) & 0xFF; d[3] = x & 0xFF;
                        d[4] = (y >> 8) & 0xFF; d[5] = y & 0xFF;
                        ws.send(d.buffer);

                        if (window._updateCursor) {
                            window._updateCursor(x, y);
                        }
                    };

                    // Create a custom cursor element if it doesn't exist yet
                    if (!document.getElementById('nh-custom-cursor')) {
                        var cur = document.createElement('div');
                        cur.id = 'nh-custom-cursor';
                        cur.style.position = 'fixed';
                        cur.style.width = '16px';
                        cur.style.height = '16px';
                        cur.style.pointerEvents = 'none';
                        cur.style.zIndex = '999999';
                        cur.style.left = '0px';
                        cur.style.top = '0px';
                        cur.style.display = 'none'; // hide until first move
                        cur.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">' +
                                        '  <path d="M0 0V14.5L4.25 10.25L8 18L11 16.5L7.25 8.75L12.5 8.75Z" fill="white" stroke="black" stroke-width="1.5" stroke-linejoin="miter"/>' +
                                        '</svg>';
                        document.body.appendChild(cur);
                    }

                    window._updateCursor = function(x, y) {
                        var cur = document.getElementById('nh-custom-cursor');
                        if (!cur) return;
                        var canvas = document.querySelector('canvas') || document.getElementById('noVNC_canvas');
                        if (!canvas) {
                            cur.style.left = (x / 1920 * window.innerWidth) + 'px';
                            cur.style.top = (y / 1080 * window.innerHeight) + 'px';
                            cur.style.display = 'block';
                            return;
                        }
                        var rect = canvas.getBoundingClientRect();
                        if (rect.width > 0 && rect.height > 0) {
                            var left = rect.left + (x / canvas.width) * rect.width;
                            var top = rect.top + (y / canvas.height) * rect.height;
                            cur.style.left = left + 'px';
                            cur.style.top = top + 'px';
                            cur.style.display = 'block';
                        } else {
                            cur.style.left = (x / 1920 * window.innerWidth) + 'px';
                            cur.style.top = (y / 1080 * window.innerHeight) + 'px';
                            cur.style.display = 'block';
                        }
                    };
                })();
            """.trimIndent(), null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Hide the noVNC fullscreen button
                    view?.evaluateJavascript("(function() { " +
                        "var css = '#noVNC_fullscreen_button { display: none !important; }'; " +
                        "var head = document.head || document.getElementsByTagName('head')[0]; " +
                        "var style = document.createElement('style'); " +
                        "style.type = 'text/css'; " +
                        "style.appendChild(document.createTextNode(css)); " +
                        "head.appendChild(style); " +
                        "})()", null)

                    // Re-inject WS interceptor as backup (in case onPageStarted was too early)
                    view?.postDelayed({
                        view.evaluateJavascript(getVncPointerHelperScript(), null)
                    }, 3000)
                }
            }

            // Android-level touch interceptor for trackpad mode
            setOnTouchListener(createTrackpadTouchListener())

            visibility = View.GONE
        }
        guiContainer.addView(guiWebView)

        // Custom Cyber-styled VNC Placeholder
        guiPlaceholderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#07080A"))
            setPadding(32, 32, 32, 32)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        guiPlaceholderTitle = TextView(this).apply {
            text = "X11 Graphical Desktop"
            setTextColor(Color.parseColor("#00FF41"))
            textSize = 20f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 8)
            }
            layoutParams = params
        }
        guiPlaceholderLayout.addView(guiPlaceholderTitle)

        guiPlaceholderDesc = TextView(this).apply {
            text = "Start a fully interactive XFCE4 desktop inside Kali/Parrot.\n(On the first boot, packages will be installed automatically)"
            setTextColor(Color.parseColor("#A9B1D6"))
            textSize = 13f
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
            layoutParams = params
        }
        guiPlaceholderLayout.addView(guiPlaceholderDesc)

        guiProgress = ProgressBar(this).apply {
            visibility = View.GONE
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            layoutParams = params
        }
        guiPlaceholderLayout.addView(guiProgress)

        btnStartGui = Button(this).apply {
            text = "START GRAPHICAL DESKTOP"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#00FF41")) // Sleek green action button
            textSize = 12f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(24, 12, 24, 12)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                startDesktopInBackground()
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
        drawerView = FrameLayout(this).apply {
            val params = DrawerLayout.LayoutParams(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 70f, resources.displayMetrics).toInt(),
                DrawerLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.START
            }
            layoutParams = params
            setBackgroundColor(Color.parseColor("#08090d"))
        }

        drawerViewContentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(8, 16, 8, 16)
        }
        drawerView.addView(drawerViewContentLayout)

        drawerHeader = TextView(this).apply {
            text = "🐚 NETHUNTER SESSIONS"
            setTextColor(Color.parseColor("#00FF41"))
            textSize = 15f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
            layoutParams = params
        }
        drawerViewContentLayout.addView(drawerHeader)

        tabLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
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
                val params = LinearLayout.LayoutParams(
                    0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt(), 1f
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

        val drawerScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = true
        }
        
        sessionDrawerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        drawerScroll.addView(sessionDrawerContainer)
        drawerViewContentLayout.addView(drawerScroll)

        btnAddSession = Button(this).apply {
            text = "+ NEW SESSION"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#00FF41")) // Sleek green action button
            textSize = 12f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(24, 12, 24, 12)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
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

        val dragHandle = View(this).apply {
            val params = FrameLayout.LayoutParams(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt(),
                FrameLayout.LayoutParams.MATCH_PARENT
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
                else -> false
            }
        }
        drawerView.addView(dragHandle)

        // Assemble root DrawerLayout
        drawerLayout.addView(mainLayout)
        drawerLayout.addView(drawerView)
        setContentView(drawerLayout)

        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                startDrawerRamUpdateLoop()
            }
            override fun onDrawerClosed(drawerView: View) {
                stopDrawerRamUpdateLoop()
            }
        })

        handleFileIntent(intent)
        setupAndStartSession()
    }

    private fun switchViewMode(mode: String) {
        activeViewMode = mode
        if (mode == "CLI") {
            btnCli.setTextColor(Color.BLACK)
            btnCli.background = createRoundedDrawable(Color.parseColor("#00FF41"), 4f)
            btnGui.setTextColor(Color.WHITE)
            btnGui.background = createRoundedDrawable(Color.parseColor("#0c0d12"), 4f)

            terminalView.visibility = View.VISIBLE
            suggestionBar.visibility = if (historyManager.getSuggestions(currentCommand.toString()).isNotEmpty()) View.VISIBLE else View.GONE
            toolbarScroll.visibility = View.VISIBLE
            guiContainer.visibility = View.GONE
            btnTouchToggle.visibility = View.GONE
            
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
            btnTouchToggle.visibility = View.VISIBLE

            topBar.visibility = View.VISIBLE

            // Hide soft keyboard when switching to GUI
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)

            checkAndLoadGui()
        }
        updateTopbarTitle()
    }

    private fun isPortOpen(port: Int): Boolean {
        return try {
            java.net.Socket("127.0.0.1", port).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun checkAndLoadGui() {
        guiScope.launch {
            val running = withContext(Dispatchers.IO) {
                isPortOpen(6080)
            }
            if (running) {
                guiPlaceholderLayout.visibility = View.GONE
                guiWebView.visibility = View.VISIBLE
                val vncUrl = getVncUrl()
                val currentUrl = guiWebView.url
                if (currentUrl == null || !currentUrl.startsWith("http://127.0.0.1:6080/vnc.html")) {
                    guiWebView.loadUrl(vncUrl)
                }
            } else {
                guiWebView.visibility = View.GONE
                guiPlaceholderLayout.visibility = View.VISIBLE
                guiProgress.visibility = View.GONE
                btnStartGui.visibility = View.VISIBLE
                guiPlaceholderTitle.text = "X11 Graphical Desktop"
                guiPlaceholderDesc.text = "Start a fully interactive XFCE4 desktop inside Kali/Parrot.\n(On the first boot, packages will be installed automatically)"
            }
        }
    }

    private fun getVncUrl(): String {
        return "http://127.0.0.1:6080/vnc.html?autoconnect=true&resize=scale&password=kali_operator&cursor=false&locale=en&lang=en"
    }

    private fun toggleTouchMode() {
        guiTouchMode = !guiTouchMode
        if (guiTouchMode) {
            btnTouchToggle.text = "🖱️ Trackpad"
            btnTouchToggle.setTextColor(Color.parseColor("#00FF41"))
            enableNativeTrackpad()
        } else {
            btnTouchToggle.text = "✋ Touch"
            btnTouchToggle.setTextColor(Color.WHITE)
        }
    }

    /**
     * Reset virtual cursor to center of VNC framebuffer (default 1920x1080).
     */
    private fun enableNativeTrackpad() {
        virtualCursorX = 960f
        virtualCursorY = 540f
        Log.d(TAG, "Trackpad: enabled, cursor at (${virtualCursorX.toInt()},${virtualCursorY.toInt()})")
    }

    /**
     * Returns a JavaScript snippet that ensures window._vncPtr is defined.
     * This is a backup injection — the primary injection happens in onPageStarted
     * which intercepts the WebSocket constructor and prototype.send.
     *
     * This backup handles the case where the WS interceptor from onPageStarted
     * was too early or got overwritten by the page.
     */
    private fun getVncPointerHelperScript(): String {
        return """
        (function() {
            // Re-patch WebSocket.prototype.send to capture WS if not already captured
            if (!window._vncWs) {
                var origSend = WebSocket.prototype.send;
                if (!window._nhSendPatched) {
                    window._nhSendPatched = true;
                    WebSocket.prototype.send = function(data) {
                        if (!window._vncWs || window._vncWs.readyState !== 1) {
                            window._vncWs = this;
                        }
                        return origSend.apply(this, arguments);
                    };
                }
            }

            // Ensure _vncPtr is defined
            window._vncPtr = function(x, y, mask) {
                var ws = window._vncWs;
                if (!ws || ws.readyState !== 1) return;
                var d = new Uint8Array(6);
                d[0] = 5;
                d[1] = mask;
                d[2] = (x >> 8) & 0xFF; d[3] = x & 0xFF;
                d[4] = (y >> 8) & 0xFF; d[5] = y & 0xFF;
                ws.send(d.buffer);

                if (window._updateCursor) {
                    window._updateCursor(x, y);
                }
            };

            // Create a custom cursor element if it doesn't exist yet
            if (!document.getElementById('nh-custom-cursor')) {
                var cur = document.createElement('div');
                cur.id = 'nh-custom-cursor';
                cur.style.position = 'fixed';
                cur.style.width = '16px';
                cur.style.height = '16px';
                cur.style.pointerEvents = 'none';
                cur.style.zIndex = '999999';
                cur.style.left = '0px';
                cur.style.top = '0px';
                cur.style.display = 'none'; // hide until first move
                cur.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">' +
                                '  <path d="M0 0V14.5L4.25 10.25L8 18L11 16.5L7.25 8.75L12.5 8.75Z" fill="white" stroke="black" stroke-width="1.5" stroke-linejoin="miter"/>' +
                                '</svg>';
                document.body.appendChild(cur);
            }

            window._updateCursor = function(x, y) {
                var cur = document.getElementById('nh-custom-cursor');
                if (!cur) return;
                var canvas = document.querySelector('canvas') || document.getElementById('noVNC_canvas');
                if (!canvas) {
                    cur.style.left = (x / 1920 * window.innerWidth) + 'px';
                    cur.style.top = (y / 1080 * window.innerHeight) + 'px';
                    cur.style.display = 'block';
                    return;
                }
                var rect = canvas.getBoundingClientRect();
                if (rect.width > 0 && rect.height > 0) {
                    var left = rect.left + (x / canvas.width) * rect.width;
                    var top = rect.top + (y / canvas.height) * rect.height;
                    cur.style.left = left + 'px';
                    cur.style.top = top + 'px';
                    cur.style.display = 'block';
                } else {
                    cur.style.left = (x / 1920 * window.innerWidth) + 'px';
                    cur.style.top = (y / 1080 * window.innerHeight) + 'px';
                    cur.style.display = 'block';
                }
            };

            // If WS is already captured, test it
            if (window._vncWs && window._vncWs.readyState === 1) {
                window._vncPtr(960, 540, 0);
                console.log('NethunterTrackpad: _vncPtr ready, WS captured');
            } else {
                console.log('NethunterTrackpad: _vncPtr defined, waiting for WS...');
            }
        })();
        """.trimIndent()
    }

    @Suppress("ClickableViewAccessibility")
    private fun createTrackpadTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            // If NOT in trackpad mode, let noVNC handle touch events normally
            if (!guiTouchMode) return@OnTouchListener false

            val pointerCount = event.pointerCount
            val sensitivity = 1.2f

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    touchStartTime = System.currentTimeMillis()
                    totalTouchMovement = 0f
                    isTouchMoving = false
                    isTwoFingerGesture = false
                    longPressTriggered = false

                    val now = System.currentTimeMillis()
                    val dxFromLastTap = event.x - lastTapX
                    val dyFromLastTap = event.y - lastTapY
                    val distFromLastTap = kotlin.math.sqrt(dxFromLastTap * dxFromLastTap + dyFromLastTap * dyFromLastTap)

                    if (now - lastTapTime < 300 && distFromLastTap < 50f) {
                        isDragGesture = true
                        longPressHandler.removeCallbacksAndMessages(null)
                        val cx = virtualCursorX.toInt()
                        val cy = virtualCursorY.toInt()
                        guiWebView.evaluateJavascript(
                            "if(window._vncPtr)window._vncPtr($cx,$cy,1)", null)
                    } else {
                        isDragGesture = false
                        // Long-press timer → right click
                        longPressHandler.removeCallbacksAndMessages(null)
                        longPressHandler.postDelayed({
                            if (!isTouchMoving && totalTouchMovement < 15f && !isTwoFingerGesture) {
                                longPressTriggered = true
                                val cx = virtualCursorX.toInt()
                                val cy = virtualCursorY.toInt()
                                guiWebView.evaluateJavascript(
                                    "if(window._vncPtr)window._vncPtr($cx,$cy,4)", null)
                                guiWebView.postDelayed({
                                    guiWebView.evaluateJavascript(
                                        "if(window._vncPtr)window._vncPtr($cx,$cy,0)", null)
                                }, 100)
                            }
                        }, 500)
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Second finger → enter scroll mode
                    isTwoFingerGesture = true
                    longPressHandler.removeCallbacksAndMessages(null)
                    if (pointerCount >= 2) {
                        twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f
                        scrollAccum = 0f
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isTwoFingerGesture && pointerCount >= 2) {
                        // Two-finger scroll
                        val midY = (event.getY(0) + event.getY(1)) / 2f
                        val delta = midY - twoFingerStartY
                        twoFingerStartY = midY
                        scrollAccum += delta

                        val cx = virtualCursorX.toInt()
                        val cy = virtualCursorY.toInt()
                        while (scrollAccum > 30f) {
                            guiWebView.evaluateJavascript(
                                "if(window._vncPtr){window._vncPtr($cx,$cy,16);" +
                                "setTimeout(function(){window._vncPtr($cx,$cy,0)},10)}", null)
                            scrollAccum -= 30f
                        }
                        while (scrollAccum < -30f) {
                            guiWebView.evaluateJavascript(
                                "if(window._vncPtr){window._vncPtr($cx,$cy,8);" +
                                "setTimeout(function(){window._vncPtr($cx,$cy,0)},10)}", null)
                            scrollAccum += 30f
                        }
                    } else if (!isTwoFingerGesture) {
                        // Single finger → move cursor relative
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        lastTouchX = event.x
                        lastTouchY = event.y
                        totalTouchMovement += kotlin.math.abs(dx) + kotlin.math.abs(dy)

                        if (totalTouchMovement > 15f) {
                            isTouchMoving = true
                            longPressHandler.removeCallbacksAndMessages(null)
                        }

                        // Update virtual cursor, clamped to 0..1920, 0..1080
                        virtualCursorX = (virtualCursorX + dx * sensitivity).coerceIn(0f, 1920f)
                        virtualCursorY = (virtualCursorY + dy * sensitivity).coerceIn(0f, 1080f)

                        val cx = virtualCursorX.toInt()
                        val cy = virtualCursorY.toInt()
                        val mask = if (isDragGesture) 1 else 0
                        guiWebView.evaluateJavascript(
                            "if(window._vncPtr)window._vncPtr($cx,$cy,$mask)", null)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacksAndMessages(null)

                    val cx = virtualCursorX.toInt()
                    val cy = virtualCursorY.toInt()

                    if (isDragGesture) {
                        // End of drag
                        guiWebView.evaluateJavascript(
                            "if(window._vncPtr)window._vncPtr($cx,$cy,0)", null)
                        lastTapTime = 0L
                    } else if (!isTwoFingerGesture && !longPressTriggered) {
                        val elapsed = System.currentTimeMillis() - touchStartTime
                        if (elapsed < 300 && totalTouchMovement < 15f) {
                            // Quick tap → left click (down then up)
                            guiWebView.evaluateJavascript(
                                "if(window._vncPtr)window._vncPtr($cx,$cy,1)", null)
                            guiWebView.postDelayed({
                                guiWebView.evaluateJavascript(
                                    "if(window._vncPtr)window._vncPtr($cx,$cy,0)", null)
                            }, 80)

                            lastTapTime = System.currentTimeMillis()
                            lastTapX = event.x
                            lastTapY = event.y
                        }
                    }

                    isTouchMoving = false
                    isTwoFingerGesture = false
                    longPressTriggered = false
                    isDragGesture = false
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    // One finger lifted, others still down
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacksAndMessages(null)
                    isTouchMoving = false
                    isTwoFingerGesture = false
                    longPressTriggered = false
                    isDragGesture = false
                    true
                }

                else -> true
            }
        }
    }

    private fun startDesktopInBackground() {
        btnStartGui.visibility = View.GONE
        guiProgress.visibility = View.VISIBLE
        guiPlaceholderTitle.text = "Initializing Desktop..."
        guiPlaceholderDesc.text = "Running setup and launching graphical server in guest container.\nThis may take up to 2-3 minutes if packages are being installed."
        
        guiScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val launcher = File(filesDir, "launcher.sh").absolutePath
                    val builder = ProcessBuilder("/system/bin/sh", launcher, "nethunter-desktop start")
                    builder.directory(filesDir)
                    val process = builder.start()
                    // Wait for the process to finish to reap it (prevent zombie)
                    val exitCode = process.waitFor()
                    Log.i(TAG, "Desktop launcher process finished with exit code: $exitCode")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start desktop process: ${e.message}")
                }
            }
            
            // Poll for port 6080 to open
            var attempts = 0
            while (attempts < 120) {
                delay(2000)
                val running = withContext(Dispatchers.IO) { isPortOpen(6080) }
                if (running) {
                    break
                }
                attempts++
            }
            
            checkAndLoadGui()
        }
    }

    private fun startVpnServiceDirectly() {
        val intent = Intent(this, com.linux_core.core.VpnCaptureService::class.java).apply {
            action = com.linux_core.core.VpnCaptureService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, com.linux_core.core.VpnCaptureService::class.java).apply {
            action = com.linux_core.core.VpnCaptureService.ACTION_STOP
        }
        startService(intent)
    }



    override fun onResume() {
        super.onResume()
        if (isServicesExpanded) {
            updateAllServiceIndicators()
            servicesUpdateHandler.post(servicesPoller)
        }
        Log.d(TAG, "onResume - requesting focus")
        terminalView.requestFocus()
        if (specialKeypadPanel.visibility != View.VISIBLE) {
            showSoftKeyboard()
        }

        val serviceSessions = TerminalService.sessions
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // ashell escape: pokud je extra nastavený na novém intentu (např. singleTask
        // nedovolil vytvoření nové instance), spustíme ashell session v téhle aktivitě
        if (intent.getBooleanExtra("ashellMode", false) ||
            intent.getStringExtra("rootfsDirName") == "ashell-host") {
            startAshellSession()
            return
        }
        handleFileIntent(intent)
    }

    private fun handleFileIntent(intent: Intent) {
        val action = intent.action
        if (Intent.ACTION_VIEW == action || Intent.ACTION_EDIT == action) {
            val uri = intent.data ?: return
            val fileName = getFileNameFromUri(uri)
            
            // Determine rootfs directory name
            var rootfsDirName = intent.getStringExtra("rootfsDirName")
            if (rootfsDirName == null) {
                val kaliSetup = File(File(filesDir, "kali-arm64"), "root/.setup_done")
                val parrotSetup = File(File(filesDir, "parrot-arm64"), "root/.setup_done")
                rootfsDirName = when {
                    kaliSetup.exists() -> "kali-arm64"
                    parrotSetup.exists() -> "parrot-arm64"
                    else -> "kali-arm64"
                }
            }
            
            val copiedFile = copyUriToChrootTmp(uri, fileName, rootfsDirName)
            if (copiedFile != null) {
                val command = "nano /tmp/nethunter_edit_$fileName"
                
                // If GUI is active, automatically switch to CLI so they see the editor
                if (activeViewMode != "CLI") {
                    switchViewMode("CLI")
                }
                
                val activeSession = currentSession ?: TerminalService.sessions.firstOrNull()
                if (activeSession != null) {
                    // Send command to active session
                    switchToSession(activeSession)
                    terminalView.post {
                        terminalView.postDelayed({
                            activeSession.write("\u0003\u0015$command\r")
                        }, 500)
                    }
                } else {
                    // Save for when the session starts
                    pendingNanoCommand = command
                }
            }
        }
    }

    private fun getFileNameFromUri(uri: android.net.Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) {
                        result = cursor.getString(idx)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query displayName: ${e.message}")
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        // Sanitize filename to avoid weird shell characters
        return (result ?: "unnamed_file").replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun copyUriToChrootTmp(uri: android.net.Uri, fileName: String, rootfsDirName: String): File? {
        try {
            val destDir = File(filesDir, "$rootfsDirName/tmp")
            if (!destDir.exists()) {
                destDir.mkdirs()
            }
            val destFile = File(destDir, "nethunter_edit_$fileName")
            contentResolver.openInputStream(uri)?.use { inputStream ->
                destFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.i(TAG, "Successfully copied $uri to ${destFile.absolutePath}")
            return destFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy URI to chroot tmp: ${e.message}")
            return null
        }
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        com.linux_core.core.VpnCaptureService.onStateChangeListener = null
        servicesUpdateHandler.removeCallbacks(servicesPoller)
        super.onDestroy()
        guiScope.cancel()
        for (session in TerminalService.sessions) {
            TerminalService.detachView(session)
        }
        currentSession = null
    }

    private fun buildSuggestionBar(): HorizontalScrollView {
        suggestionBar = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor("#1a1b26"))
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }

        suggestionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(8, 4, 8, 4)
        }

        suggestionBar.addView(suggestionContainer)
        return suggestionBar
    }

    fun updateSuggestions() {
        runOnUiThread {
            val input = currentCommand.toString()
            val suggestions = historyManager.getSuggestions(input)

            if (suggestions.isEmpty()) {
                suggestionBar.visibility = View.GONE
            } else {
                suggestionBar.visibility = View.VISIBLE
                suggestionContainer.removeAllViews()
                for (sug in suggestions) {
                    val btn = Button(this).apply {
                        text = sug
                        textSize = 10f
                        isAllCaps = false
                        typeface = Typeface.MONOSPACE
                        setTextColor(Color.parseColor("#a9b1d6"))
                        setBackgroundColor(Color.parseColor("#24283b"))
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt()
                        ).apply {
                            setMargins(4, 2, 4, 2)
                        }
                        layoutParams = params
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            applySuggestion(sug)
                        }
                    }
                    suggestionContainer.addView(btn)
                }
            }
        }
    }

    private fun applySuggestion(suggestion: String) {
        // Clear current line using Ctrl+U (\u0015)
        currentSession?.write("\u0015")
        currentSession?.write(suggestion)
        currentCommand.setLength(0)
        currentCommand.append(suggestion)
        updateSuggestions()
        terminalView.requestFocus()
    }

    fun onTerminalInput(codePoint: Int) {
        if (codePoint == 127 || codePoint == 8) { // Backspace
            if (currentCommand.isNotEmpty()) {
                currentCommand.setLength(currentCommand.length - 1)
            }
        } else if (codePoint in 32..126) { // Printable chars
            currentCommand.append(codePoint.toChar())
        }
        updateSuggestions()
    }

    fun onTerminalEnter() {
        val cmd = currentCommand.toString().trim()
        if (cmd.isNotEmpty()) {
            historyManager.addCommand(cmd)
        }
        currentCommand.setLength(0)
        updateSuggestions()
    }

    fun resetCurrentCommand() {
        currentCommand.setLength(0)
        updateSuggestions()
    }

    fun updateSessionDrawer() {
        runOnUiThread {
            if (isDrawerExpanded) {
                // Expanded mode padding & visibility
                drawerViewContentLayout.setPadding(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt(),
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt(),
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt(),
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
                )
                drawerHeader.visibility = View.VISIBLE
                
                // Futuristic console header
                val ssb = android.text.SpannableStringBuilder()
                ssb.append("🛰️ OPERATOR CONSOLE\n")
                val startRam = ssb.length
                ssb.append("[RAM: ${getTotalRamUsage()}]")
                ssb.setSpan(
                    android.text.style.ForegroundColorSpan(Color.parseColor("#00FF41")),
                    0, startRam,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                ssb.setSpan(
                    android.text.style.ForegroundColorSpan(Color.parseColor("#00E5FF")),
                    startRam, ssb.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                drawerHeader.text = ssb
                
                tabLayout.visibility = View.VISIBLE
                btnAddSession.visibility = View.VISIBLE
                
                // Update drawer tab button styling
                drawerTabButtons.forEach { (tabCode, btn) ->
                    val isSel = (tabCode == activeDrawerTab)
                    btn.setTextColor(if (isSel) Color.BLACK else Color.WHITE)
                    btn.background = if (isSel) {
                        createRoundedDrawable(Color.parseColor("#00FF41"), 6f)
                    } else {
                        createRoundedDrawable(Color.parseColor("#12131a"), 6f, Color.parseColor("#1e2026"), 1f)
                    }
                }
            } else {
                // Minimized mode padding & visibility
                drawerViewContentLayout.setPadding(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt(),
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt(),
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt(),
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
                )
                drawerHeader.visibility = View.GONE
                tabLayout.visibility = View.GONE
                btnAddSession.visibility = View.GONE
            }

            val serviceSessions = TerminalService.sessions
            sessionDrawerContainer.removeAllViews()

            if (!isDrawerExpanded) {
                // In minimized mode, add a small GUI toggle button at the top of the session list
                val btnGuiToggleMin = Button(this).apply {
                    text = "🖥️"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    background = createCircularDrawable(Color.parseColor("#12131a"), Color.parseColor("#1e2026"), 1f)
                    val sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics).toInt()
                    val params = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                        setMargins(0, 4, 0, 16)
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    layoutParams = params
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        drawerLayout.closeDrawer(Gravity.START)
                        switchViewMode("GUI")
                    }
                }
                sessionDrawerContainer.addView(btnGuiToggleMin)
            }

            for (i in 0 until serviceSessions.size) {
                val session = serviceSessions[i]
                val distro = TerminalService.getSessionDistro(session)
                
                // Filtering based on active tab (only in expanded mode)
                if (isDrawerExpanded) {
                    if (activeDrawerTab == "KALI" && !distro.contains("kali")) continue
                    if (activeDrawerTab == "PARROT" && !distro.contains("parrot")) continue
                }

                val isActive = (session == currentSession)
                val isIgnored = TerminalService.isSessionVpnIgnored(session)
                val isParrot = distro.contains("parrot")
                val distroBadge = if (isParrot) "🦜" else "🐉"
                val memBytes = com.linux_core.core.ProcessResolver.getSessionMemoryUsage(session)
                val memMb = memBytes.toDouble() / (1024.0 * 1024.0)
                val memStr = String.format(java.util.Locale.US, "%.1f MB", memMb)
                
                // Vertical container row for the session card
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    
                    val pxPaddingHoriz = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, if (isDrawerExpanded) 12f else 6f, resources.displayMetrics).toInt()
                    val pxPaddingVert = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, if (isDrawerExpanded) 12f else 8f, resources.displayMetrics).toInt()
                    setPadding(pxPaddingHoriz, pxPaddingVert, pxPaddingHoriz, pxPaddingVert)
                    
                    background = if (isActive) {
                        createRoundedDrawable(Color.parseColor("#121b16"), 8f, Color.parseColor("#00FF41"), 1f)
                    } else if (isIgnored) {
                        createRoundedDrawable(Color.parseColor("#1c150c"), 8f, Color.parseColor("#FF9900"), 1f)
                    } else {
                        createRoundedDrawable(Color.parseColor("#090a0f"), 8f, Color.parseColor("#1e2026"), 1f)
                    }
                    
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 6, 0, 6)
                    }
                    layoutParams = params
                }

                // Glow/Indicator vertical line on the left side of the row (only in expanded mode)
                if (isDrawerExpanded) {
                    val indicator = View(this).apply {
                        val colorStr = if (isActive) "#00FF41" else if (isIgnored) "#FF9900" else "#20222e"
                        background = createRoundedDrawable(Color.parseColor(colorStr), 2f)
                        val params = LinearLayout.LayoutParams(
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt(),
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
                        ).apply {
                            setMargins(0, 0, 10, 0)
                        }
                        layoutParams = params
                    }
                    row.addView(indicator)
                }

                if (isDrawerExpanded) {
                    val label = TextView(this).apply {
                        val customName = TerminalService.getSessionName(session)
                        val baseText = if (!customName.isNullOrEmpty()) customName else "Session ${i + 1}"
                        
                        val ssbLabel = android.text.SpannableStringBuilder()
                        ssbLabel.append("$distroBadge ")
                        val nameStart = ssbLabel.length
                        ssbLabel.append(baseText)
                        ssbLabel.setSpan(
                            android.text.style.ForegroundColorSpan(if (isActive) Color.parseColor("#00FF41") else Color.WHITE),
                            nameStart, ssbLabel.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        
                        ssbLabel.append("\n")
                        val memStart = ssbLabel.length
                        ssbLabel.append("  RAM: $memStr")
                        ssbLabel.setSpan(
                            android.text.style.ForegroundColorSpan(Color.parseColor("#A9B1D6")),
                            memStart, ssbLabel.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        
                        if (isIgnored) {
                            ssbLabel.append(" ")
                            val vpnStart = ssbLabel.length
                            ssbLabel.append("[BYPASS]")
                            ssbLabel.setSpan(
                                android.text.style.ForegroundColorSpan(Color.parseColor("#FF9900")),
                                vpnStart, ssbLabel.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        
                        text = ssbLabel
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        
                        val params = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                        layoutParams = params
                    }
                    row.addView(label)

                    // Quick Close Button on the right
                    val btnClose = TextView(this).apply {
                        text = "✕"
                        textSize = 12f
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setTextColor(Color.parseColor("#A9B1D6"))
                        gravity = Gravity.CENTER
                        background = createRoundedDrawable(Color.parseColor("#1c1d27"), 12f)
                        val sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
                        val params = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                            setMargins(8, 0, 0, 0)
                        }
                        layoutParams = params
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            closeSession(session)
                        }
                    }
                    row.addView(btnClose)
                } else {
                    // Minimized Mode: Show ONLY the distro badge emoji centered in a circular outline
                    val emojiLabel = TextView(this).apply {
                        text = distroBadge
                        textSize = 18f
                        gravity = Gravity.CENTER
                        background = if (isActive) {
                            createCircularDrawable(Color.parseColor("#121b16"), Color.parseColor("#00FF41"), 1.5f)
                        } else {
                            createCircularDrawable(Color.parseColor("#090a0f"), Color.parseColor("#1e2026"), 1f)
                        }
                        val sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics).toInt()
                        val params = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                        }
                        layoutParams = params
                    }
                    row.addView(emojiLabel)
                }

                // Set listeners on the entire row card
                row.setOnClickListener {
                    row.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    switchToSession(session)
                }

                if (isDrawerExpanded) {
                    row.setOnLongClickListener {
                        row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showRenameDialog(session, i + 1)
                        true
                    }
                }

                sessionDrawerContainer.addView(row)
            }
        }
    }

    private fun createRoundedDrawable(
        backgroundColor: Int,
        cornerRadiusDp: Float,
        strokeColor: Int = 0,
        strokeWidthDp: Float = 0f
    ): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(backgroundColor)
            val radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, cornerRadiusDp, resources.displayMetrics
            )
            setCornerRadius(radiusPx)
            if (strokeColor != 0 && strokeWidthDp > 0f) {
                val strokeWidthPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, strokeWidthDp, resources.displayMetrics
                ).toInt()
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    private fun createCircularDrawable(
        backgroundColor: Int,
        strokeColor: Int = 0,
        strokeWidthDp: Float = 0f
    ): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(backgroundColor)
            if (strokeColor != 0 && strokeWidthDp > 0f) {
                val strokeWidthPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, strokeWidthDp, resources.displayMetrics
                ).toInt()
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    private fun updateTopbarTitle() {
        runOnUiThread {
            if (!::statusTitle.isInitialized) return@runOnUiThread
            val session = currentSession
            if (session != null) {
                val distro = TerminalService.getSessionDistro(session)
                val isParrot = distro.contains("parrot")
                val distroBadge = if (isParrot) "🦜 PARROT OS" else "🐉 KALI NetHunter"
                statusTitle.text = "$distroBadge [${activeViewMode}]"
                statusTitle.setTextColor(if (isParrot) Color.parseColor("#00E5FF") else Color.parseColor("#00FF41"))
            } else {
                statusTitle.text = "🐉 NETHUNTER OPERATOR"
                statusTitle.setTextColor(Color.parseColor("#00FF41"))
            }
        }
    }

    private fun getTotalRamUsage(): String {
        return try {
            val actManager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val total = memInfo.totalMem
            val avail = memInfo.availMem
            val used = total - avail
            val usedGb = used.toDouble() / (1024 * 1024 * 1024)
            val totalGb = total.toDouble() / (1024 * 1024 * 1024)
            String.format("%.1f GB / %.1f GB", usedGb, totalGb)
        } catch (e: Exception) {
            "RAM: N/A"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %cBs", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun startDrawerRamUpdateLoop() {
        drawerUpdateHandler.removeCallbacks(drawerRamUpdater)
        drawerUpdateHandler.post(drawerRamUpdater)
    }

    private fun stopDrawerRamUpdateLoop() {
        drawerUpdateHandler.removeCallbacks(drawerRamUpdater)
    }

    private fun showRenameDialog(session: TerminalSession, defaultIndex: Int) {
        val currentName = TerminalService.getSessionName(session) ?: "Session $defaultIndex"
        val input = android.widget.EditText(this).apply {
            setText(currentName)
            setSingleLine(true)
            setSelection(currentName.length)
        }
        
        val container = android.widget.FrameLayout(this).apply {
            val padding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics
            ).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(input)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Rename Session")
            .setMessage("Enter custom name for this session:")
            .setView(container)
            .setPositiveButton("Rename") { dialog, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    TerminalService.setSessionName(session, newName)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun addNewSession() {
        val cfg = config ?: return
        Log.i(TAG, "addNewSession")
        val session = try {
            TerminalService.createSession(this, cfg, terminalView) { showError(it) }
        } catch (e: Exception) {
            showError("Session error: ${e.message}")
            return
        }

        switchToSession(session)
        updateSessionDrawer()
    }

    private fun switchToSession(session: TerminalSession) {
        currentSession?.let { TerminalService.detachView(it) }
        currentSession = session
        TerminalService.attachView(session, terminalView)
        terminalView.post {
            terminalView.requestFocus()
            terminalView.onScreenUpdated()
        }
        updateSessionDrawer()
        updateTopbarTitle()
    }

    private fun closeSession(session: TerminalSession) {
        TerminalService.removeSession(session)
        val remaining = TerminalService.sessions
        if (remaining.isEmpty()) {
            finish()
        } else {
            if (currentSession == session) {
                currentSession = null
                switchToSession(remaining[0])
            } else {
                updateSessionDrawer()
            }
        }
    }

    fun onSessionEnded(session: TerminalSession) {
        val remaining = TerminalService.sessions
        if (!remaining.contains(session)) {
            if (remaining.isEmpty()) finish()
            else updateSessionDrawer()
            return
        }
        if (remaining.isEmpty()) {
            finish()
        } else {
            if (currentSession == session) {
                currentSession = null
                switchToSession(remaining[0])
            } else {
                updateSessionDrawer()
            }
        }
    }

    private fun buildExtraKeysToolbar(): View {
        val rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor("#090a0f"))
            setPadding(0, 4, 0, 4)
        }

        // Setup ViewPager2
        val viewPager = androidx.viewpager2.widget.ViewPager2(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt())
        }

        // Setup Dot indicator layout
        val dotsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
            ).apply {
                setMargins(0, 2, 0, 4)
            }
        }

        // Define Pages
        val page1 = listOf(
            "ESC" to { sendKey("\u001b") },
            "TAB" to { sendKey("\t") },
            "CTRL" to { toggleCtrlModifier() },
            "ALT" to { toggleAltModifier() },
            "SHIFT" to { toggleShiftModifier() },
            "⌨️" to { toggleSpecialKeypad(specialKeypadPanel.visibility == View.GONE) }
        )

        val page2 = listOf(
            "|" to { sendKey("|") },
            "/" to { sendKey("/") },
            "\\" to { sendKey("\\") },
            ":" to { sendKey(":") },
            "-" to { sendKey("-") },
            "_" to { sendKey("_") },
            "~" to { sendKey("~") },
            "=" to { sendKey("=") }
        )

        val page3 = listOf(
            "←" to { sendKey("\u001b[D") },
            "↑" to { sendKey("\u001b[A") },
            "↓" to { sendKey("\u001b[B") },
            "→" to { sendKey("\u001b[C") },
            "Home" to { sendKey("\u001b[H") },
            "End" to { sendKey("\u001b[F") }
        )

        val page4 = listOf(
            "F1" to { sendKey("\u001bOP") },
            "F2" to { sendKey("\u001bOQ") },
            "F3" to { sendKey("\u001bOR") },
            "F4" to { sendKey("\u001bOS") },
            "F5" to { sendKey("\u001b[15~") },
            "F6" to { sendKey("\u001b[17~") },
            "F7" to { sendKey("\u001b[18~") },
            "F8" to { sendKey("\u001b[19~") }
        )

        val pages = listOf(page1, page2, page3, page4)

        // ViewPager2 Adapter
        viewPager.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = pages.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val container = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12, 0, 12, 0)
                }
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(container) {}
            }

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val container = holder.itemView as LinearLayout
                container.removeAllViews()
                
                val keys = pages[position]
                for ((label, action) in keys) {
                    val btn = Button(holder.itemView.context).apply {
                        text = label
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        setTextColor(Color.WHITE)
                        
                        // Premium Visual style: dark cards with rounded corners
                        val isModifier = label == "CTRL" || label == "ALT" || label == "SHIFT"
                        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                            setColor(Color.parseColor(if (isModifier) "#121320" else "#1c1d30"))
                            cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics)
                            setStroke(
                                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics).toInt(),
                                Color.parseColor("#2a2b45")
                            )
                        }
                        background = bgDrawable

                        val params = LinearLayout.LayoutParams(0, TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics).toInt(), 1f
                        ).apply {
                            setMargins(4, 2, 4, 2)
                        }
                        layoutParams = params
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            action()
                        }
                    }

                    if (label == "CTRL") btnCtrl = btn
                    if (label == "ALT") btnAlt = btn
                    if (label == "SHIFT") btnShift = btn
                    if (label == "⌨️") btnToggleKeypad = btn

                    container.addView(btn)
                }
            }
        }

        // Initialize dots indicator
        val dotViews = ArrayList<View>()
        for (i in pages.indices) {
            val dot = View(this).apply {
                val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()
                val params = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(6, 0, 6, 0)
                }
                layoutParams = params
                val drawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#44475a"))
                }
                background = drawable
            }
            dotsLayout.addView(dot)
            dotViews.add(dot)
        }

        // Listen to page changes to update active dot indicators
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                for (i in dotViews.indices) {
                    val active = (i == position)
                    val drawable = dotViews[i].background as android.graphics.drawable.GradientDrawable
                    drawable.setColor(Color.parseColor(if (active) "#00FF41" else "#44475a"))
                }
            }
        })

        rootContainer.addView(viewPager)
        rootContainer.addView(dotsLayout)
        return rootContainer
    }

    private fun buildSpecialKeypadPanel(): LinearLayout {
        specialKeypadPanel = LinearLayout(this).apply {
            val heightPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 200f, resources.displayMetrics).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#08090d"))
            visibility = View.GONE
        }

        // Tab bar container
        val tabScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#06070a"))
            setPadding(4, 4, 4, 4)
        }

        tabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        tabScroll.addView(tabContainer)
        specialKeypadPanel.addView(tabScroll)

        // Scrollable keys container
        val keysScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = true
        }

        keysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        keysScroll.addView(keysContainer)
        specialKeypadPanel.addView(keysScroll)

        buildTabs()
        renderActiveTab()

        return specialKeypadPanel
    }

    private fun buildTabs() {
        tabContainer.removeAllViews()
        tabButtons.clear()

        for (tab in tabsList) {
            val btn = Button(this).apply {
                text = when (tab) {
                    "CONTROL" -> "🎛️ Control"
                    "SYMBOLS" -> "🔣 Symbols"
                    "NAVIGATION" -> "🧭 Navigation"
                    "CTRL COMBOS" -> "⚡ Combos"
                    "F-KEYS" -> "🛠️ F-Keys"
                    else -> tab
                }
                textSize = 11f
                isAllCaps = false
                typeface = Typeface.MONOSPACE
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics).toInt()
                ).apply {
                    setMargins(4, 2, 4, 2)
                }
                layoutParams = params
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    activeKeyboardTab = tab
                    updateTabStyles()
                    renderActiveTab()
                }
            }
            tabContainer.addView(btn)
            tabButtons[tab] = btn
        }
        updateTabStyles()
    }

    private fun updateTabStyles() {
        for ((tab, btn) in tabButtons) {
            val isActive = (tab == activeKeyboardTab)
            val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor(if (isActive) "#151620" else "#08090d"))
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics)
                setStroke(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, resources.displayMetrics).toInt(),
                    Color.parseColor(if (isActive) "#00FF41" else "#2a2b45")
                )
            }
            btn.background = bgDrawable
            btn.setTextColor(if (isActive) Color.parseColor("#00FF41") else Color.WHITE)
        }
    }

    private fun renderActiveTab() {
        keysContainer.removeAllViews()

        val keys = when (activeKeyboardTab) {
            "CONTROL" -> HackerKeyboardRows.row1Control
            "SYMBOLS" -> HackerKeyboardRows.row3Symbols
            "NAVIGATION" -> HackerKeyboardRows.row4Navigation
            "CTRL COMBOS" -> HackerKeyboardRows.row5CtrlCombos
            "F-KEYS" -> HackerKeyboardRows.row6Function
            else -> emptyList()
        }

        val columns = when (activeKeyboardTab) {
            "SYMBOLS", "CTRL COMBOS" -> 5
            else -> 4
        }

        var currentRow: LinearLayout? = null
        val rowHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics).toInt()

        for (i in keys.indices) {
            if (i % columns == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, rowHeight
                    ).apply {
                        setMargins(0, 3, 0, 3)
                    }
                }
                keysContainer.addView(currentRow)
            }

            val key = keys[i]
            val btn = Button(this).apply {
                text = key.label
                textSize = 12f
                isAllCaps = false
                typeface = Typeface.MONOSPACE
                setTextColor(Color.WHITE)
                
                val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#151620"))
                    cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics)
                    setStroke(
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics).toInt(),
                        Color.parseColor("#2a2b45")
                    )
                }
                background = bgDrawable

                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(3, 0, 3, 0)
                }
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    handleHackerKeyPress(key)
                }
            }
            currentRow?.addView(btn)
        }

        // Pad the last row with empty space / invisible views if it's not fully filled
        val remainder = keys.size % columns
        if (remainder != 0 && currentRow != null) {
            val missing = columns - remainder
            for (m in 0 until missing) {
                val spacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        setMargins(4, 0, 4, 0)
                    }
                }
                currentRow.addView(spacer)
            }
        }
    }

    private fun handleHackerKeyPress(key: KeyType) {
        if (key == KeyType.CTRL_C || key == KeyType.CTRL_L || key == KeyType.CTRL_U) {
            resetCurrentCommand()
        }

        val sequence = when (key) {
            // Row 1 - Control
            KeyType.ESC -> "\u001b"
            KeyType.TAB -> "\t"
            KeyType.ENTER -> "\r"
            KeyType.BACK_SPACE -> "\u007f"
            KeyType.INSERT -> "\u001b[2~"
            KeyType.DELETE -> "\u001b[3~"
            KeyType.SHIFT_TAB -> "\u001b[Z"
            KeyType.PASTE -> {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString()
                    if (!text.isNullOrEmpty()) {
                        sendKey(text)
                    }
                }
                return
            }

            // Row 4 - Navigation
            KeyType.PAGE_UP -> "\u001b[5~"
            KeyType.PAGE_DOWN -> "\u001b[6~"
            KeyType.ARROW_LEFT -> "\u001b[D"
            KeyType.ARROW_RIGHT -> "\u001b[C"
            KeyType.ARROW_UP -> "\u001b[A"
            KeyType.ARROW_DOWN -> "\u001b[B"
            KeyType.HOME -> "\u001b[H"
            KeyType.END -> "\u001b[F"

            // Row 5 - Ctrl Combinations
            KeyType.CTRL_UNDERSCORE -> "\u001f"
            KeyType.CTRL_XX -> "\u0018\u0018"
            KeyType.CTRL_Z -> "\u001a"
            KeyType.CTRL_R -> "\u0012"
            KeyType.CTRL_G -> "\u0007"
            KeyType.CTRL_A -> "\u0001"
            KeyType.CTRL_B -> "\u0002"
            KeyType.CTRL_X -> "\u0018"
            KeyType.CTRL_F -> "\u0006"
            KeyType.CTRL_P -> "\u0010"
            KeyType.CTRL_N -> "\u000e"
            KeyType.CTRL_C -> "\u0003"
            KeyType.CTRL_H -> "\u0008"
            KeyType.CTRL_S -> "\u0013"
            KeyType.CTRL_Q -> "\u0011"
            KeyType.CTRL_U -> "\u0015"
            KeyType.CTRL_W -> "\u0017"
            KeyType.CTRL_L -> "\u000c"
            KeyType.CTRL_D -> "\u0004"

            // Row 6 - F-keys
            KeyType.F1 -> "\u001bOP"
            KeyType.F2 -> "\u001bOQ"
            KeyType.F3 -> "\u001bOR"
            KeyType.F4 -> "\u001bOS"
            KeyType.F5 -> "\u001b[15~"
            KeyType.F6 -> "\u001b[17~"
            KeyType.F7 -> "\u001b[18~"
            KeyType.F8 -> "\u001b[19~"
            KeyType.F9 -> "\u001b[20~"
            KeyType.F10 -> "\u001b[21~"
            KeyType.F11 -> "\u001b[23~"
            KeyType.F12 -> "\u001b[24~"
            KeyType.F13 -> "\u001b[25~"
            KeyType.F14 -> "\u001b[26~"
            KeyType.F15 -> "\u001b[28~"
            KeyType.F16 -> "\u001b[29~"
            KeyType.F17 -> "\u001b[31~"
            KeyType.F18 -> "\u001b[32~"
            KeyType.F19 -> "\u001b[33~"
            KeyType.F20 -> "\u001b[34~"

            // Alt, Ctrl and Shift keys (as fallbacks if needed)
            KeyType.ALT -> {
                toggleAltModifier()
                return
            }
            KeyType.CTRL -> {
                toggleCtrlModifier()
                return
            }
            KeyType.SHIFT -> {
                toggleShiftModifier()
                return
            }

            // Row 3 - Symbols
            else -> key.label
        }

        sendKey(sequence)
    }

    private fun sendKey(sequence: String) {
        currentSession?.write(sequence)
        terminalView.requestFocus()
    }

    private fun toggleCtrlModifier() {
        customCtrlActive = !customCtrlActive
        if (customCtrlActive) {
            btnCtrl.setBackgroundColor(Color.parseColor("#ff0033")) // Kali Red
            btnCtrl.setTextColor(Color.WHITE)
        } else {
            btnCtrl.setBackgroundColor(Color.parseColor("#181926"))
            btnCtrl.setTextColor(Color.WHITE)
        }
        terminalView.requestFocus()
    }

    private fun toggleAltModifier() {
        customAltActive = !customAltActive
        if (customAltActive) {
            btnAlt.setBackgroundColor(Color.parseColor("#ff0033")) // Kali Red
            btnAlt.setTextColor(Color.WHITE)
        } else {
            btnAlt.setBackgroundColor(Color.parseColor("#181926"))
            btnAlt.setTextColor(Color.WHITE)
        }
        terminalView.requestFocus()
    }

    private fun toggleShiftModifier() {
        customShiftActive = !customShiftActive
        if (customShiftActive) {
            btnShift.setBackgroundColor(Color.parseColor("#ff0033")) // Kali Red
            btnShift.setTextColor(Color.WHITE)
        } else {
            btnShift.setBackgroundColor(Color.parseColor("#181926"))
            btnShift.setTextColor(Color.WHITE)
        }
        terminalView.requestFocus()
    }

    fun toggleSpecialKeypad(show: Boolean) {
        if (show) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
            
            specialKeypadPanel.visibility = View.VISIBLE
            btnToggleKeypad.setBackgroundColor(Color.parseColor("#ff0033"))
        } else {
            specialKeypadPanel.visibility = View.GONE
            btnToggleKeypad.setBackgroundColor(Color.parseColor("#181926"))
            
            showSoftKeyboard()
        }
        terminalView.requestFocus()
    }

    fun resetModifiers() {
        customCtrlActive = false
        customAltActive = false
        customShiftActive = false
        runOnUiThread {
            btnCtrl.setBackgroundColor(Color.parseColor("#181926"))
            btnCtrl.setTextColor(Color.WHITE)
            btnAlt.setBackgroundColor(Color.parseColor("#181926"))
            btnAlt.setTextColor(Color.WHITE)
            btnShift.setBackgroundColor(Color.parseColor("#181926"))
            btnShift.setTextColor(Color.WHITE)
        }
    }

    private fun buildErrorOverlay(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            setPadding(48, 64, 48, 64)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        errorText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        layout.addView(errorText)
        return layout
    }

    private fun setupAndStartSession() {
        Log.i(TAG, "setupAndStartSession")
        val activeSessions = TerminalService.sessions
        if (activeSessions.isNotEmpty()) {
            Log.i(TAG, "Attaching to existing active session")
            val lastSession = activeSessions.last()
            val sessionId = TerminalService.getSessionId(lastSession)
            val distroName = if (sessionId != null) TerminalService.sessionDistros[sessionId] ?: "kali-arm64" else "kali-arm64"
            val mountStorageSaved = getSharedPreferences("vpn_settings", MODE_PRIVATE).getBoolean("mount_storage", false)
            lifecycleScope.launch(Dispatchers.IO) {
                val cfg = try {
                    val isDocker = intent.getBooleanExtra("isDockerImage", false) ||
                                   distroName.startsWith("docker-") ||
                                   distroName.startsWith("oci-")
                    ProotManager.setupProotEnvironment(this@TerminalActivity, distroName, mountStorageSaved, null, false, isDocker)
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
        val rootfsDirName = intent.getStringExtra("rootfsDirName") ?: "kali-arm64"
        val mountStorage = intent.getBooleanExtra("mountStorage", false)
        val customCommand = intent.getStringExtra("customCommand")
        val ashellMode = intent.getBooleanExtra("ashellMode", false)
        // Docker image: rozpozná se podle extra, prefixu adresáře nebo fallback na .docker_image soubor
        val isDockerImage = intent.getBooleanExtra("isDockerImage", false) ||
                            rootfsDirName.startsWith("docker-") ||
                            rootfsDirName.startsWith("oci-") ||
                            File(filesDir, "$rootfsDirName/.docker_image").exists()

        // ashell: escape z prootu do host app shellu (/system/bin/sh, bez PRoot)
        if (ashellMode || rootfsDirName == "ashell-host") {
            startAshellSession()
            return
        }

        val rootfsDir = File(filesDir, rootfsDirName)
        val setupDoneFile = File(rootfsDir, "root/.setup_done")

        if (!setupDoneFile.exists()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Detekce Rootu")
                .setMessage("Má Vaše zařízení ROOT oprávnění (Magisk / KernelSU)?\n\nPokud zvolíte 'Ano', nebudou se vytvářet falešné mock soubory pro systémové příkazy (jako systemctl, sysctl, atd.), protože je nebudete potřebovat.")
                .setPositiveButton("Ano") { _, _ ->
                    startSetup(rootfsDirName, mountStorage, customCommand, true, isDockerImage)
                }
                .setNegativeButton("Ne") { _, _ ->
                    startSetup(rootfsDirName, mountStorage, customCommand, false, isDockerImage)
                }
                .setCancelable(false)
                .show()
        } else {
            startSetup(rootfsDirName, mountStorage, customCommand, false, isDockerImage)
        }
    }

    /**
     * ashell — spustí interaktivní host shell (bez PRoot, mimo guest).
     * Používá /system/bin/sh s HOME = filesDir. Slouží jako escape z prootu.
     */
    private fun startAshellSession() {
        Log.i(TAG, "startAshellSession: escape proot → host app shell")
        val cwd = filesDir
        // TerminalSession spouští config.command[0] s config.command jako argv.
        // Chceme prostě /system/bin/sh -i (interaktivní), cwd = filesDir.
        val cmd = arrayOf("/system/bin/sh", "-i")
        val env = arrayOf(
            "HOME=${filesDir.absolutePath}",
            "USER=app",
            "PATH=/system/bin:/system/xbin:/vendor/bin",
            "TERM=xterm-256color",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system"
        )
        val cfg = com.linux_core.core.ProotConfig(
            command = cmd,
            cwd = cwd.absolutePath,
            env = env,
            prootPath = "",
            rootfsDir = "(host)"   // sentinel: Není rootfs, jsme v hostu
        )
        config = cfg
        startTerminalSession(cfg)
    }

    private fun startSetup(rootfsDirName: String, mountStorage: Boolean, customCommand: String?, hasRoot: Boolean, isDockerImage: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                ProotManager.setupProotEnvironment(this@TerminalActivity, rootfsDirName, mountStorage, customCommand, hasRoot, isDockerImage)
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
        val session = try {
            TerminalService.createSession(this, config, terminalView) { showError(it) }
        } catch (e: Exception) { showError("Session error: ${e.message}"); return }

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
            val success = imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            Log.d(TAG, "showSoftInput request sent, success=$success")
        }, 300)
    }

    private fun showError(message: String) {
        Log.e(TAG, "Error: $message")
        errorText.text = message
        errorLayout.visibility = View.VISIBLE
        terminalView.visibility = View.GONE
    }

    // ═══════════════════════════════════════════════════════════════
    //  SERVICES PANEL
    // ═══════════════════════════════════════════════════════════════

    private fun buildServicesPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            val h = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 34f, resources.displayMetrics).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h
            ).apply { setMargins(8, 2, 8, 2) }

            btnShizuku = Button(this@TerminalActivity).apply {
                text = "\u26A1 SHIZU \u25CB"
                textSize = 9f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.Gray)
                background = createRoundedDrawable(Color.parseColor("#0c0d12"), 6f, Color.parseColor("#1e2026"), 1f)
                setPadding(10, 4, 10, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                )
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    toggleServiceDetail("shizuku")
                }
            }
            addView(btnShizuku)

            View(this@TerminalActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt(), 1
                )
            }.also { addView(it) }

            btnCode = Button(this@TerminalActivity).apply {
                text = "[code] CODE \u25CB"
                textSize = 9f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.Gray)
                background = createRoundedDrawable(Color.parseColor("#0c0d12"), 6f, Color.parseColor("#1e2026"), 1f)
                setPadding(10, 4, 10, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                )
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    toggleServiceDetail("code")
                }
            }
            addView(btnCode)

            View(this@TerminalActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt(), 1
                )
            }.also { addView(it) }

            btnPhoenix = Button(this@TerminalActivity).apply {
                text = "\uD83D\uDD25 PHOENIX \u25CB"
                textSize = 9f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.Gray)
                background = createRoundedDrawable(Color.parseColor("#0c0d12"), 6f, Color.parseColor("#1e2026"), 1f)
                setPadding(10, 4, 10, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                )
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    toggleServiceDetail("phoenix")
                }
            }
            addView(btnPhoenix)

            View(this@TerminalActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }.also { addView(it) }

            // START ALL button
            Button(this@TerminalActivity).apply {
                text = "\u25B6 ALL"
                textSize = 9f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.parseColor("#00FF41"))
                background = createRoundedDrawable(Color.parseColor("#0f1017"), 6f, Color.parseColor("#00FF41"), 1f)
                setPadding(10, 4, 10, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                )
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    startAllServices()
                }
            }.also { addView(it) }

            View(this@TerminalActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt(), 1
                )
            }.also { addView(it) }

            // Refresh button
            Button(this@TerminalActivity).apply {
                text = "\u21BB"
                textSize = 12f
                setTextColor(Color.Gray)
                background = null
                setPadding(6, 0, 6, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                )
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    updateAllServiceIndicators()
                }
            }.also { addView(it) }
        }
    }

    private fun buildServicesDetailPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            val p = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
            setPadding(p, 4, p, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#0c0d12"))
        }
    }

    private fun toggleServicesPanel() {
        isServicesExpanded = !isServicesExpanded
        servicesPanel.visibility = if (isServicesExpanded) View.VISIBLE else View.GONE
        btnServicesToggle.text = if (isServicesExpanded) "\u25B2" else "\u25BC"

        if (isServicesExpanded) {
            updateAllServiceIndicators()
            servicesUpdateHandler.post(servicesPoller)
        } else {
            servicesDetailPanel.visibility = View.GONE
            expandedService = null
            servicesUpdateHandler.removeCallbacks(servicesPoller)
        }
    }

    private fun toggleServiceDetail(service: String) {
        if (expandedService == service) {
            servicesDetailPanel.visibility = View.GONE
            expandedService = null
        } else {
            expandedService = service
            updateServiceDetail(service)
            servicesDetailPanel.visibility = View.VISIBLE
        }
    }

    private fun updateAllServiceIndicators() {
        updateServiceIndicator("shizuku", btnShizuku)
        updateServiceIndicator("code", btnCode)
        updateServiceIndicator("phoenix", btnPhoenix)

        if (expandedService != null) {
            updateServiceDetail(expandedService)
        }
    }

    private fun updateServiceIndicator(service: String, button: Button) {
        val running = when (service) {
            "shizuku" -> com.linux_core.core.ShizukuManager.status(applicationContext).running
            "code" -> {
                val raw = runCodeServerCtl("status")
                raw.contains("running", ignoreCase = true) || raw.contains("pid", ignoreCase = true)
            }
            "phoenix" -> false
            else -> false
        }

        val icon = if (running) "\u25CF" else "\u25CB"
        val color = if (running) Color.parseColor("#00FF41") else Color.Gray
        button.text = when (service) {
            "shizuku" -> "\u26A1 SHIZU $icon"
            "code" -> "[code] CODE $icon"
            "phoenix" -> "\uD83D\uDD25 PHOENIX $icon"
            else -> button.text
        }
        button.setTextColor(color)
    }

    private fun updateServiceDetail(service: String) {
        servicesDetailPanel.removeAllViews()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        when (service) {
            "shizuku" -> {
                val st = com.linux_core.core.ShizukuManager.status(applicationContext)
                val icon = if (st.running) "\u25CF" else "\u25CB"
                val color = if (st.running) Color.parseColor("#00FF41") else Color.Gray

                row.addView(TextView(this).apply {
                    text = "\u26A1 SHIZUKU SERVER  $icon"
                    setTextColor(color)
                    textSize = 11f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                })

                row.addView(TextView(this).apply {
                    val info = when {
                        st.running -> "  pid:${st.pid ?: "?"}  ${st.mode}"
                        st.suAvailable -> "  su available"
                        st.shizukuApkPath != null -> "  Shizuku APK ready"
                        st.adbAvailable -> "  ADB enabled"
                        else -> ""
                    }
                    text = info
                    setTextColor(Color.LightGray)
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                })

                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })

                // START/STOP button
                if (st.running) {
                    row.addView(Button(this).apply {
                        text = "\u23F9 STOP"
                        textSize = 9f
                        setTextColor(Color.parseColor("#FF5555"))
                        background = createRoundedDrawable(Color.parseColor("#1a1a2e"), 6f, Color.parseColor("#FF5555"), 1f)
                        setPadding(10, 4, 10, 4)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                        )
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            com.linux_core.core.ShizukuManager.stopServer(applicationContext)
                            updateAllServiceIndicators()
                        }
                    })
                } else if (st.suAvailable || st.shizukuApkPath != null) {
                    row.addView(Button(this).apply {
                        text = "\u25B6 START"
                        textSize = 9f
                        setTextColor(Color.parseColor("#00FF41"))
                        background = createRoundedDrawable(Color.parseColor("#0a1a0a"), 6f, Color.parseColor("#00FF41"), 1f)
                        setPadding(10, 4, 10, 4)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                        )
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            com.linux_core.core.ShizukuManager.startServer(applicationContext)
                            updateAllServiceIndicators()
                        }
                    })
                } else {
                    // No startup method available — show setup options
                    row.addView(Button(this).apply {
                        text = "\u2699 SETUP"
                        textSize = 9f
                        setTextColor(Color.parseColor("#FF6B35"))
                        background = createRoundedDrawable(Color.parseColor("#1a1a0a"), 6f, Color.parseColor("#FF6B35"), 1f)
                        setPadding(10, 4, 10, 4)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                        )
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            showShizukuSetupDialog()
                        }
                    })
                }
            }
            "code" -> {
                val raw = runCodeServerCtl("status")
                val running = raw.contains("running", ignoreCase = true) || raw.contains("pid", ignoreCase = true)
                val icon = if (running) "\u25CF" else "\u25CB"
                val color = if (running) Color.parseColor("#00FF41") else Color.Gray

                row.addView(TextView(this).apply {
                    text = "[code] CODE-SERVER  $icon"
                    setTextColor(color)
                    textSize = 11f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                })

                if (running) {
                    row.addView(TextView(this).apply {
                        text = "  :8443"
                        setTextColor(Color.LightGray)
                        textSize = 10f
                        typeface = Typeface.MONOSPACE
                    })
                }

                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })

                if (running) {
                    row.addView(Button(this).apply {
                        text = "\u23F9 STOP"
                        textSize = 9f
                        setTextColor(Color.parseColor("#FF5555"))
                        background = createRoundedDrawable(Color.parseColor("#1a1a2e"), 6f, Color.parseColor("#FF5555"), 1f)
                        setPadding(10, 4, 10, 4)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                        )
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            runCodeServerCtl("stop")
                            updateAllServiceIndicators()
                        }
                    })
                    row.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt(), 1
                        )
                    })
                    row.addView(Button(this).apply {
                        text = "\uD83C\uDF10 OPEN"
                        textSize = 9f
                        setTextColor(Color.parseColor("#00BFFF"))
                        background = createRoundedDrawable(Color.parseColor("#0a1a2e"), 6f, Color.parseColor("#00BFFF"), 1f)
                        setPadding(10, 4, 10, 4)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                        )
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("http://127.0.0.1:8443"))
                            startActivity(intent)
                        }
                    })
                } else {
                    row.addView(Button(this).apply {
                        text = "\u25B6 START"
                        textSize = 9f
                        setTextColor(Color.parseColor("#00FF41"))
                        background = createRoundedDrawable(Color.parseColor("#0a1a0a"), 6f, Color.parseColor("#00FF41"), 1f)
                        setPadding(10, 4, 10, 4)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                        )
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            runCodeServerCtl("start")
                            updateAllServiceIndicators()
                        }
                    })
                }
            }
            "phoenix" -> {
                row.addView(TextView(this).apply {
                    text = "\uD83D\uDD25 PHOENIX OTLP  \u25CB"
                    setTextColor(Color.Gray)
                    textSize = 11f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                })

                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })

                row.addView(Button(this).apply {
                    text = "\u2699 CONFIGURE"
                    textSize = 9f
                    setTextColor(Color.parseColor("#FF6B35"))
                    background = createRoundedDrawable(Color.parseColor("#1a1a0a"), 6f, Color.parseColor("#FF6B35"), 1f)
                    setPadding(10, 4, 10, 4)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt()
                    )
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        showPhoenixConfigDialog()
                    }
                })
            }
        }

        servicesDetailPanel.addView(row)
    }

    private fun runCodeServerCtl(vararg args: String): String {
        val launcherFile = java.io.File(applicationContext.filesDir, "launcher.sh")
        if (!launcherFile.exists() || !launcherFile.canExecute()) {
            return "{\"error\":\"launcher.sh not found\"}"
        }
        return try {
            val pb = ProcessBuilder("sh", launcherFile.absolutePath, "code-server-ctl", *args)
            pb.directory(applicationContext.filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                "{\"error\":\"timed out\"}"
            } else {
                output
            }
        } catch (e: Exception) {
            "{\"error\":\"${e.message}\"}"
        }
    }

    private fun showPhoenixConfigDialog() {
        val prefs = applicationContext.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        val currentEndpoint = prefs.getString("phoenix_endpoint",
            "http://localhost:6006/v1/traces") ?: "http://localhost:6006/v1/traces"

        val input = android.widget.EditText(this).apply {
            setText(currentEndpoint)
            setHint("http://localhost:6006/v1/traces")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.Gray)
            textSize = 12f
            setPadding(24, 16, 24, 16)
        }

        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("Phoenix OTLP Endpoint")
            .setMessage("Configure OpenTelemetry endpoint for Phoenix telemetry export.")
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val newEndpoint = input.text.toString().trim()
                prefs.edit().putString("phoenix_endpoint", newEndpoint).apply()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showShizukuSetupDialog() {
        val sb = StringBuilder()
        sb.appendLine("Shizuku server potřebuje root nebo ADB.")
        sb.appendLine()
        sb.appendLine("\u2022 Instaluj Shizuku z Play Store / F-Droid")
        sb.appendLine("\u2022 Nebo povol Wireless debugging v Developer options")
        sb.appendLine()
        sb.appendLine("Po instalaci restartuj Shizuku v tomto panelu.")

        val adbCmd = com.linux_core.core.ShizukuManager.getShizukuApkPath(applicationContext)
        if (adbCmd != null) {
            sb.appendLine()
            sb.appendLine("ADB příkaz (spustit z počítače):")
            sb.appendLine("adb shell /data/data/${applicationContext.packageName}/files/shizuku-server --apk=$adbCmd")
        }

        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("\u26A1 Shizuku Setup")
            .setMessage(sb.toString())
            .setPositiveButton("OPEN PLAY STORE") { _, _ ->
                try {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=moe.shizuku.privileged.api")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
                    )
                    startActivity(intent)
                }
            }
            .setNeutralButton("OPEN ADB SETTINGS") { _, _ ->
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) { /* ignore */ }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun startAllServices() {
        com.linux_core.core.ShizukuManager.startServer(applicationContext)
        runCodeServerCtl("start")
        updateAllServiceIndicators()
    }
}

class TerminalViewClientImpl : TerminalViewClient {
    private var activity: TerminalActivity? = null

    fun setActivity(activity: TerminalActivity) { this.activity = activity }

    override fun onScale(scale: Float): Float {
        activity?.changeTerminalFontSize(scale)
        return 1.0f
    }
    override fun onSingleTapUp(e: MotionEvent) {
        Log.d("TerminalView", "onSingleTapUp")
        if (activity?.toggleSpecialKeypad(false) == null) {
            activity?.showSoftKeyboard()
        }
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        Log.d("TerminalView", "onKeyDown: keyCode=$keyCode")
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            activity?.onTerminalEnter()
            session.write("\r")
            return true
        }
        val arrowSequence = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
            else -> null
        }
        if (arrowSequence != null) {
            session.write(arrowSequence)
            return true
        }
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun readShiftKey() = activity?.customShiftActive == true
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        val act = activity
        val finalCtrl = ctrlDown || (act?.customCtrlActive == true)
        val finalAlt = act?.customAltActive == true
        val finalShift = act?.customShiftActive == true

        if (finalCtrl) {
            act?.resetModifiers()
            val upperCode = codePoint.toChar().uppercaseChar().code
            if (upperCode in 64..95) {
                session.write(Character.toString((upperCode - 64).toChar()))
                // Ctrl+C (3), Ctrl+L (12), Ctrl+U (21) should reset currentCommand
                if (upperCode == 'C'.code || upperCode == 'L'.code || upperCode == 'U'.code) {
                    act?.resetCurrentCommand()
                }
                return true
            }
        }
        if (finalAlt) {
            act?.resetModifiers()
            session.write("\u001b" + Character.toString(codePoint.toChar()))
            return true
        }

        var processedCodePoint = codePoint
        if (finalShift) {
            act?.resetModifiers()
            val ch = codePoint.toChar()
            if (ch.isLowerCase()) {
                processedCodePoint = ch.uppercaseChar().code
            }
        } else {
            act?.onTerminalInput(codePoint)
        }

        val input = StringBuilder().appendCodePoint(processedCodePoint).toString()
        Log.d("TerminalView", "onCodePoint: $input ($processedCodePoint)")
        session.write(input)
        return true
    }
    override fun onEmulatorSet() {
        Log.d("TerminalView", "onEmulatorSet")
    }
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Stack trace", e) }
}
