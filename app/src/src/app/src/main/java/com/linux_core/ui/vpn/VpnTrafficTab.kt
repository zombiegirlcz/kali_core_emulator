package com.linux_core.ui.vpn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linux_core.core.VpnLogManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun VpnTrafficTab() {
    var activeChartType by remember { mutableStateOf("BANDWIDTH") } // "BANDWIDTH", "AI_TELEMETRY"
    var aiPoints by remember { mutableStateOf(VpnLogManager.getAiTelemetry()) }
    
    LaunchedEffect(activeChartType) {
        while (true) {
            if (activeChartType == "AI_TELEMETRY") {
                aiPoints = VpnLogManager.getAiTelemetry()
            }
            delay(1000)
        }
    }

    // Interactive Compose line graph representing multi-timeframe network telemetry
    var timeframe by remember { mutableStateOf("30d") } // "24h", "7d", "30d"
    val rawData = remember(timeframe) {
        when (timeframe) {
            "24h" -> VpnLogManager.getHourlyTraffic()
            "7d" -> {
                val full = VpnLogManager.getDailyTraffic()
                Pair(full.first.takeLast(7).toLongArray(), full.second.takeLast(7).toLongArray())
            }
            else -> VpnLogManager.getDailyTraffic()
        }
    }

    val dl = rawData.first
    val ul = rawData.second
    val count = dl.size

    // Touch interaction state
    var touchX by remember { mutableStateOf<Float?>(null) }
    var selectedIndex by remember(count) { mutableStateOf(count - 1) }

    // Format helper
    val formatBytes = remember {
        { bytes: Long ->
            if (bytes >= 1024L * 1024 * 1024) {
                String.format("%.2f GB", bytes / (1024f * 1024 * 1024))
            } else if (bytes >= 1024L * 1024) {
                String.format("%.2f MB", bytes / (1024f * 1024))
            } else if (bytes >= 1024L) {
                String.format("%.2f KB", bytes / 1024f)
            } else {
                "$bytes B"
            }
        }
    }

    // Timeframe labels
    val getPointLabel = remember {
        { idx: Int ->
            when (timeframe) {
                "24h" -> {
                    val hr = (System.currentTimeMillis() / (1000 * 60 * 60) - (23 - idx)) % 24
                    String.format("%02d:00", if (hr < 0) hr + 24 else hr)
                }
                "7d" -> {
                    val dayMs = 1000L * 60 * 60 * 24
                    val date = Date(System.currentTimeMillis() - (6 - idx) * dayMs)
                    SimpleDateFormat("dd.MM", Locale.getDefault()).format(date)
                }
                else -> {
                    val dayMs = 1000L * 60 * 60 * 24
                    val date = Date(System.currentTimeMillis() - (29 - idx) * dayMs)
                    SimpleDateFormat("dd.MM", Locale.getDefault()).format(date)
                }
            }
        }
    }

    // Compute aggregate stats
    val totalDl = remember(dl) { dl.sum() }
    val totalUl = remember(ul) { ul.sum() }
    val peakCombined = remember(dl, ul) {
        var max = 0L
        for (i in dl.indices) {
            max = Math.max(max, dl[i] + ul[i])
        }
        max
    }
    val avgDl = remember(dl) { if (count > 0) totalDl / count else 0L }
    val avgUl = remember(ul) { if (count > 0) totalUl / count else 0L }

    Column(modifier = Modifier.fillMaxSize()) {
        // Chart Selector Segment
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("BANDWIDTH" to "PRŮTOK (Bandwidth)", "AI_TELEMETRY" to "AI TELEMETRIE (AI Analytics)").forEach { (typeId, label) ->
                val active = activeChartType == typeId
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .background(
                            if (active) Color(0x3300FF41) else Color(0x111E2026),
                            RoundedCornerShape(6.dp)
                        )
                        .border(
                            1.dp,
                            if (active) Color(0xFF00FF41) else Color(0x22FFFFFF),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { activeChartType = typeId },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) Color(0xFF00FF41) else Color.Gray
                    )
                }
            }
        }

        if (activeChartType == "BANDWIDTH") {
            // Timeframe Selector Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("24h" to "24 Hours", "7d" to "7 Days", "30d" to "30 Days").forEach { (id, label) ->
                    val active = timeframe == id
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .background(
                                if (active) Color(0x3300FF41) else Color(0x111E2026),
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                1.dp,
                                if (active) Color(0xFF00FF41) else Color(0x221E2026),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { timeframe = id },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (active) Color(0xFF00FF41) else Color.Gray
                        )
                    }
                }
            }

            // Aggregate Stats Cards Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Total Card
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0x3300FF41)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC0C0E14))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("TOTAL BANDWIDTH", fontSize = 8.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(formatBytes(totalDl + totalUl), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                // Average Card
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0x1EFFFFFF)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC0C0E14))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("AVERAGE FLOW", fontSize = 8.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(formatBytes(avgDl + avgUl), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
                    }
                }

                // Peak Card
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0x3300E5FF)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC0C0E14))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("PEAK SPEED", fontSize = 8.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(formatBytes(peakCombined), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                    }
                }
            }

            // Glowing interactive telemetry chart card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF00FF41)),
                colors = CardDefaults.cardColors(containerColor = Color(0xE607080A))
            ) {
                Column(modifier = Modifier.padding(12.dp).fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("BANDWIDTH DIAGNOSTICS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Interactive telemetry. Touch or drag below.", fontSize = 9.sp, color = Color.Gray)
                        }

                        // Interactive Info Overlay
                        val activeIdx = selectedIndex.coerceIn(0, count - 1)
                        val activeLabel = getPointLabel(activeIdx)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "$activeLabel | ↓ ${formatBytes(dl[activeIdx])} | ↑ ${formatBytes(ul[activeIdx])}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00FF41),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(count) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val position = event.changes.firstOrNull()?.position
                                            if (position != null) {
                                                val anyPressed = event.changes.any { it.pressed }
                                                if (anyPressed) {
                                                    touchX = position.x
                                                    // Resolve closest index
                                                    val stepX = size.width / (count - 1).toFloat()
                                                    val idx = (position.x / stepX + 0.5f).toInt().coerceIn(0, count - 1)
                                                    selectedIndex = idx
                                                } else {
                                                    touchX = null
                                                }
                                            }
                                        }
                                    }
                                }
                        ) {
                            var maxVal = 1024L * 1024L // Minimum 1MB scale limit
                            dl.forEach { maxVal = Math.max(maxVal, it) }
                            ul.forEach { maxVal = Math.max(maxVal, it) }

                            // Draw background grids
                            val gridLines = 4
                            val hStep = size.height / (gridLines + 1)
                            for (i in 0..gridLines) {
                                val y = hStep * (i + 1)
                                drawLine(
                                    color = Color(0x1100FF41),
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 1f
                                )
                            }

                            if (count > 1) {
                                val stepX = size.width / (count - 1).toFloat()

                                // Render line and gradient area
                                fun drawTrafficLineWithGradient(values: LongArray, strokeColor: Color, gradientColor: Color) {
                                    val points = ArrayList<Offset>()
                                    val strokePath = Path()
                                    val fillPath = Path()

                                    values.forEachIndexed { idx, value ->
                                        val x = idx * stepX
                                        val yFraction = value.toFloat() / maxVal.toFloat()
                                        val y = size.height - (yFraction * size.height * 0.82f) - 6f // bottom padding
                                        val offset = Offset(x, y)
                                        points.add(offset)
                                        
                                        if (idx == 0) {
                                            strokePath.moveTo(x, y)
                                            fillPath.moveTo(x, y)
                                        } else {
                                            strokePath.lineTo(x, y)
                                            fillPath.lineTo(x, y)
                                        }
                                    }

                                    // Close fill path
                                    fillPath.lineTo((count - 1) * stepX, size.height)
                                    fillPath.lineTo(0f, size.height)
                                    fillPath.close()

                                    // Draw Gradient Fill
                                    drawPath(
                                        path = fillPath,
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                gradientColor.copy(alpha = 0.22f),
                                                gradientColor.copy(alpha = 0.01f)
                                            )
                                        )
                                    )

                                    // Draw Stroke Line
                                    drawPath(
                                        path = strokePath,
                                        color = strokeColor,
                                        style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                                    )

                                    // Draw circle dots
                                    points.forEachIndexed { idx, offset ->
                                        val isSelected = idx == selectedIndex
                                        drawCircle(
                                            color = strokeColor,
                                            radius = if (isSelected) 5f else 2.5f,
                                            center = offset
                                        )
                                        if (isSelected) {
                                            drawCircle(
                                                color = Color.White,
                                                radius = 2f,
                                                center = offset
                                            )
                                        }
                                    }
                                }

                                // Render Download in neon green
                                drawTrafficLineWithGradient(dl, Color(0xFF00FF41), Color(0xFF00FF41))

                                // Render Upload in neon cyan
                                drawTrafficLineWithGradient(ul, Color(0xFF00E5FF), Color(0xFF00E5FF))

                                // Draw vertical gold touch cursor line with nice glow
                                val activeIdx = selectedIndex.coerceIn(0, count - 1)
                                val cursorX = activeIdx * stepX
                                
                                // Glow backing
                                drawLine(
                                    color = Color(0x22FFDD00),
                                    start = Offset(cursorX, 0f),
                                    end = Offset(cursorX, size.height),
                                    strokeWidth = 6f
                                )
                                // Core line
                                drawLine(
                                    color = Color(0xFFFFCC00),
                                    start = Offset(cursorX, 0f),
                                    end = Offset(cursorX, size.height),
                                    strokeWidth = 1.5f
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFF00FF41), RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download", fontSize = 9.sp, color = Color.LightGray)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFF00E5FF), RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Upload", fontSize = 9.sp, color = Color.LightGray)
                        }
                    }
                }
            }

            // Chronological History Log Breakdown Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF1E2026)),
                colors = CardDefaults.cardColors(containerColor = Color(0x990B0D13))
            ) {
                Column(modifier = Modifier.padding(12.dp).fillMaxSize()) {
                    Text("HISTORICAL TRANSFER LOGS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Usage profiles in the active timeframe", fontSize = 9.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items((0 until count).reversed().toList()) { idx ->
                            val isSelected = idx == selectedIndex
                            val itemDl = dl[idx]
                            val itemUl = ul[idx]
                            val totalItem = itemDl + itemUl

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedIndex = idx },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF00FF41) else Color(0x11FFFFFF)
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Color(0xFF12151D) else Color(0xFF0C0E14)
                                )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = getPointLabel(idx),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color(0xFF00FF41) else Color.White,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "Total: ${formatBytes(totalItem)}",
                                            fontSize = 10.sp,
                                            color = Color.LightGray,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("↓ ${formatBytes(itemDl)}", fontSize = 9.sp, color = Color(0xFF00FF41), fontFamily = FontFamily.Monospace)
                                        Text("↑ ${formatBytes(itemUl)}", fontSize = 9.sp, color = Color(0xFF00E5FF), fontFamily = FontFamily.Monospace)
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Horizontal double progress bar
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                    ) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            drawRoundRect(
                                                color = Color(0x1EFFFFFF),
                                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                                            )
                                            if (totalItem > 0) {
                                                val dlFraction = itemDl.toFloat() / totalItem.toFloat()
                                                val ulFraction = itemUl.toFloat() / totalItem.toFloat()

                                                // Download (Green)
                                                drawRoundRect(
                                                    color = Color(0xFF00FF41),
                                                    size = androidx.compose.ui.geometry.Size(
                                                        size.width * dlFraction,
                                                        size.height
                                                    ),
                                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                                                )
                                                // Upload (Cyan) from the right
                                                drawRoundRect(
                                                    color = Color(0xFF00E5FF),
                                                    topLeft = Offset(size.width * dlFraction, 0f),
                                                    size = androidx.compose.ui.geometry.Size(
                                                        size.width * ulFraction,
                                                        size.height
                                                    ),
                                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // AI TELEMETRY VIEW
            val avgEntropy = remember(aiPoints) {
                if (aiPoints.isNotEmpty()) aiPoints.map { it.entropy }.average() else 0.0
            }
            val avgDelta = remember(aiPoints) {
                if (aiPoints.isNotEmpty()) aiPoints.map { it.deltaTime }.average() else 0.0
            }
            val threatCount = remember(aiPoints) {
                aiPoints.count { it.category == VpnLogManager.AuditCategory.CRITICAL || it.category == VpnLogManager.AuditCategory.SUSPICIOUS || it.category == VpnLogManager.AuditCategory.BLOCKED }
            }
            val threatRatio = remember(aiPoints, threatCount) {
                if (aiPoints.isNotEmpty()) (threatCount.toFloat() / aiPoints.size * 100).toInt() else 0
            }

            // AI Statistics Cards Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Threat Ratio Card
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF5252)),
                    colors = CardDefaults.cardColors(containerColor = Color(0x33FF5252))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("THREAT / ANOMALY RATIO", fontSize = 8.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("$threatRatio %", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF5252))
                    }
                }

                // Avg Entropy Card
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0x1EFFFFFF)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC0C0E14))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("AVERAGE ENTROPY", fontSize = 8.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(String.format("%.3f", avgEntropy), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
                    }
                }

                // Avg Delta Card
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0x3300E5FF)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC0C0E14))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("AVG DELTA TIME", fontSize = 8.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(String.format("%.3fs", avgDelta), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                    }
                }
            }

            // Scatter Plot Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF00FF41)),
                colors = CardDefaults.cardColors(containerColor = Color(0xE607080A))
            ) {
                Column(modifier = Modifier.padding(12.dp).fillMaxSize()) {
                    Text("AI INFERENCE SCATTER PLOT (ENTROPY VS SIZE)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("X: Packet Size (0-1500) | Y: Entropy (0-8)", fontSize = 8.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 6.dp))

                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            // Draw background grids (X & Y)
                            val gridLines = 4
                            val hStep = size.height / (gridLines + 1)
                            val wStep = size.width / (gridLines + 1)
                            for (i in 0..gridLines) {
                                val y = hStep * (i + 1)
                                drawLine(
                                    color = Color(0x1100FF41),
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 1f
                                )
                                val x = wStep * (i + 1)
                                drawLine(
                                    color = Color(0x1100FF41),
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 1f
                                )
                            }

                            // Plot points
                            for (pt in aiPoints) {
                                val xFraction = pt.size.toFloat() / 1500f
                                val x = (xFraction * size.width).coerceIn(6f, size.width - 6f)

                                val yFraction = pt.entropy.toFloat() / 8f
                                val y = (size.height - (yFraction * size.height)).coerceIn(6f, size.height - 6f)

                                val color = when (pt.category) {
                                    VpnLogManager.AuditCategory.CRITICAL -> Color(0xFFFF3333)
                                    VpnLogManager.AuditCategory.SUSPICIOUS -> Color(0xFFFF9900)
                                    VpnLogManager.AuditCategory.BLOCKED -> Color(0xFFFF5252)
                                    else -> Color(0xFF00FF41)
                                }

                                drawCircle(
                                    color = color,
                                    radius = 4.5f,
                                    center = Offset(x, y)
                                )
                                drawCircle(
                                    color = color.copy(alpha = 0.25f),
                                    radius = 9f,
                                    center = Offset(x, y)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).background(Color(0xFF00FF41), CircleShape))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Normal/Allowed", fontSize = 8.sp, color = Color.Gray)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).background(Color(0xFFFF9900), CircleShape))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Suspicious", fontSize = 8.sp, color = Color.Gray)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).background(Color(0xFFFF3333), CircleShape))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Critical Anomaly", fontSize = 8.sp, color = Color.Gray)
                        }
                    }
                }
            }

            // AI Flow analysis log card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF1E2026)),
                colors = CardDefaults.cardColors(containerColor = Color(0x990B0D13))
            ) {
                Column(modifier = Modifier.padding(12.dp).fillMaxSize()) {
                    Text("AI ANALYZED FLOW HISTOGRAM", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Detailed entropy & delta metrics evaluated by neural engine", fontSize = 8.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(aiPoints.reversed()) { pt ->
                            val color = when (pt.category) {
                                VpnLogManager.AuditCategory.CRITICAL -> Color(0xFFFF3333)
                                VpnLogManager.AuditCategory.SUSPICIOUS -> Color(0xFFFF9900)
                                VpnLogManager.AuditCategory.BLOCKED -> Color(0xFFFF5252)
                                else -> Color(0xFF00FF41)
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, color.copy(alpha = 0.2f)),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0E14))
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Flow packet size: ${pt.size} B",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Entropy: ${String.format("%.3f", pt.entropy)} | Delta: ${String.format("%.3fs", pt.deltaTime)}",
                                            fontSize = 9.sp,
                                            color = Color.Gray,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                            .border(1.dp, color, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = pt.category.name,
                                            fontSize = 8.sp,
                                            color = color,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
