package com.linux_core.ui.terminal

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * Návrhový bar příkazů nad klávesnicí (historie příkazů → tlačítka s návrhy)
 * a sledování aktuálně psaného příkazu pro účely návrhů.
 */
internal fun TerminalActivity.buildSuggestionBar(): HorizontalScrollView {
    suggestionBar =
        HorizontalScrollView(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor("#1a1b26"))
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }

    suggestionContainer =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams =
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(8, 4, 8, 4)
        }

    suggestionBar.addView(suggestionContainer)
    return suggestionBar
}

fun TerminalActivity.updateSuggestions() {
    // Debounce: zpracujeme jen poslední stav (neměnící se návrhy se
    // nepřestavují), abychom neblokovali IME commitText main vlaknem.
    suggestionHandler.removeCallbacks(rebuildSuggestionsRunnable)
    suggestionHandler.post(rebuildSuggestionsRunnable)
}

// Beží na main threadu (postováno přes suggestionHandler).
internal fun TerminalActivity.rebuildSuggestions() {
    val input = currentCommand.toString()
    val suggestions = historyManager.getSuggestions(input)
    if (suggestions == lastSuggestedList) return
    lastSuggestedList = suggestions

    if (suggestions.isEmpty()) {
        suggestionBar.visibility = View.GONE
        return
    }
    suggestionBar.visibility = View.VISIBLE
    suggestionContainer.removeAllViews()
    for (sug in suggestions) {
        val btn =
            Button(this).apply {
                text = sug
                textSize = 10f
                isAllCaps = false
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#a9b1d6"))
                setBackgroundColor(Color.parseColor("#24283b"))
                val params =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics).toInt(),
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

internal fun TerminalActivity.applySuggestion(suggestion: String) {
    // Clear current line using Ctrl+U (\u0015)
    currentSession?.write("\u0015")
    currentSession?.write(suggestion)
    currentCommand.setLength(0)
    currentCommand.append(suggestion)
    updateSuggestions()
    terminalView.requestFocus()
}

fun TerminalActivity.onTerminalInput(codePoint: Int) {
    if (codePoint == 127 || codePoint == 8) { // Backspace
        if (currentCommand.isNotEmpty()) {
            currentCommand.setLength(currentCommand.length - 1)
        }
    } else if (codePoint in 32..126) { // Printable chars
        currentCommand.append(codePoint.toChar())
    }
    updateSuggestions()
}

fun TerminalActivity.onTerminalEnter() {
    val cmd = currentCommand.toString().trim()
    if (cmd.isNotEmpty()) {
        historyManager.addCommand(cmd)
    }
    currentCommand.setLength(0)
    updateSuggestions()
}

fun TerminalActivity.resetCurrentCommand() {
    currentCommand.setLength(0)
    updateSuggestions()
}
