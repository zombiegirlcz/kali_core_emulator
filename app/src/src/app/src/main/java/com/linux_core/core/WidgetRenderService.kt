package com.linux_core.core

import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import com.linux_core.R
import com.termux.terminal.TerminalBuffer

class WidgetRenderService : Service() {

    companion object {
        const val TAG = "WidgetRenderService"

        const val ACTION_START = "com.linux_core.WIDGET_RENDER_START"

        const val ACTION_ROTATE = "com.linux_core.WIDGET_ROTATE"
        const val EXTRA_DIRECTION = "direction"

        const val ACTION_REFRESH = "com.linux_core.WIDGET_REFRESH"

        const val ACTION_STOP = "com.linux_core.WIDGET_RENDER_STOP"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var currentFaceIndex = 0
    private val vpnHistory = mutableListOf<Long>()
    private var previousBytes: Long = -1L
    private val logBuffer = ArrayDeque<WidgetPageRenderer.LogEntry>(50)
    private var isAnimating = false
    private var renderRunnable: Runnable? = null
    private var previousBitmap: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("WidgetRenderThread").apply { start() }
        workerHandler = Handler(workerThread!!.looper)
        startPeriodicRendering()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ROTATE -> {
                val direction = intent.getIntExtra(EXTRA_DIRECTION, 0)
                if (direction != 0) startCubeRotation(direction)
            }
            ACTION_REFRESH -> renderAndUpdateWidget()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopPeriodicRendering()
        workerThread?.quitSafely()
        super.onDestroy()
    }

    private fun startPeriodicRendering() {
        renderRunnable = object : Runnable {
            override fun run() {
                if (!isAnimating) {
                    collectData()
                    renderAndUpdateWidget()
                }
                handler.postDelayed(this, 15000)
            }
        }
        handler.post(renderRunnable!!)
    }

    private fun stopPeriodicRendering() {
        renderRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun collectData() {
        if (VpnCaptureService.isRunning()) {
            val bytes = VpnCaptureService.getCapturedByteCount()
            if (previousBytes >= 0) {
                val delta = bytes - previousBytes
                if (delta >= 0) vpnHistory.add(delta)
            }
            previousBytes = bytes
            if (vpnHistory.size > 20) vpnHistory.removeFirst()
        }

        val session = TerminalService.sessions.firstOrNull()
        if (session != null) {
            try {
                val emulator = session.emulator
                val screenBuffer: TerminalBuffer = emulator.screen
                val visibleRows = emulator.mRows
                val columns = emulator.mColumns
                val startRow = maxOf(0, visibleRows - 5)
                val text = screenBuffer.getSelectedText(0, startRow, columns, visibleRows)
                text.split("\n")
                    .map { it.trimEnd() }
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        logBuffer.addLast(WidgetPageRenderer.LogEntry(
                            timestamp = System.currentTimeMillis(),
                            line = line,
                            isError = line.contains("error", ignoreCase = true) ||
                                      line.contains("fail", ignoreCase = true) ||
                                      line.contains("permission denied", ignoreCase = true)
                        ))
                    }
                while (logBuffer.size > 50) logBuffer.removeFirst()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read terminal screen: ${e.message}")
            }
        }
    }

    private fun renderAndUpdateWidget() {
        if (isAnimating) return

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, WidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        if (appWidgetIds.isEmpty()) return

        val options = appWidgetManager.getAppWidgetOptions(appWidgetIds[0])
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200)

        workerHandler?.post {
            val density = resources.displayMetrics.density
            val bitmap = renderFace(currentFaceIndex, width, height, density)

            bitmap?.let { bmp ->
                handler.post {
                    for (appWidgetId in appWidgetIds) {
                        val views = RemoteViews(packageName, R.layout.widget_layout)
                        views.setImageViewBitmap(R.id.cube_face, bmp)
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
                    }
                    previousBitmap?.recycle()
                    previousBitmap = bmp
                }
            }
        }
    }

    private fun startCubeRotation(direction: Int) {
        if (isAnimating) return
        isAnimating = true

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, WidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        if (appWidgetIds.isEmpty()) { isAnimating = false; return }

        val options = appWidgetManager.getAppWidgetOptions(appWidgetIds[0])
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200)
        val density = resources.displayMetrics.density

        val fromIndex = currentFaceIndex
        val toIndex = ((currentFaceIndex + direction) % 4 + 4) % 4

        workerHandler?.post {
            val fromFace = renderFace(fromIndex, width, height, density)
            val toFace = renderFace(toIndex, width, height, density)

            if (fromFace == null || toFace == null) {
                handler.post { isAnimating = false }
                return@post
            }

            val frames = WidgetCubeRenderer.generateRotationFrames(
                width, height, fromFace, toFace, direction, frameCount = 10, density
            )

            handler.post(object : Runnable {
                var frameIndex = 0
                override fun run() {
                    if (frameIndex < frames.size) {
                        val frame = frames[frameIndex]
                        val views = RemoteViews(this@WidgetRenderService.packageName, R.layout.widget_layout)
                        views.setImageViewBitmap(R.id.cube_face, frame)
                        for (appWidgetId in appWidgetIds) {
                            appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
                        }
                        frameIndex++
                        handler.postDelayed(this, 40)
                    } else {
                        currentFaceIndex = toIndex
                        isAnimating = false
                        frames.forEach {
                            if (it != fromFace && it != toFace) it.recycle()
                        }
                        fromFace.recycle()
                        toFace.recycle()
                    }
                }
            })
        }
    }

    private fun renderFace(faceIndex: Int, width: Int, height: Int, density: Float): Bitmap? {
        return when (faceIndex) {
            0 -> {
                val cells = WidgetProvider.notebookCells.map {
                    WidgetPageRenderer.NotebookCell(it.name, it.command, it.lastOutput, it.lastRunTime)
                }
                WidgetPageRenderer.renderNotebookFace(width, height, cells, density)
            }
            1 -> {
                val stats = WidgetPageRenderer.VpnStats(
                    running = VpnCaptureService.isRunning(),
                    packets = VpnCaptureService.getCapturedPacketCount(),
                    bytes = VpnCaptureService.getCapturedByteCount(),
                    historyPoints = vpnHistory.toList()
                )
                WidgetPageRenderer.renderDashboardFace(width, height, stats, density)
            }
            2 -> WidgetPageRenderer.renderLogsFace(width, height, logBuffer.toList(), density)
            3 -> {
                val actions = listOf(
                    WidgetPageRenderer.QuickAction("VPN ON/OFF", "vpn_toggle"),
                    WidgetPageRenderer.QuickAction("BOOT KALI", "kali"),
                    WidgetPageRenderer.QuickAction("BOOT PARROT", "parrot"),
                    WidgetPageRenderer.QuickAction("STOP ALL", "stop_all")
                )
                WidgetPageRenderer.renderControlFace(width, height, VpnCaptureService.isRunning(), actions, density)
            }
            else -> null
        }
    }
}
