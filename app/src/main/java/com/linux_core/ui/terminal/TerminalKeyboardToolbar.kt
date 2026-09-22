package com.linux_core.ui.terminal

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.linux_core.core.terminal.KeyType

/**
 * Klávesnicová lišta nad terminálem (ESC/TAB/CTRL/ALT/SHIFT stránkovaný
 * ViewPager2 toolbar) a rozbalovatelný speciální keypad panel (taby +
 * mřížka kláves) — včetně odesílání sekvencí a modifikátorů Ctrl/Alt/Shift.
 */
internal fun TerminalActivity.buildExtraKeysToolbar(): View {
    val rootContainer =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor("#090a0f"))
            setPadding(0, 4, 0, 4)
        }

    // Setup ViewPager2
    val viewPager =
        androidx.viewpager2.widget.ViewPager2(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt(),
                )
        }

    // Setup Dot indicator layout
    val dotsLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt(),
                    ).apply {
                        setMargins(0, 2, 0, 4)
                    }
        }

    // Define Pages
    val page1 =
        listOf(
            "ESC" to { sendKey("\u001b") },
            "TAB" to { sendKey("\t") },
            "CTRL" to { toggleCtrlModifier() },
            "ALT" to { toggleAltModifier() },
            "SHIFT" to { toggleShiftModifier() },
            "⌨️" to { toggleSpecialKeypad(specialKeypadPanel.visibility == View.GONE) },
        )

    val page2 =
        listOf(
            "|" to { sendKey("|") },
            "/" to { sendKey("/") },
            "\\" to { sendKey("\\") },
            ":" to { sendKey(":") },
            "-" to { sendKey("-") },
            "_" to { sendKey("_") },
            "~" to { sendKey("~") },
            "=" to { sendKey("=") },
        )

    val page3 =
        listOf(
            "←" to { sendKey("\u001b[D") },
            "↑" to { sendKey("\u001b[A") },
            "↓" to { sendKey("\u001b[B") },
            "→" to { sendKey("\u001b[C") },
            "Home" to { sendKey("\u001b[H") },
            "End" to { sendKey("\u001b[F") },
        )

    val page4 =
        listOf(
            "F1" to { sendKey("\u001bOP") },
            "F2" to { sendKey("\u001bOQ") },
            "F3" to { sendKey("\u001bOR") },
            "F4" to { sendKey("\u001bOS") },
            "F5" to { sendKey("\u001b[15~") },
            "F6" to { sendKey("\u001b[17~") },
            "F7" to { sendKey("\u001b[18~") },
            "F8" to { sendKey("\u001b[19~") },
        )

    val pages = listOf(page1, page2, page3, page4)

    // ViewPager2 Adapter
    viewPager.adapter =
        object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = pages.size

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val container =
                    LinearLayout(parent.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams =
                            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(12, 0, 12, 0)
                    }
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(container) {}
            }

            override fun onBindViewHolder(
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                position: Int,
            ) {
                val container = holder.itemView as LinearLayout
                container.removeAllViews()

                val keys = pages[position]
                for ((label, action) in keys) {
                    val btn =
                        Button(holder.itemView.context).apply {
                            text = label
                            textSize = 12f
                            typeface = Typeface.MONOSPACE
                            setTextColor(Color.WHITE)

                            // Premium Visual style: dark cards with rounded corners
                            val isModifier = label == "CTRL" || label == "ALT" || label == "SHIFT"
                            val bgDrawable =
                                android.graphics.drawable.GradientDrawable().apply {
                                    setColor(Color.parseColor(if (isModifier) "#121320" else "#1c1d30"))
                                    cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics)
                                    setStroke(
                                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics).toInt(),
                                        Color.parseColor("#2a2b45"),
                                    )
                                }
                            background = bgDrawable

                            val params =
                                LinearLayout
                                    .LayoutParams(
                                        0,
                                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics).toInt(),
                                        1f,
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
        val dot =
            View(this).apply {
                val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()
                val params =
                    LinearLayout.LayoutParams(size, size).apply {
                        setMargins(6, 0, 6, 0)
                    }
                layoutParams = params
                val drawable =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor("#44475a"))
                    }
                background = drawable
            }
        dotsLayout.addView(dot)
        dotViews.add(dot)
    }

    // Listen to page changes to update active dot indicators
    viewPager.registerOnPageChangeCallback(
        object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                for (i in dotViews.indices) {
                    val active = (i == position)
                    val drawable = dotViews[i].background as android.graphics.drawable.GradientDrawable
                    drawable.setColor(Color.parseColor(if (active) "#00FF41" else "#44475a"))
                }
            }
        },
    )

    rootContainer.addView(viewPager)
    rootContainer.addView(dotsLayout)
    return rootContainer
}

internal fun TerminalActivity.buildSpecialKeypadPanel(): LinearLayout {
    specialKeypadPanel =
        LinearLayout(this).apply {
            val heightPx =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200f, resources.displayMetrics).toInt()
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#08090d"))
            visibility = View.GONE
        }

    // Tab bar container
    val tabScroll =
        HorizontalScrollView(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#06070a"))
            setPadding(4, 4, 4, 4)
        }

    tabContainer =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams =
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
    tabScroll.addView(tabContainer)
    specialKeypadPanel.addView(tabScroll)

    // Scrollable keys container
    val keysScroll =
        ScrollView(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = true
        }

    keysContainer =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            layoutParams =
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
    keysScroll.addView(keysContainer)
    specialKeypadPanel.addView(keysScroll)

    buildTabs()
    renderActiveTab()

    return specialKeypadPanel
}

