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
    private lateinit var specialKeypadPanel: ScrollView

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

    private fun buildSpecialKeypadPanel(): ScrollView {
        specialKeypadPanel = ScrollView(this).apply {
            val heightPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 260f, resources.displayMetrics).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
            setBackgroundColor(Color.parseColor("#0c0d12"))
            visibility = View.GONE
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        fun addRow(buttons: List<Pair<String, () -> Unit>>) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val rowHeight = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 46f, resources.displayMetrics).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowHeight).apply {
                    setMargins(0, 4, 0, 4)
                }
            }
            for ((label, action) in buttons) {
                val btn = Button(this).apply {
                    text = label
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#151620"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        setMargins(4, 0, 4, 0)
                    }
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        action()
                    }
                }
                row.addView(btn)
            }
            container.addView(row)
        }

        // Row 1: ESC, TAB, Ctrl, Alt, Del, Backspace
        addRow(listOf(
            "ESC" to { sendKey("\u001b") },
            "TAB" to { sendKey("\t") },
            "CTRL" to { toggleCtrlModifier() },
            "ALT" to { toggleAltModifier() },
            "DEL" to { sendKey("\u001b[3~") },
            "⌫" to { sendKey("\u007f") }
        ))

        // Row 2: F1 - F6, PgUp
        addRow(listOf(
            "F1" to { sendKey("\u001bOP") },
            "F2" to { sendKey("\u001bOQ") },
            "F3" to { sendKey("\u001bOR") },
            "F4" to { sendKey("\u001bOS") },
            "F5" to { sendKey("\u001b[15~") },
            "F6" to { sendKey("\u001b[17~") },
            "PgUp" to { sendKey("\u001b[5~") }
        ))

        // Row 3: F7 - F12, PgDn
        addRow(listOf(
            "F7" to { sendKey("\u001b[18~") },
            "F8" to { sendKey("\u001b[19~") },
            "F9" to { sendKey("\u001b[20~") },
            "F10" to { sendKey("\u001b[21~") },
            "F11" to { sendKey("\u001b[23~") },
            "F12" to { sendKey("\u001b[24~") },
            "PgDn" to { sendKey("\u001b[6~") }
        ))

        // Row 4: HOME, ▲, END, Ctrl+C, Ctrl+D, Ctrl+Z
        addRow(listOf(
            "HOME" to { sendKey("\u001b[1~") },
            "▲" to { sendKey("\u001b[A") },
            "END" to { sendKey("\u001b[4~") },
            "Ctrl+C" to { sendKey("\u0003") },
            "Ctrl+D" to { sendKey("\u0004") },
            "Ctrl+Z" to { sendKey("\u001a") }
        ))

        // Row 5: ◀, ENTER, ▶, ▼, |, clear
        addRow(listOf(
            "◀" to { sendKey("\u001b[D") },
            "ENTER" to { sendKey("\r") },
            "▶" to { sendKey("\u001b[C") },
            "▼" to { sendKey("\u001b[B") },
            "|" to { sendKey("|") },
            "clear" to { sendKey("clear\r") }
        ))

        specialKeypadPanel.addView(container)
        return specialKeypadPanel
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
        config = try { ProotManager.setupProotEnvironment(this, rootfsDirName, mountStorage) } catch (e: Exception) { 
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
