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
    private lateinit var btnFloatingMenu: TextView
    private lateinit var topBar: LinearLayout
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
    private lateinit var btnCtrl: Button
    private lateinit var btnAlt: Button
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
    private lateinit var toolbarScroll: HorizontalScrollView
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
            setBackgroundColor(Color.parseColor("#08090d"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE // Hidden by default in CLI mode
        }

        // Hamburger Menu button on the left of topBar to slide drawer open
        val btnMenu = Button(this).apply {
            text = "☰"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#151620"))
            setPadding(16, 4, 16, 4)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics).toInt()
            ).apply {
                setMargins(12, 4, 12, 4)
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
            setBackgroundColor(Color.parseColor("#151620"))
            setPadding(16, 4, 16, 4)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics).toInt()
            ).apply {
                setMargins(0, 4, 12, 4)
            }
            layoutParams = params
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                finish()
            }
        }
        topBar.addView(btnGoToMenu)

        // Symmetrical spacer to push GUI switch to the right
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        topBar.addView(spacer)

        // CLI/GUI Switch on the right side of topBar
        val guiToggleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(8, 0, 16, 0)
            }
            gravity = Gravity.CENTER
        }

        btnCli = Button(this).apply {
            text = "🐚 CLI"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00FF41")) // Deep Matrix Green initially highlighted
            setBackgroundColor(Color.parseColor("#151620"))
            setPadding(12, 4, 12, 4)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt()
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
            setBackgroundColor(Color.parseColor("#08090d"))
            setPadding(12, 4, 12, 4)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt()
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
            text = "🖱️ Mouse"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#151620"))
            setPadding(12, 4, 12, 4)
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

        btnFloatingMenu = TextView(this).apply {
            text = "☰"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8000FF41")) // semi-transparent neon green
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(12, 12, 12, 12)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(4, 4, 4, 4)
            }
            layoutParams = params
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                isDrawerExpanded = false
                val minWidthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 70f, resources.displayMetrics).toInt()
                val dParams = drawerView.layoutParams as DrawerLayout.LayoutParams
                dParams.width = minWidthPx
                drawerView.layoutParams = dParams
                drawerView.requestLayout()
                updateSessionDrawer()
                drawerLayout.openDrawer(Gravity.START)
            }
        }
        terminalContainer.addView(btnFloatingMenu)

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
            btnCli.setTextColor(Color.parseColor("#00FF41"))
            btnCli.setBackgroundColor(Color.parseColor("#151620"))
            btnGui.setTextColor(Color.WHITE)
            btnGui.setBackgroundColor(Color.parseColor("#08090d"))

            terminalView.visibility = View.VISIBLE
            suggestionBar.visibility = if (historyManager.getSuggestions(currentCommand.toString()).isNotEmpty()) View.VISIBLE else View.GONE
            toolbarScroll.visibility = View.VISIBLE
            guiContainer.visibility = View.GONE
            btnTouchToggle.visibility = View.GONE
            
            topBar.visibility = View.GONE
            btnFloatingMenu.visibility = View.VISIBLE
            
            showSoftKeyboard()
        } else {
            btnGui.setTextColor(Color.parseColor("#00FF41"))
            btnGui.setBackgroundColor(Color.parseColor("#151620"))
            btnCli.setTextColor(Color.WHITE)
            btnCli.setBackgroundColor(Color.parseColor("#08090d"))

            terminalView.visibility = View.GONE
            suggestionBar.visibility = View.GONE
            toolbarScroll.visibility = View.GONE
            specialKeypadPanel.visibility = View.GONE
            guiContainer.visibility = View.VISIBLE
            btnTouchToggle.visibility = View.VISIBLE

            topBar.visibility = View.VISIBLE
            btnFloatingMenu.visibility = View.GONE

            // Hide soft keyboard when switching to GUI
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)

            checkAndLoadGui()
        }
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
        stopDrawerRamUpdateLoop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt(),
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt(),
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt(),
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt()
                )
                drawerHeader.visibility = View.VISIBLE
                drawerHeader.text = "🐚 NETHUNTER SESSIONS\n[RAM: ${getTotalRamUsage()}]"
                tabLayout.visibility = View.VISIBLE
                btnAddSession.visibility = View.VISIBLE
                
                // Update drawer tab button styling
                drawerTabButtons.forEach { (tabCode, btn) ->
                    val isSel = (tabCode == activeDrawerTab)
                    btn.setTextColor(if (isSel) Color.parseColor("#00FF41") else Color.WHITE)
                    btn.setBackgroundColor(if (isSel) Color.parseColor("#151620") else Color.parseColor("#08090d"))
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
                    setBackgroundColor(Color.parseColor("#151620"))
                    setTextColor(Color.WHITE)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics).toInt()
                    ).apply {
                        setMargins(0, 4, 0, 16)
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
                
                // Vertical container row for the session card
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(if (isActive) Color.parseColor("#151620") else Color.parseColor("#0c0d12"))
                    
                    val pxPaddingHoriz = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, if (isDrawerExpanded) 16f else 8f, resources.displayMetrics).toInt()
                    val pxPaddingVert = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, if (isDrawerExpanded) 18f else 12f, resources.displayMetrics).toInt()
                    setPadding(pxPaddingHoriz, pxPaddingVert, pxPaddingHoriz, pxPaddingVert)
                    
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 4, 0, 4)
                    }
                    layoutParams = params
                }

                // Glow/Indicator vertical line on the left side of the row
                val indicator = View(this).apply {
                    setBackgroundColor(if (isActive) Color.parseColor("#00FF41") else Color.TRANSPARENT)
                    val params = LinearLayout.LayoutParams(
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt(),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    layoutParams = params
                }
                row.addView(indicator)

                // Session Text label
                val isIgnored = TerminalService.isSessionVpnIgnored(session)
                val isParrot = distro.contains("parrot")
                val distroBadge = if (isParrot) "🦜" else "🐉"
                
                val memBytes = com.linux_core.core.ProcessResolver.getSessionMemoryUsage(session)
                val memStr = formatBytes(memBytes)

                if (isDrawerExpanded) {
                    val label = TextView(this).apply {
                        val customName = TerminalService.getSessionName(session)
                        val baseText = if (!customName.isNullOrEmpty()) customName else "Session ${i + 1}"
                        text = "${distroBadge} ${baseText} ($memStr)" + (if (isIgnored) " [VPN IGNORED]" else "")
                        textSize = 13f
                        typeface = Typeface.MONOSPACE
                        if (isActive) {
                            setTextColor(Color.parseColor("#00FF41"))
                        } else if (isIgnored) {
                            setTextColor(Color.parseColor("#FF9900")) // Gold/Orange for bypassed session
                        } else {
                            setTextColor(Color.WHITE)
                        }
                        val params = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        ).apply {
                            setMargins(16, 0, 16, 0)
                        }
                        layoutParams = params
                    }
                    row.addView(label)

                    // Quick Close Button on the right
                    val btnClose = TextView(this).apply {
                        text = "✕"
                        textSize = 14f
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setTextColor(Color.parseColor("#A9B1D6"))
                        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
                        setPadding(pad, pad, pad, pad)
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        layoutParams = params
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            closeSession(session)
                        }
                    }
                    row.addView(btnClose)
                } else {
                    // Minimized Mode: Show ONLY the distro badge emoji
                    val emojiLabel = TextView(this).apply {
                        text = distroBadge
                        textSize = 20f
                        gravity = Gravity.CENTER
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 0, 0)
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

    private fun buildExtraKeysToolbar(): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor("#0d0e15"))
            setPadding(4, 4, 4, 4)
            isHorizontalScrollBarEnabled = false
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        val toolbarKeys = listOf(
            "ESC" to { sendKey("\u001b") },
            "TAB" to { sendKey("\t") },
            "CTRL" to { toggleCtrlModifier() },
            "ALT" to { toggleAltModifier() },
            "|" to { sendKey("|") },
            "-" to { sendKey("-") },
            "_" to { sendKey("_") },
            "/" to { sendKey("/") },
            "\\" to { sendKey("\\") },
            ":" to { sendKey(":") },
            "⌨️ Keypad" to { toggleSpecialKeypad(specialKeypadPanel.visibility == View.GONE) }
        )

        for ((label, action) in toolbarKeys) {
            val btn = Button(this).apply {
                text = label
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#181926"))
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics).toInt()
                ).apply {
                    setMargins(6, 4, 6, 4)
                }
                layoutParams = params
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    action()
                }
            }
            if (label == "CTRL") btnCtrl = btn
            if (label == "ALT") btnAlt = btn
            if (label == "⌨️ Keypad") btnToggleKeypad = btn

            layout.addView(btn)
        }

        scroll.addView(layout)
        return scroll
    }

    private fun buildSpecialKeypadPanel(): LinearLayout {
        specialKeypadPanel = LinearLayout(this).apply {
            val heightPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 260f, resources.displayMetrics).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0c0d12"))
            visibility = View.GONE
        }

        // Tab bar container
        val tabScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#08090d"))
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
            if (isActive) {
                btn.setTextColor(Color.parseColor("#00FF41")) // Deep matrix green
                btn.setBackgroundColor(Color.parseColor("#151620")) // Active background
            } else {
                btn.setTextColor(Color.WHITE)
                btn.setBackgroundColor(Color.parseColor("#08090d")) // Inactive background
            }
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
            TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics).toInt()

        for (i in keys.indices) {
            if (i % columns == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, rowHeight
                    ).apply {
                        setMargins(0, 4, 0, 4)
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
                setBackgroundColor(Color.parseColor("#151620")) // Deep cyber black background
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(4, 0, 4, 0)
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

            // Alt and Ctrl keys (as fallbacks if needed)
            KeyType.ALT -> {
                toggleAltModifier()
                return
            }
            KeyType.CTRL -> {
                toggleCtrlModifier()
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
        runOnUiThread {
            btnCtrl.setBackgroundColor(Color.parseColor("#181926"))
            btnCtrl.setTextColor(Color.WHITE)
            btnAlt.setBackgroundColor(Color.parseColor("#181926"))
            btnAlt.setTextColor(Color.WHITE)
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
                    ProotManager.setupProotEnvironment(this@TerminalActivity, distroName, mountStorageSaved, null, false)
                } catch (e: Exception) {
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

        val rootfsDir = File(filesDir, rootfsDirName)
        val setupDoneFile = File(rootfsDir, "root/.setup_done")

        if (!setupDoneFile.exists()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Detekce Rootu")
                .setMessage("Má Vaše zařízení ROOT oprávnění (Magisk / KernelSU)?\n\nPokud zvolíte 'Ano', nebudou se vytvářet falešné mock soubory pro systémové příkazy (jako systemctl, sysctl, atd.), protože je nebudete potřebovat.")
                .setPositiveButton("Ano") { _, _ ->
                    startSetup(rootfsDirName, mountStorage, customCommand, true)
                }
                .setNegativeButton("Ne") { _, _ ->
                    startSetup(rootfsDirName, mountStorage, customCommand, false)
                }
                .setCancelable(false)
                .show()
        } else {
            startSetup(rootfsDirName, mountStorage, customCommand, false)
        }
    }

    private fun startSetup(rootfsDirName: String, mountStorage: Boolean, customCommand: String?, hasRoot: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                ProotManager.setupProotEnvironment(this@TerminalActivity, rootfsDirName, mountStorage, customCommand, hasRoot)
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (result == null) {
                    showError("Setup failed")
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
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        val act = activity
        val finalCtrl = ctrlDown || (act?.customCtrlActive == true)
        val finalAlt = act?.customAltActive == true

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

        act?.onTerminalInput(codePoint)
        val input = StringBuilder().appendCodePoint(codePoint).toString()
        Log.d("TerminalView", "onCodePoint: $input ($codePoint)")
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