internal fun TerminalActivity.handleHackerKeyPress(key: KeyType) {
    if (key == KeyType.CTRL_C || key == KeyType.CTRL_L || key == KeyType.CTRL_U) {
        resetCurrentCommand()
    }

    val sequence =
        when (key) {
            // Row 1 - Control
            KeyType.ESC -> {
                "\u001b"
            }

            KeyType.TAB -> {
                "\t"
            }

            KeyType.ENTER -> {
                "\r"
            }

            KeyType.BACK_SPACE -> {
                "\u007f"
            }

            KeyType.INSERT -> {
                "\u001b[2~"
            }

            KeyType.DELETE -> {
                "\u001b[3~"
            }

            KeyType.SHIFT_TAB -> {
                "\u001b[Z"
            }

            KeyType.PASTE -> {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString()
                    if (!text.isNullOrEmpty()) {
                        pasteToCurrentSession(text)
                    }
                }
                return
            }

            // Row 4 - Navigation
            KeyType.PAGE_UP -> {
                "\u001b[5~"
            }

            KeyType.PAGE_DOWN -> {
                "\u001b[6~"
            }

            KeyType.ARROW_LEFT -> {
                "\u001b[D"
            }

            KeyType.ARROW_RIGHT -> {
                "\u001b[C"
            }

            KeyType.ARROW_UP -> {
                "\u001b[A"
            }

            KeyType.ARROW_DOWN -> {
                "\u001b[B"
            }

            KeyType.HOME -> {
                "\u001b[H"
            }

            KeyType.END -> {
                "\u001b[F"
            }

            // Row 5 - Ctrl Combinations
            KeyType.CTRL_UNDERSCORE -> {
                "\u001f"
            }

            KeyType.CTRL_XX -> {
                "\u0018\u0018"
            }

            KeyType.CTRL_Z -> {
                "\u001a"
            }

            KeyType.CTRL_R -> {
                "\u0012"
            }

            KeyType.CTRL_G -> {
                "\u0007"
            }

            KeyType.CTRL_A -> {
                "\u0001"
            }

            KeyType.CTRL_B -> {
                "\u0002"
            }

            KeyType.CTRL_X -> {
                "\u0018"
            }

            KeyType.CTRL_F -> {
                "\u0006"
            }

            KeyType.CTRL_P -> {
                "\u0010"
            }

            KeyType.CTRL_N -> {
                "\u000e"
            }

            KeyType.CTRL_C -> {
                "\u0003"
            }

            KeyType.CTRL_H -> {
                "\u0008"
            }

            KeyType.CTRL_S -> {
                "\u0013"
            }

            KeyType.CTRL_Q -> {
                "\u0011"
            }

            KeyType.CTRL_U -> {
                "\u0015"
            }

            KeyType.CTRL_W -> {
                "\u0017"
            }

            KeyType.CTRL_L -> {
                "\u000c"
            }

            KeyType.CTRL_D -> {
                "\u0004"
            }

            // Row 6 - F-keys
            KeyType.F1 -> {
                "\u001bOP"
            }

            KeyType.F2 -> {
                "\u001bOQ"
            }

            KeyType.F3 -> {
                "\u001bOR"
            }

            KeyType.F4 -> {
                "\u001bOS"
            }

            KeyType.F5 -> {
                "\u001b[15~"
            }

            KeyType.F6 -> {
                "\u001b[17~"
            }

            KeyType.F7 -> {
                "\u001b[18~"
            }

            KeyType.F8 -> {
                "\u001b[19~"
            }

            KeyType.F9 -> {
                "\u001b[20~"
            }

            KeyType.F10 -> {
                "\u001b[21~"
            }

            KeyType.F11 -> {
                "\u001b[23~"
            }

            KeyType.F12 -> {
                "\u001b[24~"
            }

            KeyType.F13 -> {
                "\u001b[25~"
            }

            KeyType.F14 -> {
                "\u001b[26~"
            }

            KeyType.F15 -> {
                "\u001b[28~"
            }

            KeyType.F16 -> {
                "\u001b[29~"
            }

            KeyType.F17 -> {
                "\u001b[31~"
            }

            KeyType.F18 -> {
                "\u001b[32~"
            }

            KeyType.F19 -> {
                "\u001b[33~"
            }

            KeyType.F20 -> {
                "\u001b[34~"
            }

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
            else -> {
                key.label
            }
        }

    sendKey(sequence)
}

internal fun TerminalActivity.sendKey(sequence: String) {
    currentSession?.write(sequence)
    terminalView.requestFocus()
}

/**
 * Vloží text ze schránky přes bracketed-paste (Termux TerminalEmulator.paste()).
 * Fix BUG (clipboard paste): místo raw session.write()/sendKey() se použije
 * emulátorová paste(), která:
 *  1) odstraní ESC + C1 control znaky z vkládaného textu,
 *  2) normalizuje nové řádky (\r\n/\n → \r),
 *  3) obalí payload do \e[200~ … \e[201~ když aplikace uvnitř (nano, bash…)
 *     aktivovala bracketed paste (DECSET 2004) — vložené nové řádky a control
 *     sekvence se tak NIKDY nevykonají jako interaktivní zkratky (^K, ^M,
 *     <ffffffff> v nano) a UTF-8 diakritika projde jako jeden raw buffer.
 */
internal fun TerminalActivity.pasteToCurrentSession(text: String) {
    val session = currentSession ?: return
    val emulator = session.getEmulator()
    if (emulator != null) {
        emulator.paste(text)
    } else {
        session.write(text)
    }
    terminalView.requestFocus()
}

internal fun TerminalActivity.toggleCtrlModifier() {
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

internal fun TerminalActivity.toggleAltModifier() {
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

internal fun TerminalActivity.toggleShiftModifier() {
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

fun TerminalActivity.toggleSpecialKeypad(show: Boolean) {
    if (show) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
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

fun TerminalActivity.resetModifiers() {
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
