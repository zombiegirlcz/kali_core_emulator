package com.linux_core.ui.vpn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linux_core.core.VpnLogManager
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun VpnTrafficTab() {
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
        // Timeframe Selector Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("24h" to "24 Hours", "7d" to "7 Days", "30d" to "30 Days").forEach { (id, label) ->
                val active = timeframe == id
                Button(
                    onClick = { timeframe = id },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) Color(0xFF00FF41) else Color(0x331E2026),
                        contentColor = if (active) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                ) {
                    Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                    Text("TOTAL DATA", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(formatBytes(totalDl + totalUl), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                    Text("AVERAGE", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(formatBytes(avgDl + avgUl), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
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
                    Text("PEAK TRANSFER", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(formatBytes(peakCombined), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
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
            colors = CardDefaults.cardColors(containerColor = Color(0xDD07080A))
        ) {
            Column(modifier = Modifier.padding(12.dp).fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("BANDWIDTH TELEMETRY", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Interactive glowing flow analysis (Swipe/Tap line)", fontSize = 9.sp, color = Color.Gray)
                    }

                    // Interactive Info Overlay
                    val activeIdx = selectedIndex.coerceIn(0, count - 1)
                    val activeLabel = getPointLabel(activeIdx)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "$activeLabel: ↓ ${formatBytes(dl[activeIdx])} | ↑ ${formatBytes(ul[activeIdx])}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FF41)
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
                                color = Color(0x1E00FF41),
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1f
                            )
                        }

                        if (count > 1) {
                            val stepX = size.width / (count - 1).toFloat()

                            // Helper for rendering path
                            fun drawTrafficLine(values: LongArray, color: Color) {
                                val points = ArrayList<Offset>()
                                values.forEachIndexed { idx, value ->
                                    val x = idx * stepX
                                    val yFraction = value.toFloat() / maxVal.toFloat()
                                    val y = size.height - (yFraction * size.height * 0.85f) - 5f // padding bottom
                                    points.add(Offset(x, y))
                                }
                                for (i in 0 until points.size - 1) {
                                    drawLine(
                                        color = color,
                                        start = points[i],
                                        end = points[i + 1],
                                        strokeWidth = 3f
                                    )
                                }
                                // Draw points
                                points.forEachIndexed { idx, offset ->
                                    val isSelected = idx == selectedIndex
                                    drawCircle(
                                        color = color,
                                        radius = if (isSelected) 6f else 3f,
                                        center = offset
                                    )
                                    if (isSelected) {
                                        drawCircle(
                                            color = Color.White,
                                            radius = 2.5f,
                                            center = offset
                                        )
                                    }
                                }
                            }

                            drawTrafficLine(dl, Color(0xFF00FF41)) // Green for Download
                            drawTrafficLine(ul, Color(0xFF00E5FF)) // Cyan for Upload

                            // Draw vertical touch cursor line
                            val activeIdx = selectedIndex.coerceIn(0, count - 1)
                            val cursorX = activeIdx * stepX
                            drawLine(
                                color = Color(0x66FFDD00), // cyber gold color
                                start = Offset(cursorX, 0f),
                                end = Offset(cursorX, size.height),
                                strokeWidth = 2f
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
                                .width(8.dp)
                                .height(8.dp)
                                .padding(top = 1.dp)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) { drawRect(Color(0xFF00FF41)) }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Download", fontSize = 9.sp, color = Color.LightGray)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(8.dp)
                                .height(8.dp)
                                .padding(top = 1.dp)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) { drawRect(Color(0xFF00E5FF)) }
                        }
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
                Text("CHRONOLOGICAL TRANSFER LOGS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Breakdown of historical usage profiles in the active timeframe", fontSize = 9.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // List from newest to oldest
                    items((0 until count).reversed().toList()) { idx ->
                        val isSelected = idx == selectedIndex
                        val itemDl = dl[idx]
                        val itemUl = ul[idx]
                        val totalItem = itemDl + itemUl

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedIndex = idx },
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) Color(0xFF00FF41) else Color(0x11FFFFFF)
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) Color(0xFF12151D) else Color(0xFF0C0E14)
                            )
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = getPointLabel(idx),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color(0xFF00FF41) else Color.White
                                    )
                                    Text(
                                        text = "Total: ${formatBytes(totalItem)}",
                                        fontSize = 10.sp,
                                        color = Color.LightGray,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("↓ ${formatBytes(itemDl)}", fontSize = 9.sp, color = Color(0xFF00FF41))
                                    Text("↑ ${formatBytes(itemUl)}", fontSize = 9.sp, color = Color(0xFF00E5FF))
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Horizontal double progress bar
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        // Background Track
                                        drawRoundRect(
                                            color = Color(0x331E2026),
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
    }
}
