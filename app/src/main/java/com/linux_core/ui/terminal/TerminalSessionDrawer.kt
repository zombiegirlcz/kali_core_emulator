package com.linux_core.ui.terminal

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.linux_core.core.DEFAULT_BOOT_MODE
import com.linux_core.core.HackerKeyboardRows
import com.linux_core.core.ProotManager
import com.linux_core.core.TerminalService
import com.linux_core.core.loadBootMode
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sesní drawer (seznam session s KALI/PARROT taby) a klávesnicové taby
 * ("Control"/"Symbols"/... panel nad speciální klávesnicí).
 */
internal fun TerminalActivity.updateSessionDrawerImpl() {
    runOnUiThread {
        if (isDrawerExpanded) {
            // Expanded mode padding & visibility
            drawerViewContentLayout.setPadding(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt(),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt(),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt(),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt(),
            )
            drawerHeader.visibility = View.VISIBLE

            // Futuristic console header
            val ssb = android.text.SpannableStringBuilder()
            ssb.append("🛰️ OPERATOR CONSOLE\n")
            val startRam = ssb.length
            ssb.append("[RAM: ${getTotalRamUsage()}]")
            ssb.setSpan(
                android.text.style.ForegroundColorSpan(Color.parseColor("#00FF41")),
                0,
                startRam,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            ssb.setSpan(
                android.text.style.ForegroundColorSpan(Color.parseColor("#00E5FF")),
                startRam,
                ssb.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            drawerHeader.text = ssb

            tabLayout.visibility = View.VISIBLE
            btnAddSession.visibility = View.VISIBLE

            // Update drawer tab button styling
            drawerTabButtons.forEach { (tabCode, btn) ->
                val isSel = (tabCode == activeDrawerTab)
                btn.setTextColor(if (isSel) Color.BLACK else Color.WHITE)
                btn.background =
                    if (isSel) {
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
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt(),
            )
            drawerHeader.visibility = View.GONE
            tabLayout.visibility = View.GONE
            btnAddSession.visibility = View.GONE
        }

        val serviceSessions = TerminalService.sessions
        sessionDrawerContainer.removeAllViews()

        if (!isDrawerExpanded) {
            // In minimized mode, add a small GUI toggle button at the top of the session list
            val btnGuiToggleMin =
                Button(this).apply {
                    text = "🖥️"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    background = createCircularDrawable(Color.parseColor("#12131a"), Color.parseColor("#1e2026"), 1f)
                    val sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics).toInt()
                    val params =
                        LinearLayout.LayoutParams(sizePx, sizePx).apply {
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
            val memBytes =
                com.linux_core.core.ProcessResolver
                    .getSessionMemoryUsage(session)
            val memMb = memBytes.toDouble() / (1024.0 * 1024.0)
            val memStr = String.format(java.util.Locale.US, "%.1f MB", memMb)

            // Vertical container row for the session card
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL

                    val pxPaddingHoriz =
                        TypedValue
                            .applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP,
                                if (isDrawerExpanded) 12f else 6f,
                                resources.displayMetrics,
                            ).toInt()
                    val pxPaddingVert =
                        TypedValue
                            .applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP,
                                if (isDrawerExpanded) 12f else 8f,
                                resources.displayMetrics,
                            ).toInt()
                    setPadding(pxPaddingHoriz, pxPaddingVert, pxPaddingHoriz, pxPaddingVert)

                    background =
                        if (isActive) {
                            createRoundedDrawable(Color.parseColor("#121b16"), 8f, Color.parseColor("#00FF41"), 1f)
                        } else if (isIgnored) {
                            createRoundedDrawable(Color.parseColor("#1c150c"), 8f, Color.parseColor("#FF9900"), 1f)
                        } else {
                            createRoundedDrawable(Color.parseColor("#090a0f"), 8f, Color.parseColor("#1e2026"), 1f)
                        }

                    val params =
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                setMargins(0, 6, 0, 6)
                            }
                    layoutParams = params
                }

            // Glow/Indicator vertical line on the left side of the row (only in expanded mode)
            if (isDrawerExpanded) {
                val indicator =
                    View(this).apply {
                        val colorStr =
                            if (isActive) {
                                "#00FF41"
                            } else if (isIgnored) {
                                "#FF9900"
                            } else {
                                "#20222e"
                            }
                        background = createRoundedDrawable(Color.parseColor(colorStr), 2f)
                        val params =
                            LinearLayout
                                .LayoutParams(
                                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt(),
                                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt(),
                                ).apply {
                                    setMargins(0, 0, 10, 0)
                                }
                        layoutParams = params
                    }
                row.addView(indicator)
            }

            if (isDrawerExpanded) {
                val label =
                    TextView(this).apply {
                        val customName = TerminalService.getSessionName(session)
                        val baseText = if (!customName.isNullOrEmpty()) customName else "Session ${i + 1}"

                        val ssbLabel = android.text.SpannableStringBuilder()
                        ssbLabel.append("$distroBadge ")
                        val nameStart = ssbLabel.length
                        ssbLabel.append(baseText)
                        ssbLabel.setSpan(
                            android.text.style.ForegroundColorSpan(if (isActive) Color.parseColor("#00FF41") else Color.WHITE),
                            nameStart,
                            ssbLabel.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )

                        ssbLabel.append("\n")
                        val memStart = ssbLabel.length
                        ssbLabel.append("  RAM: $memStr")
                        ssbLabel.setSpan(
                            android.text.style.ForegroundColorSpan(Color.parseColor("#A9B1D6")),
                            memStart,
                            ssbLabel.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )

                        if (isIgnored) {
                            ssbLabel.append(" ")
                            val vpnStart = ssbLabel.length
                            ssbLabel.append("[BYPASS]")
                            ssbLabel.setSpan(
                                android.text.style.ForegroundColorSpan(Color.parseColor("#FF9900")),
                                vpnStart,
                                ssbLabel.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }

                        text = ssbLabel
                        textSize = 12f
                        typeface = Typeface.MONOSPACE

                        val params =
                            LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                        layoutParams = params
                    }
                row.addView(label)

                // Quick Close Button on the right
                val btnClose =
                    TextView(this).apply {
                        text = "✕"
                        textSize = 12f
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setTextColor(Color.parseColor("#A9B1D6"))
                        gravity = Gravity.CENTER
                        background = createRoundedDrawable(Color.parseColor("#1c1d27"), 12f)
                        val sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
                        val params =
                            LinearLayout.LayoutParams(sizePx, sizePx).apply {
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
                val emojiLabel =
                    TextView(this).apply {
                        text = distroBadge
                        textSize = 18f
                        gravity = Gravity.CENTER
                        background =
                            if (isActive) {
                                createCircularDrawable(Color.parseColor("#121b16"), Color.parseColor("#00FF41"), 1.5f)
                            } else {
                                createCircularDrawable(Color.parseColor("#090a0f"), Color.parseColor("#1e2026"), 1f)
                            }
                        val sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics).toInt()
                        val params =
                            LinearLayout.LayoutParams(sizePx, sizePx).apply {
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

internal fun TerminalActivity.createRoundedDrawable(
    backgroundColor: Int,
    cornerRadiusDp: Float,
    strokeColor: Int = 0,
    strokeWidthDp: Float = 0f,
): android.graphics.drawable.GradientDrawable =
    android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(backgroundColor)
        val radiusPx =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                cornerRadiusDp,
                resources.displayMetrics,
            )
        setCornerRadius(radiusPx)
        if (strokeColor != 0 && strokeWidthDp > 0f) {
            val strokeWidthPx =
                TypedValue
                    .applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        strokeWidthDp,
                        resources.displayMetrics,
                    ).toInt()
            setStroke(strokeWidthPx, strokeColor)
        }
    }

internal fun TerminalActivity.createCircularDrawable(
    backgroundColor: Int,
    strokeColor: Int = 0,
    strokeWidthDp: Float = 0f,
): android.graphics.drawable.GradientDrawable =
    android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(backgroundColor)
        if (strokeColor != 0 && strokeWidthDp > 0f) {
            val strokeWidthPx =
                TypedValue
                    .applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        strokeWidthDp,
                        resources.displayMetrics,
                    ).toInt()
            setStroke(strokeWidthPx, strokeColor)
        }
    }

internal fun TerminalActivity.updateTopbarTitle() {
    runOnUiThread {
        if (!isStatusTitleInitialized) return@runOnUiThread
        val session = currentSession
        if (session != null) {
            val distro = TerminalService.getSessionDistro(session)
            val isParrot = distro.contains("parrot")
            val distroBadge = if (isParrot) "🦜 PARROT OS" else "🐉 KALI NetHunter"
            statusTitle.text = "$distroBadge [$activeViewMode]"
            statusTitle.setTextColor(if (isParrot) Color.parseColor("#00E5FF") else Color.parseColor("#00FF41"))
        } else {
            statusTitle.text = "🐉 NETHUNTER OPERATOR"
            statusTitle.setTextColor(Color.parseColor("#00FF41"))
        }
    }
}

internal fun TerminalActivity.getTotalRamUsage(): String =
    try {
        val actManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
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

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %cBs", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

internal fun TerminalActivity.startDrawerRamUpdateLoop() {
    drawerUpdateHandler.removeCallbacks(drawerRamUpdater)
    drawerUpdateHandler.post(drawerRamUpdater)
}

internal fun TerminalActivity.stopDrawerRamUpdateLoop() {
    drawerUpdateHandler.removeCallbacks(drawerRamUpdater)
}

internal fun TerminalActivity.showRenameDialog(
    session: TerminalSession,
    defaultIndex: Int,
) {
    val currentName = TerminalService.getSessionName(session) ?: "Session $defaultIndex"
    val input =
        android.widget.EditText(this).apply {
            setText(currentName)
            setSingleLine(true)
            setSelection(currentName.length)
        }

    val container =
        android.widget.FrameLayout(this).apply {
            val padding =
                TypedValue
                    .applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        20f,
                        resources.displayMetrics,
                    ).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(input)
        }

    android.app.AlertDialog
        .Builder(this)
        .setTitle("Rename Session")
        .setMessage("Enter custom name for this session:")
        .setView(container)
        .setPositiveButton("Rename") { dialog, _ ->
            val newName = input.text.toString().trim()
            if (newName.isNotEmpty()) {
                TerminalService.setSessionName(session, newName)
            }
            dialog.dismiss()
        }.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }.show()
}

internal fun TerminalActivity.addNewSession() {
    Log.i(TerminalActivity.TAG, "addNewSession")
    // MULTI-ROOTFS: config aktivity může ukazovat na jiné distro než aktuální
    // session (uživatel přepnl drawerem mezi Kali a Parrot). Vždy postavíme
    // config podle distra AKTIVNÍ session, aby "+" vytvořilo session téhož
    // rootfs, který si uživatel prohlíží.
    val sid = currentSession?.let { TerminalService.getSessionId(it) }
    val distroName = if (sid != null) TerminalService.sessionDistros[sid] ?: "nh/distro/kali" else "nh/distro/kali"
    val isDocker =
        distroName.startsWith("docker-") ||
            distroName.startsWith("oci-") ||
            distroName.startsWith("nh/distro/docker/")
    val mountStorageSaved = getSharedPreferences("vpn_settings", Context.MODE_PRIVATE).getBoolean("mount_storage", false)
    lifecycleScope.launch(Dispatchers.IO) {
        val cfg =
            try {
                val distroId1 = distroName.substringAfterLast("/")
                val bootMode1 = loadBootMode(this@addNewSession, distroId1, DEFAULT_BOOT_MODE)
                ProotManager.setupProotEnvironment(
                    this@addNewSession,
                    distroName,
                    mountStorageSaved,
                    null,
                    false,
                    isDocker,
                    bootMode1,
                )
            } catch (e: Exception) {
                Log.e(TerminalActivity.TAG, "addNewSession setup failed for $distroName", e)
                null
            }
        withContext(Dispatchers.Main) {
            if (cfg == null) {
                showError("Setup failed: $distroName")
                return@withContext
            }
            config = cfg
            val session =
                try {
                    TerminalService.createSession(this@addNewSession, cfg, terminalView) { showError(it) }
                } catch (e: Exception) {
                    showError("Session error: ${e.message}")
                    return@withContext
                }
            switchToSession(session)
            updateSessionDrawer()
        }
    }
}

internal fun TerminalActivity.switchToSession(session: TerminalSession) {
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

internal fun TerminalActivity.closeSession(session: TerminalSession) {
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

fun TerminalActivity.onSessionEnded(session: TerminalSession) {
    val remaining = TerminalService.sessions
    if (!remaining.contains(session)) {
        if (remaining.isEmpty()) {
            finish()
        } else {
            updateSessionDrawer()
        }
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

internal fun TerminalActivity.buildTabs() {
    tabContainer.removeAllViews()
    tabButtons.clear()

    for (tab in tabsList) {
        val btn =
            Button(this).apply {
                text =
                    when (tab) {
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
                val params =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics).toInt(),
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

internal fun TerminalActivity.updateTabStyles() {
    for ((tab, btn) in tabButtons) {
        val isActive = (tab == activeKeyboardTab)
        val bgDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor(if (isActive) "#151620" else "#08090d"))
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics)
                setStroke(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, resources.displayMetrics).toInt(),
                    Color.parseColor(if (isActive) "#00FF41" else "#2a2b45"),
                )
            }
        btn.background = bgDrawable
        btn.setTextColor(if (isActive) Color.parseColor("#00FF41") else Color.WHITE)
    }
}

internal fun TerminalActivity.renderActiveTab() {
    keysContainer.removeAllViews()

    val keys =
        when (activeKeyboardTab) {
            "CONTROL" -> HackerKeyboardRows.row1Control
            "SYMBOLS" -> HackerKeyboardRows.row3Symbols
            "NAVIGATION" -> HackerKeyboardRows.row4Navigation
            "CTRL COMBOS" -> HackerKeyboardRows.row5CtrlCombos
            "F-KEYS" -> HackerKeyboardRows.row6Function
            else -> emptyList()
        }

    val columns =
        when (activeKeyboardTab) {
            "SYMBOLS", "CTRL COMBOS" -> 5
            else -> 4
        }

    var currentRow: LinearLayout? = null
    val rowHeight =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics).toInt()

    for (i in keys.indices) {
        if (i % columns == 0) {
            currentRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                rowHeight,
                            ).apply {
                                setMargins(0, 3, 0, 3)
                            }
                }
            keysContainer.addView(currentRow)
        }

        val key = keys[i]
        val btn =
            Button(this).apply {
                text = key.label
                textSize = 12f
                isAllCaps = false
                typeface = Typeface.MONOSPACE
                setTextColor(Color.WHITE)

                val bgDrawable =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#151620"))
                        cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics)
                        setStroke(
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics).toInt(),
                            Color.parseColor("#2a2b45"),
                        )
                    }
                background = bgDrawable

                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
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
            val spacer =
                View(this).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                            setMargins(4, 0, 4, 0)
                        }
                }
            currentRow.addView(spacer)
        }
    }
}
