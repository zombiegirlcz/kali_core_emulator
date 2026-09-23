package com.linux_core.ui.terminal

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

/**
 * Panel služeb (ADB shell daemon) v topbaru — sbalený indikátor, rozbalený
 * detail se start/stop tlačítkem a "START ALL"/refresh akce.
 */
internal fun TerminalActivity.buildServicesPanel(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        val h = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 34f, resources.displayMetrics).toInt()
        layoutParams =
            LinearLayout
                .LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    h,
                ).apply { setMargins(8, 2, 8, 2) }

        btnAdb =
            Button(this@buildServicesPanel).apply {
                text = "📡 ADB ○"
                textSize = 9f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.GRAY)
                background = createRoundedDrawable(Color.parseColor("#0c0d12"), 6f, Color.parseColor("#1e2026"), 1f)
                setPadding(10, 4, 10, 4)
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt(),
                    )
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    toggleServiceDetail("adb")
                }
            }
        addView(btnAdb)

        View(this@buildServicesPanel)
            .apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt(),
                        1,
                    )
            }.also { addView(it) }

        // START ALL button
        Button(this@buildServicesPanel)
            .apply {
                text = "▶ ALL"
                textSize = 9f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.parseColor("#00FF41"))
                background = createRoundedDrawable(Color.parseColor("#0f1017"), 6f, Color.parseColor("#00FF41"), 1f)
                setPadding(10, 4, 10, 4)
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt(),
                    )
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    startAllServices()
                }
            }.also { addView(it) }

        View(this@buildServicesPanel)
            .apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt(),
                        1,
                    )
            }.also { addView(it) }

        // Refresh button
        Button(this@buildServicesPanel)
            .apply {
                text = "↻"
                textSize = 12f
                setTextColor(Color.GRAY)
                background = null
                setPadding(6, 0, 6, 0)
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt(),
                    )
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    updateAllServiceIndicators()
                }
            }.also { addView(it) }
    }

internal fun TerminalActivity.buildServicesDetailPanel(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        val p = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
        setPadding(p, 4, p, 4)
        layoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        setBackgroundColor(Color.parseColor("#0c0d12"))
    }

internal fun TerminalActivity.toggleServicesPanel() {
    isServicesExpanded = !isServicesExpanded
    servicesPanel.visibility = if (isServicesExpanded) View.VISIBLE else View.GONE
    btnServicesToggle.text = if (isServicesExpanded) "▲" else "▼"

    if (isServicesExpanded) {
        updateAllServiceIndicators()
        servicesUpdateHandler.removeCallbacks(servicesPoller)
        servicesUpdateHandler.post(servicesPoller)
    } else {
        servicesDetailPanel.visibility = View.GONE
        expandedService = null
        // Poller necháváme běžet (jen pomalejší interval) — indikátor ADB
        // musí svítit/zhasínat i se sbaleným panelem.
    }
}

internal fun TerminalActivity.toggleServiceDetail(service: String) {
    if (expandedService == service) {
        servicesDetailPanel.visibility = View.GONE
        expandedService = null
    } else {
        expandedService = service
        updateServiceDetail(service)
        servicesDetailPanel.visibility = View.VISIBLE
    }
}

internal fun TerminalActivity.updateAllServiceIndicators() {
    updateServiceIndicator("adb", btnAdb)

    val svc = expandedService
    if (svc != null) {
        updateServiceDetail(svc)
    }
}

internal fun TerminalActivity.updateServiceIndicator(
    service: String,
    button: Button,
) {
    val running =
        when (service) {
            "adb" -> {
                com.linux_core.core.terminal.ShellDaemonClient
                    .status()
                    .running
            }

            else -> {
                false
            }
        }

    val icon = if (running) "●" else "○"
    val color = if (running) Color.parseColor("#00FF41") else Color.GRAY
    button.text =
        when (service) {
            "adb" -> "📡 ADB $icon"
            else -> button.text
        }
    button.setTextColor(color)
}

/**
 * Update service indicator with pre-computed running state (main-thread safe).
 */
internal fun TerminalActivity.updateServiceIndicator(
    service: String,
    button: Button,
    running: Boolean,
) {
    val icon = if (running) "●" else "○"
    val color = if (running) Color.parseColor("#00FF41") else Color.GRAY
    button.text =
        when (service) {
            "adb" -> "📡 ADB $icon"
            else -> button.text
        }
    button.setTextColor(color)
}

/**
 * Update service detail (calls status() inline — used from button handlers).
 */
