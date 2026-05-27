package com.linux_core.ui.terminal

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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
import androidx.activity.ComponentActivity
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.linux_core.core.ProotConfig
import com.linux_core.core.ProotManager
import com.linux_core.core.TerminalService
import com.linux_core.core.KeyType
import com.linux_core.core.HackerKeyboardRows
import java.io.File

class TerminalActivity : ComponentActivity() {
    companion object {
        private const val TAG = "TerminalActivity"
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

    // Multi-Session management via background service
    private lateinit var sessionTabBar: HorizontalScrollView
    private lateinit var sessionTabContainer: LinearLayout

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

    var terminalFontSizeFloat = 32f

    fun changeTerminalFontSize(scale: Float) {
        terminalFontSizeFloat *= scale
        terminalFontSizeFloat = terminalFontSizeFloat.coerceIn(8f, 72f)
        terminalView.setTextSize(terminalFontSizeFloat.toInt())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewClient.setActivity(this)
        historyManager = com.linux_core.core.HistoryManager(this)

        // Root container: vertical LinearLayout for screen layout
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Active sessions tab bar docked at the very top
        val sessionBar = buildSessionTabBar()
        mainLayout.addView(sessionBar)

        // Terminal view container (takes weight = 1f to fill remaining screen space)
        val terminalContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        terminalView = TerminalView(this, null)
        terminalView.setBackgroundColor(Color.BLACK)
        terminalView.setTextSize(32)
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

        mainLayout.addView(terminalContainer)

        // Suggestions Bar
        val suggBar = buildSuggestionBar()
        mainLayout.addView(suggBar)

        // Horizontal scrollable Extra Keys Toolbar
        val toolbarScroll = buildExtraKeysToolbar()
        mainLayout.addView(toolbarScroll)

        // Custom Special Keypad Panel (grid overlays Android keyboard space)
        val keypadPanel = buildSpecialKeypadPanel()
        mainLayout.addView(keypadPanel)

        setContentView(mainLayout)
        setupAndStartSession()
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
            updateSessionTabBar()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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

    private fun buildSessionTabBar(): HorizontalScrollView {
        sessionTabBar = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor("#08090d"))
            isHorizontalScrollBarEnabled = false
        }

        sessionTabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(4, 4, 4, 4)
        }

        sessionTabBar.addView(sessionTabContainer)
        return sessionTabBar
    }

    fun updateSessionTabBar() {
        runOnUiThread {
            val serviceSessions = TerminalService.sessions
            sessionTabContainer.removeAllViews()
            for (i in 0 until serviceSessions.size) {
                val session = serviceSessions[i]
                val isActive = (session == currentSession)
                val btn = Button(this).apply {
                    text = "Session ${i + 1}"
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.WHITE)
                    setBackgroundColor(if (isActive) Color.parseColor("#ff0033") else Color.parseColor("#181926"))
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics).toInt()
                    ).apply {
                        setMargins(6, 2, 6, 2)
                    }
                    layoutParams = params
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        switchToSession(session)
                    }
                    setOnLongClickListener {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        closeSession(session)
                        true
                    }
                }
                sessionTabContainer.addView(btn)
            }

            // Plus Button to add session
            val plusBtn = Button(this).apply {
                text = " + "
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#151620"))
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics).toInt()
                ).apply {
                    setMargins(12, 2, 4, 2)
                }
                layoutParams = params
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    addNewSession()
                }
            }
            sessionTabContainer.addView(plusBtn)
        }
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
        updateSessionTabBar()
    }

    private fun switchToSession(session: TerminalSession) {
        currentSession?.let { TerminalService.detachView(it) }
        currentSession = session
        TerminalService.attachView(session, terminalView)
        terminalView.post {
            terminalView.requestFocus()
            terminalView.onScreenUpdated()
        }
        updateSessionTabBar()
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
                updateSessionTabBar()
            }
        }
    }

    fun onSessionEnded(session: TerminalSession) {
        val remaining = TerminalService.sessions
        if (!remaining.contains(session)) {
            if (remaining.isEmpty()) finish()
            else updateSessionTabBar()
            return
        }
        if (remaining.isEmpty()) {
            finish()
        } else {
            if (currentSession == session) {
                currentSession = null
                switchToSession(remaining[0])
            } else {
                updateSessionTabBar()
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
        config = try {
            ProotManager.setupProotEnvironment(this, rootfsDirName, mountStorage, customCommand, hasRoot)
        } catch (e: Exception) {
            showError("Setup failed: ${e.message}"); return
        }
        startTerminalSession(config!!)
    }

    private fun startTerminalSession(config: ProotConfig) {
        Log.i(TAG, "startTerminalSession")
        val session = try {
            TerminalService.createSession(this, config, terminalView) { showError(it) }
        } catch (e: Exception) { showError("Session error: ${e.message}"); return }

        switchToSession(session)
        updateSessionTabBar()
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
        return scale
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
