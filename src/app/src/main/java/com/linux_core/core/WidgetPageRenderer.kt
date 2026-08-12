package com.linux_core.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WidgetPageRenderer {

    data class NotebookCell(
        val name: String,
        val command: String,
        val lastOutput: String,
        val lastRunTime: Long
    )

    data class VpnStats(
        val running: Boolean,
        val packets: Long,
        val bytes: Long,
        val historyPoints: List<Long>
    )

    data class LogEntry(
        val timestamp: Long,
        val line: String,
        val isError: Boolean
    )

    data class QuickAction(
        val label: String,
        val type: String
    )

    private const val BG_COLOR = "#0A0A0A"
    private const val ACCENT_COLOR = "#00FF41"
    private const val CELL_BG = "#1A1A1A"
    private const val WHITE_TEXT = "#FFFFFF"
    private const val LIGHT_GRAY = "#CCCCCC"
    private const val MED_GRAY = "#666666"
    private const val DIM_GRAY = "#555555"
    private const val ERROR_RED = "#FF4444"

    fun renderNotebookFace(
        width: Int, height: Int, cells: List<NotebookCell>, density: Float
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (width < 100 || height < 100) {
            drawErrorPlaceholder(canvas, width, height, density, "Příliš malá plocha")
            return bitmap
        }

        drawBackground(canvas, width, height, density)
        drawTitle(canvas, width, density, "NOTEBOOK")

        val titleHeight = (18f * density).toInt()
        val margin = (2f * density).toInt()
        val cellPadding = (6f * density).toInt()
        val gridTop = (2f * density).toInt() + titleHeight + (4f * density).toInt()
        val gridLeft = (2f * density).toInt()
        val gridRight = width - (2f * density).toInt()
        val gridBottom = height - (2f * density).toInt()
        val cellWidth = (gridRight - gridLeft - margin) / 2
        val cellHeight = (gridBottom - gridTop - margin) / 2

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(CELL_BG) }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_COLOR)
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_COLOR)
            textSize = 12f * density
        }
        val cmdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(LIGHT_GRAY)
            textSize = 10f * density
        }
        val outputPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(MED_GRAY)
            textSize = 9f * density
        }
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_COLOR)
            textSize = 8f * density
        }
        val trianglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_COLOR)
            style = Paint.Style.FILL
        }

        for (i in cells.indices.take(4)) {
            val cell = cells[i]
            val col = i % 2
            val row = i / 2
            val cx = gridLeft + col * (cellWidth + margin)
            val cy = gridTop + row * (cellHeight + margin)
            val rect = RectF(cx.toFloat(), cy.toFloat(), (cx + cellWidth).toFloat(), (cy + cellHeight).toFloat())
            val cornerRadius = 4f * density

            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

            val nameX = cx + cellPadding
            val nameY = cy + cellPadding + (12f * density)
            canvas.drawText(cell.name.uppercase(), nameX.toFloat(), nameY, namePaint)

            val cmdX = cx + cellPadding
            val cmdY = nameY + (12f * density) + (2f * density)
            val cmdText = if (cell.command.length > 40) cell.command.take(40) + "..." else cell.command
            canvas.drawText("$ ${cmdText}", cmdX.toFloat(), cmdY, cmdPaint)

            val outputY = cy + cellHeight - cellPadding - (12f * density)
            val outputText = if (cell.lastOutput.length > 40) cell.lastOutput.take(40) + "..." else cell.lastOutput
            canvas.drawText(outputText, cmdX.toFloat(), outputY, outputPaint)

            if (cell.lastRunTime > 0) {
                val timeY = outputY + (10f * density)
                val timeStr = formatTime(cell.lastRunTime)
                canvas.drawText(timeStr, cmdX.toFloat(), timeY, timePaint)
            }

            val triangleSize = (8f * density)
            val triCx = (cx + cellWidth - cellPadding - triangleSize).toFloat()
            val triCy = outputY - (2f * density)
            val triTop = triCy - triangleSize
            val path = Path().apply {
                moveTo(triCx, triTop)
                lineTo(triCx + triangleSize, triCy - triangleSize / 2)
                lineTo(triCx, triCy)
                close()
            }
            canvas.drawPath(path, trianglePaint)
        }

        return bitmap
    }

    fun renderDashboardFace(
        width: Int, height: Int, stats: VpnStats, density: Float
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (width < 100 || height < 100) {
            drawErrorPlaceholder(canvas, width, height, density, "Příliš malá plocha")
            return bitmap
        }

        drawBackground(canvas, width, height, density)
        drawTitle(canvas, width, density, "SÍŤOVÝ PROVOZ")

        val titleHeight = (18f * density).toInt()
        val contentTop = (2f * density).toInt() + titleHeight + (4f * density).toInt()
        val contentLeft = (6f * density).toInt()
        var curY = contentTop.toFloat()

        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * density
        }
        statusPaint.color = if (stats.running) Color.parseColor(ACCENT_COLOR) else Color.parseColor(ERROR_RED)
        val statusText = if (stats.running) "● VPN AKTIVNÍ" else "● VPN NEAKTIVNÍ"
        canvas.drawText(statusText, contentLeft.toFloat(), curY + statusPaint.textSize, statusPaint)

        val statPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(WHITE_TEXT)
            textSize = 12f * density
        }
        curY += statusPaint.textSize + (6f * density)
        canvas.drawText("Packety: ${formatLargeNumber(stats.packets)}", contentLeft.toFloat(), curY, statPaint)
        curY += statPaint.textSize + (4f * density)
        val mbText = "Data: ${formatBytesToMB(stats.bytes)} MB"
        canvas.drawText(mbText, contentLeft.toFloat(), curY, statPaint)

        val graphTop = curY + (8f * density)
        val graphBottom = height - (16f * density)
        val graphLeft = (12f * density).toFloat()
        val graphRight = width - (8f * density).toFloat()
        val graphHeight = graphBottom - graphTop
        val graphWidth = graphRight - graphLeft

        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_COLOR)
            strokeWidth = 1f * density
            style = Paint.Style.STROKE
        }
        canvas.drawLine(graphLeft, graphTop, graphLeft, graphBottom, axisPaint)
        canvas.drawLine(graphLeft, graphBottom, graphRight, graphBottom, axisPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(MED_GRAY)
            textSize = 8f * density
        }
        canvas.drawText("MB", graphLeft + (2f * density), graphTop - (2f * density), labelPaint)
        canvas.drawText("čas →", graphRight - (20f * density), graphBottom + (10f * density), labelPaint)

        val history = stats.historyPoints
        if (history.isEmpty()) {
            val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(MED_GRAY)
                textSize = 10f * density
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                "Čekám na data...",
                (graphLeft + graphRight) / 2,
                (graphTop + graphBottom) / 2,
                noDataPaint
            )
        } else {
            val points = history.takeLast(20)
            val maxVal = points.maxOrNull() ?: 1L
            if (maxVal > 0) {
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(ACCENT_COLOR)
                    strokeWidth = 1f * density
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                }
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(ACCENT_COLOR)
                    style = Paint.Style.FILL
                }
                val dotRadius = 2f * density

                val stepX = if (points.size > 1) graphWidth / (points.size - 1).toFloat() else 0f

                for (i in points.indices) {
                    val px = graphLeft + i * stepX
                    val py = graphBottom - (points[i].toFloat() / maxVal.toFloat()) * graphHeight
                    canvas.drawCircle(px, py, dotRadius, dotPaint)
                    if (i > 0) {
                        val prevPx = graphLeft + (i - 1) * stepX
                        val prevPy = graphBottom - (points[i - 1].toFloat() / maxVal.toFloat()) * graphHeight
                        canvas.drawLine(prevPx, prevPy, px, py, linePaint)
                    }
                }
            }
        }

        return bitmap
    }

    fun renderLogsFace(
        width: Int, height: Int, logs: List<LogEntry>, density: Float
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (width < 100 || height < 100) {
            drawErrorPlaceholder(canvas, width, height, density, "Příliš malá plocha")
            return bitmap
        }

        drawBackground(canvas, width, height, density)
        drawTitle(canvas, width, density, "LOGY")

        val titleHeight = (18f * density).toInt()
        val contentTop = (2f * density).toInt() + titleHeight + (4f * density).toInt()
        val contentLeft = (4f * density).toInt()
        val lineHeight = (12f * density).toInt()
        val maxLines = 12

        if (logs.isEmpty()) {
            val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(MED_GRAY)
                textSize = 10f * density
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                "Zatím žádné logy...",
                (width / 2).toFloat(),
                contentTop + (height - contentTop) / 2f,
                noDataPaint
            )
            return bitmap
        }

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(DIM_GRAY)
            textSize = 8f * density
            typeface = Typeface.MONOSPACE
        }
        val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_COLOR)
            textSize = 9f * density
            typeface = Typeface.MONOSPACE
        }
        val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ERROR_RED)
            textSize = 9f * density
            typeface = Typeface.MONOSPACE
        }

        val displayLogs = logs.takeLast(maxLines)
        var y = contentTop.toFloat()

        for (entry in displayLogs) {
            val timeStr = formatTime(entry.timestamp)
            val timeWidth = timePaint.measureText(timeStr) + (4f * density)
            val maxLineWidth = width - contentLeft - timeWidth - (4f * density)

            val linePaint = if (entry.isError) errorPaint else normalPaint
            val line = if (linePaint.measureText(entry.line) > maxLineWidth) {
                truncateText(entry.line, linePaint, maxLineWidth)
            } else {
                entry.line
            }

            canvas.drawText(timeStr, contentLeft.toFloat(), y + timePaint.textSize, timePaint)
            canvas.drawText(line, contentLeft + timeWidth, y + linePaint.textSize, linePaint)

            y += lineHeight
            if (y + lineHeight > height - (4f * density)) break
        }

        return bitmap
    }

    fun renderControlFace(
        width: Int, height: Int, vpnRunning: Boolean,
        actions: List<QuickAction>, density: Float
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (width < 100 || height < 100) {
            drawErrorPlaceholder(canvas, width, height, density, "Příliš malá plocha")
            return bitmap
        }

        drawBackground(canvas, width, height, density)
        drawTitle(canvas, width, density, "OVLÁDÁNÍ")

        val titleHeight = (18f * density).toInt()
        var curY = (2f * density).toInt() + titleHeight + (8f * density).toInt()

        val indicatorRadius = (20f * density)
        val indicatorCx = (width / 2).toFloat()
        val indicatorCy = curY + indicatorRadius

        val indicatorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (vpnRunning) Color.parseColor(ACCENT_COLOR) else Color.parseColor(ERROR_RED)
            style = Paint.Style.FILL
        }
        val indicatorBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        canvas.drawCircle(indicatorCx, indicatorCy, indicatorRadius, indicatorFill)
        canvas.drawCircle(indicatorCx, indicatorCy, indicatorRadius, indicatorBorder)

        curY += (indicatorRadius * 2 + (4f * density)).toInt()
        val vpnLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("VPN", indicatorCx, curY + vpnLabelPaint.textSize, vpnLabelPaint)

        curY += (vpnLabelPaint.textSize + (8f * density)).toInt()

        val buttonHeight = (32f * density).toInt()
        val buttonPaddingH = (12f * density).toInt()
        val buttonMargin = (4f * density).toInt()
        val buttonLeft = (16f * density).toInt()
        val buttonRight = width - (16f * density).toInt()
        val buttonWidth = buttonRight - buttonLeft
        val cornerRadius = 6f * density

        val buttonBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(CELL_BG) }
        val buttonBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_COLOR)
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * density
            textAlign = Paint.Align.CENTER
        }

        for (action in actions.take(4)) {
            val buttonRect = RectF(
                buttonLeft.toFloat(),
                curY.toFloat(),
                buttonRight.toFloat(),
                (curY + buttonHeight).toFloat()
            )
            canvas.drawRoundRect(buttonRect, cornerRadius, cornerRadius, buttonBg)
            canvas.drawRoundRect(buttonRect, cornerRadius, cornerRadius, buttonBorder)

            val textY = curY + (buttonHeight + buttonTextPaint.textSize) / 2 - (2f * density)
            canvas.drawText(
                action.label,
                (buttonLeft + buttonWidth / 2).toFloat(),
                textY,
                buttonTextPaint
            )

            curY += buttonHeight + buttonMargin
            if (curY + buttonHeight > height - (4f * density)) break
        }

        return bitmap
    }

    private fun drawBackground(canvas: Canvas, width: Int, height: Int, density: Float) {
        val bgPaint = Paint().apply { color = Color.parseColor(BG_COLOR) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_COLOR)
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        canvas.drawRect(1f, 1f, (width - 1).toFloat(), (height - 1).toFloat(), borderPaint)
    }

    private fun drawTitle(canvas: Canvas, width: Int, density: Float, title: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * density
            textAlign = Paint.Align.CENTER
        }
        val y = (2f * density) + paint.textSize
        canvas.drawText(title, (width / 2).toFloat(), y, paint)
    }

    private fun drawErrorPlaceholder(canvas: Canvas, width: Int, height: Int, density: Float, message: String) {
        val bgPaint = Paint().apply { color = Color.parseColor(BG_COLOR) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ERROR_RED)
            textSize = 10f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(message, (width / 2).toFloat(), (height / 2).toFloat(), textPaint)
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        return sdf.format(Date(timestamp))
    }

    private fun formatLargeNumber(value: Long): String {
        return if (value >= 1_000_000) {
            "%.1fM".format(value / 1_000_000.0)
        } else if (value >= 1_000) {
            "%.1fK".format(value / 1_000.0)
        } else {
            value.toString()
        }
    }

    private fun formatBytesToMB(bytes: Long): String {
        return "%.1f".format(bytes / 1_048_576.0)
    }

    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var truncated = text
        while (truncated.isNotEmpty() && paint.measureText(truncated + "...") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return truncated + "..."
    }
}