internal fun TerminalActivity.updateServiceDetail(service: String) {
    servicesDetailPanel.removeAllViews()

    val row =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
        }

    when (service) {
        "adb" -> {
            val st =
                com.linux_core.core.terminal.ShellDaemonClient
                    .status()
            val icon = if (st.running) "●" else "○"
            val color = if (st.running) Color.parseColor("#00FF41") else Color.GRAY

            row.addView(
                TextView(this).apply {
                    text = "📡 ADB SHELL DAEMON  $icon"
                    setTextColor(color)
                    textSize = 11f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                },
            )

            row.addView(
                TextView(this).apply {
                    val info = if (st.running) "  port:${st.port}" else "  stopped"
                    text = info
                    setTextColor(Color.LTGRAY)
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                },
            )

            row.addView(
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                },
            )

            // START/STOP button
            if (st.running) {
                row.addView(
                    Button(this).apply {
                        text = "⏹ STOP"
                        textSize = 9f
                        setTextColor(Color.parseColor("#FF5555"))
                        background = createRoundedDrawable(Color.parseColor("#1a1a2e"), 6f, Color.parseColor("#FF5555"), 1f)
                        setPadding(10, 4, 10, 4)
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt(),
                            )
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            stopDaemonInGuest()
                        }
                    },
                )
            } else {
                row.addView(
                    Button(this).apply {
                        text = "▶ START (guest)"
                        textSize = 9f
                        setTextColor(Color.parseColor("#00FF41"))
                        background = createRoundedDrawable(Color.parseColor("#0a1a0a"), 6f, Color.parseColor("#00FF41"), 1f)
                        setPadding(10, 4, 10, 4)
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics).toInt(),
                            )
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            startDaemonInGuest()
                        }
                    },
                )
            }
        }
    }

    servicesDetailPanel.addView(row)
}

/**
 * App UID (10323) NEMUZE spawnout proces pod uid 2000. Tlacitko START
 * proto spusti `ashell adb start` JEDNORAZOVE pres boot skript na pozadi
 * (žádná nová terminal session): boot nastartuje proot, provede příkaz
 * pod uid 2000 a hned zemře. Daemon se odpoutá (setsid + daemonize)
 * a běží dál. Indikátor se aktualizuje pollerem.
 */
internal fun TerminalActivity.startDaemonInGuest() {
    Log.i(TerminalActivity.TAG, "startDaemonInGuest: boot -- ashell adb start (bez su)")
    Thread {
        try {
            val boot = java.io.File(applicationContext.filesDir, "usr/bin/boot")
            if (!boot.exists()) {
                Log.e(TerminalActivity.TAG, "boot script not found at ${boot.absolutePath}")
                return@Thread
            }
            val pb = ProcessBuilder("sh", boot.absolutePath, "--", "ashell", "adb", "start")
            pb.directory(applicationContext.filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val completed = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) proc.destroyForcibly()
            Log.i(TerminalActivity.TAG, "startDaemonInGuest: $out")
        } catch (e: Exception) {
            Log.e(TerminalActivity.TAG, "startDaemonInGuest failed: ${e.message}")
        }
        runOnUiThread {
            servicesUpdateHandler.removeCallbacks(servicesPoller)
            servicesUpdateHandler.post(servicesPoller)
        }
    }.start()
}

/**
 * STOP: jednorazove `ashell adb stop` pres boot skript na pozadi
 * (pkill daemona pod uid 2000). Zadna nova session.
 */
internal fun TerminalActivity.stopDaemonInGuest() {
    Log.i(TerminalActivity.TAG, "stopDaemonInGuest: boot -- ashell adb stop (bez su)")
    Thread {
        try {
            val boot = java.io.File(applicationContext.filesDir, "usr/bin/boot")
            if (!boot.exists()) {
                Log.e(TerminalActivity.TAG, "boot script not found at ${boot.absolutePath}")
                return@Thread
            }
            val pb = ProcessBuilder("sh", boot.absolutePath, "--", "ashell", "adb", "stop")
            pb.directory(applicationContext.filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val completed = proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) proc.destroyForcibly()
            Log.i(TerminalActivity.TAG, "stopDaemonInGuest: $out")
        } catch (e: Exception) {
            Log.e(TerminalActivity.TAG, "stopDaemonInGuest failed: ${e.message}")
        }
        runOnUiThread {
            servicesUpdateHandler.removeCallbacks(servicesPoller)
            servicesUpdateHandler.post(servicesPoller)
        }
    }.start()
}

internal fun TerminalActivity.startDaemonAsync(callback: ((Boolean) -> Unit)? = null) {
    Thread {
        val ok =
            com.linux_core.core.terminal.ShellDaemonClient
                .startDaemon(applicationContext)
        runOnUiThread {
            callback?.invoke(ok)
            updateAllServiceIndicators()
            if (!ok) {
                android.widget.Toast
                    .makeText(
                        this@startDaemonAsync,
                        "App neumí spustit uid 2000 — v guestu: ashell adb start",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }.start()
}

internal fun TerminalActivity.startAllServices() {
    Thread {
        com.linux_core.core.terminal.ShellDaemonClient
            .startDaemon(applicationContext)
        runOnUiThread { updateAllServiceIndicators() }
    }.start()
}
