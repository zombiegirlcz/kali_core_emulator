package cz.hackai.nethunter_ai_operator.ui.terminal

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import cz.hackai.nethunter_ai_operator.core.ProotConfig
import cz.hackai.nethunter_ai_operator.core.ProotManager
import java.io.File

class TerminalActivity : ComponentActivity() {
    companion object {
        private const val TAG = "TerminalActivity"
    }

    private lateinit var container: FrameLayout
    private lateinit var terminalView: TerminalView
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorText: TextView
    private var config: ProotConfig? = null
    private var currentSession: TerminalSession? = null
    private val viewClient = TerminalViewClientImpl()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewClient.setActivity(this)

        container = FrameLayout(this)
        container.setBackgroundColor(Color.BLACK)

        terminalView = TerminalView(this, null)
        terminalView.setBackgroundColor(Color.BLACK)
        terminalView.setTextSize(32)
        terminalView.setTerminalViewClient(viewClient)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        
        // Explicitly handle click to show keyboard
        terminalView.setOnClickListener {
            Log.d(TAG, "TerminalView clicked - requesting keyboard")
            showSoftKeyboard()
        }

        container.addView(terminalView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        errorLayout = buildErrorOverlay()
        container.addView(errorLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setContentView(container)
        setupAndStartSession()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - requesting focus")
        terminalView.requestFocus()
        showSoftKeyboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentSession?.finishIfRunning()
        currentSession = null
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
        config = try { ProotManager.setupProotEnvironment(this) } catch (e: Exception) { 
            showError("Setup failed: ${e.message}"); return 
        }
        startTerminalSession(config!!)
    }

    private fun startTerminalSession(config: ProotConfig) {
        Log.i(TAG, "startTerminalSession")
        val session = try {
            TerminalSession(
                config.command[0], config.cwd, config.command, config.env, 1000,
                TerminalSessionClientImpl(terminalView, this, System.currentTimeMillis()) { showError(it) }
            )
        } catch (e: Exception) { showError("Session error: ${e.message}"); return }

        terminalView.attachSession(session)
        currentSession = session
        
        terminalView.post {
            terminalView.requestFocus()
            terminalView.onScreenUpdated()
            Log.d(TAG, "TerminalView attached and focused")
        }
        
        showSoftKeyboard()
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

    override fun onScale(scale: Float) = scale
    override fun onSingleTapUp(e: MotionEvent) {
        Log.d("TerminalView", "onSingleTapUp")
        activity?.showSoftKeyboard()
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        Log.d("TerminalView", "onKeyDown: keyCode=$keyCode")
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            session.write("\r")
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

class TerminalSessionClientImpl(
    private val view: TerminalView,
    private val activity: android.app.Activity,
    private val startTimeMs: Long,
    private val onError: (String) -> Unit
) : TerminalSessionClient {
    private var dataCount = 0
    override fun onTextChanged(session: TerminalSession) { 
        dataCount++
        if (dataCount % 10 == 0) Log.d("TermSession", "onTextChanged count: $dataCount")
        view.onScreenUpdated() 
    }
    override fun onTitleChanged(session: TerminalSession) {}
    override fun onSessionFinished(session: TerminalSession) {
        Log.i("TermSession", "onSessionFinished: exitStatus=${session.exitStatus}")
        if (session.exitStatus != 0 && session.exitStatus != -9) {
            Handler(Looper.getMainLooper()).post { onError("Exit code: ${session.exitStatus}") }
        }
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession) {}
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
