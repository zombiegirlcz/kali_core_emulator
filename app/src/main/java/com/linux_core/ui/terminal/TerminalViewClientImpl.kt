package com.linux_core.ui.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient

class TerminalViewClientImpl : TerminalViewClient {
    private var activity: TerminalActivity? = null

    fun setActivity(activity: TerminalActivity) {
        this.activity = activity
    }

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

    override fun onKeyDown(
        keyCode: Int,
        e: KeyEvent,
        session: TerminalSession,
    ): Boolean {
        Log.d("TerminalView", "onKeyDown: keyCode=$keyCode")
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            activity?.onTerminalEnter()
            session.write("\r")
            return true
        }
        val arrowSequence =
            when (keyCode) {
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

    override fun onKeyUp(
        keyCode: Int,
        e: KeyEvent,
    ) = false

    override fun onLongPress(event: MotionEvent) = false

    override fun readControlKey() = false

    override fun readAltKey() = false

    override fun readShiftKey() = activity?.customShiftActive == true

    override fun readFnKey() = false

    override fun onCodePoint(
        codePoint: Int,
        ctrlDown: Boolean,
        session: TerminalSession,
    ): Boolean {
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

    override fun logError(
        tag: String,
        message: String,
    ) {
        Log.e(tag, message)
    }

    override fun logWarn(
        tag: String,
        message: String,
    ) {
        Log.w(tag, message)
    }

    override fun logInfo(
        tag: String,
        message: String,
    ) {
        Log.i(tag, message)
    }

    override fun logDebug(
        tag: String,
        message: String,
    ) {
        Log.d(tag, message)
    }

    override fun logVerbose(
        tag: String,
        message: String,
    ) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(
        tag: String,
        message: String,
        e: Exception,
    ) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(
        tag: String,
        e: Exception,
    ) {
        Log.e(tag, "Stack trace", e)
    }
}
