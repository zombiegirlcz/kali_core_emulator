package com.linux_core.ui.vpn

import android.content.ClipData
import android.content.ClipboardManager
import com.linux_core.BuildConfig
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.linux_core.core.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// Helper function to resolve app icons
fun getAppIconPainter(context: Context, packageName: String?): Painter {
    if (packageName.isNullOrEmpty()) {
        return ColorPainter(Color(0xFF2C3E50)) // Fallback dark slate color
    }
    return try {
        val pm = context.packageManager
        val drawable = pm.getApplicationIcon(packageName)
        val bitmap = drawable.toBitmap(width = 96, height = 96).asImageBitmap()
        BitmapPainter(bitmap)
    } catch (e: Exception) {
        ColorPainter(Color(0xFF2C3E50))
    }
}

// Format bytes helper
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.2f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

// Format duration helper
fun formatDuration(startTime: Long): String {
    val diff = System.currentTimeMillis() - startTime
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    return when {
        hours > 0 -> String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60)
        minutes > 0 -> String.format("%dm %ds", minutes, seconds % 60)
        else -> String.format("%ds", seconds)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnSecurityTab() {
    val context = LocalContext.current
    var activeFilter by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    var securitySubTab by remember { mutableStateOf("AUDIT") }

    val auditLogs = remember { mutableStateOf(VpnLogManager.getLogs()) }
    val blockedIps = remember {
        mutableStateListOf<String>().apply {
            addAll(VpnFirewallManager.getBlockedIps())
        }
    }

    var showFirewallConfig by remember { mutableStateOf(false) }
    var selectedLogForDetails by remember { mutableStateOf<VpnLogManager.LogEntry?>(null) }
    var selectedMitmPort by remember { mutableStateOf<Int?>(null) }

    // Dynamic stats updated in real-time
    var totalRequests by remember { mutableStateOf(0L) }
    var blockedAds by remember { mutableStateOf(0L) }
    var blockedTrackers by remember { mutableStateOf(0L) }
    var bytesSaved by remember { mutableStateOf(0L) }
    var bytesUploaded by remember { mutableStateOf(0L) }
    var bytesDownloaded by remember { mutableStateOf(0L) }
    var topAppsList by remember { mutableStateOf(emptyList<Triple<String, String?, Int>>()) }

    var mitmEnabled by remember { mutableStateOf(
        context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
            .getBoolean("enable_mitm", BuildConfig.ENABLE_MITM)
    ) }

    // SOCKETS + MITM tab state (must be at composable level, not inside LazyColumn)
    val activeSockets = remember { mutableStateListOf<com.linux_core.core.ActiveSocket>() }
    var mitmSnippets by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
    val mitmSessionInfos = remember { mutableStateListOf<TlsMitmEngine.MitmSessionInfo>() }

    // Periodic logger & stats updater
    LaunchedEffect(Unit) {
        while (true) {
            auditLogs.value = VpnLogManager.getLogs()
            totalRequests = VpnLogManager.getTotalRequests()
            blockedAds = VpnLogManager.getTotalBlockedAds()
            blockedTrackers = VpnLogManager.getTotalBlockedTrackers()
            bytesSaved = VpnLogManager.getTotalBytesSaved()
            bytesUploaded = VpnLogManager.getTotalBytesUploaded()
            bytesDownloaded = VpnLogManager.getTotalBytesDownloaded()
            topAppsList = VpnLogManager.getTopApps()
            mitmEnabled = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
                .getBoolean("enable_mitm", BuildConfig.ENABLE_MITM)
            // Refresh SOCKETS + MITM data
            if (VpnCaptureService.isRunning()) {
                val list = VpnCaptureService.getActiveSockets(context)
                activeSockets.clear()
                activeSockets.addAll(list)
                val ports = TlsMitmEngine.getActiveSessionPorts()
                mitmSnippets = TlsMitmEngine.getSessionSnapshots()
                val infos = ports.mapNotNull { TlsMitmEngine.getSessionInfo(it) }
                mitmSessionInfos.clear()
                mitmSessionInfos.addAll(infos)
            } else {
                activeSockets.clear()
            }
            delay(1000)
        }
    }

    val filteredLogs = remember(activeFilter, searchQuery, auditLogs.value) {
        auditLogs.value.filter { entry ->
            val matchesFilter = when (activeFilter) {
                "ALL" -> true
                "BLOCKED" -> entry.category == VpnLogManager.AuditCategory.BLOCKED || entry.detail.contains("block", ignoreCase = true)
                "ALLOWED" -> entry.category == VpnLogManager.AuditCategory.ALLOWED
                "SUSPICIOUS" -> entry.category == VpnLogManager.AuditCategory.SUSPICIOUS || entry.category == VpnLogManager.AuditCategory.CRITICAL
                else -> entry.category.name == activeFilter
            }
            val matchesSearch = if (searchQuery.isEmpty()) true else {
                entry.dstIp.contains(searchQuery) ||
                entry.srcIp.contains(searchQuery) ||
                entry.detail.contains(searchQuery, ignoreCase = true) ||
                entry.appName.contains(searchQuery, ignoreCase = true) ||
                (entry.sessionName?.contains(searchQuery, ignoreCase = true) ?: false)
            }
            matchesFilter && matchesSearch
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0E14))
    ) {
        // Header with Export and Firewall toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "AUDIT & PROTECTION",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Live traffic analytics & adblock metrics",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = { showFirewallConfig = !showFirewallConfig },
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (showFirewallConfig) Color(0x33FF3333) else Color(0x1EFFFFFF),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            1.dp,
                            if (showFirewallConfig) Color(0xFFFF3333) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Firewall",
                        tint = if (showFirewallConfig) Color(0xFFFF3333) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Button(
                    onClick = {
                        val path = VpnLogManager.exportLogsToDownloads(context)
                        if (path != null) {
                            Toast.makeText(context, "Logs exported successfully!", Toast.LENGTH_LONG).show()
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x1A2CC47B)),
                    border = BorderStroke(1.dp, Color(0xFF2CC47B)),
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF2CC47B), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("EXPORT CSV", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2CC47B))
                }
            }
        }

        if (showFirewallConfig) {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                FirewallController(blockedIps)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // 1. Statistics Cards Section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Left Card: Requests Summary
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Analytics, contentDescription = null, tint = Color(0xFF2CC47B), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("POŽADAVKY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("${blockedAds + blockedTrackers}", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color(0xFFFF5252))
                                    Text("blokováno", fontSize = 9.sp, color = Color.Gray)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("$totalRequests", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White)
                                    Text("celkem", fontSize = 9.sp, color = Color.Gray)
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))

                            // Ratio Progress Bar
                            val ratio = if (totalRequests > 0) (blockedAds + blockedTrackers).toFloat() / totalRequests else 0f
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x22FFFFFF))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(ratio.coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .background(Color(0xFFFF5252))
                                )
                            }
                        }
                    }

                    // Right Card: Data Usage
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFF2CC47B), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("VYUŽITÍ DAT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            Column {
                                Text(formatBytes(bytesSaved), fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color(0xFF2CC47B))
                                Text("ušetřeno", fontSize = 9.sp, color = Color.Gray)
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            // Upload vs Download info
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("▲ ${formatBytes(bytesUploaded)}", fontSize = 9.sp, color = Color.LightGray)
                                Text("▼ ${formatBytes(bytesDownloaded)}", fontSize = 9.sp, color = Color.LightGray)
                            }
                        }
                    }
                }
            }

            // 2. Top Apps Tracker List
            if (topAppsList.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("AKTIVNÍ APLIKACE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Spacer(modifier = Modifier.height(8.dp))
                            topAppsList.forEach { (appName, pkgName, count) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Image(
                                            painter = getAppIconPainter(context, pkgName),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = appName,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "$count požadavků",
                                        color = Color(0xFF2CC47B),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Security Sub-tab Bar
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            "AUDIT" to "Audit",
                            "SOCKETS" to "Sockets",
                            "MITM" to "TLS MITM"
                        ).forEach { (tabId, label) ->
                            val active = securitySubTab == tabId
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .background(
                                        if (active) Color(0x332CC47B) else Color(0x0AFFFFFF),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (active) Color(0xFF2CC47B) else Color(0x11FFFFFF),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { securitySubTab = tabId },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (active) Color(0xFF2CC47B) else Color.Gray
                                )
                            }
                        }
                    }
                }
            }

            // 4. AUDIT Sub-tab
            if (securitySubTab == "AUDIT") {
                // Search and Filter
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Hledat...", color = Color.DarkGray, fontSize = 13.sp) },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF2CC47B),
                                    unfocusedBorderColor = Color(0xFF2C3E50),
                                    focusedContainerColor = Color(0xFF0C0E14),
                                    unfocusedContainerColor = Color(0xFF0C0E14)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp)) }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf("ALL" to "Vše", "ALLOWED" to "Povoleno", "SUSPICIOUS" to "Slídiči", "BLOCKED" to "Blokováno").forEach { (filterId, label) ->
                                    val active = activeFilter == filterId
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(28.dp)
                                            .background(
                                                if (active) Color(0x222CC47B) else Color(0x0AFFFFFF),
                                                RoundedCornerShape(6.dp)
                                            )
                                            .border(
                                                1.dp,
                                                if (active) Color(0xFF2CC47B) else Color(0x11FFFFFF),
                                                RoundedCornerShape(6.dp)
                                            )
                                            .clickable { activeFilter = filterId },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (active) Color(0xFF2CC47B) else Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (filteredLogs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Žádné záznamy neodpovídají filtrům.", color = Color.DarkGray, fontSize = 13.sp)
                        }
                    }
                } else {
                    items(filteredLogs) { entry ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            LogEntryRow(
                                entry = entry,
                                blockedIps = blockedIps,
                                onDetailsClick = { selectedLogForDetails = entry }
                            )
                        }
                    }
                }
            }

            // 5. SOCKETS Sub-tab
            if (securitySubTab == "SOCKETS") {
                if (activeSockets.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (com.linux_core.core.VpnCaptureService.isRunning()) "Žádná aktivní připojení." else "Spusťte VPN k monitorování aktivních soketů.",
                                color = Color.DarkGray,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    items(activeSockets) { socket ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            ActiveSocketRow(
                                socket = socket,
                                blockedIps = blockedIps,
                                onBlockToggle = {
                                    if (blockedIps.contains(socket.dstIp)) {
                                        VpnFirewallManager.unblockIp(socket.dstIp)
                                        blockedIps.remove(socket.dstIp)
                                        Toast.makeText(context, "IP odblokována", Toast.LENGTH_SHORT).show()
                                    } else {
                                        VpnFirewallManager.blockIp(socket.dstIp)
                                        blockedIps.add(socket.dstIp)
                                        Toast.makeText(context, "IP zablokována", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // 6. MITM Sub-tab
            if (securitySubTab == "MITM") {
                // MITM toggle row
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (mitmEnabled) Color(0xFFFFD740) else Color(0x11FFFFFF)),
                        colors = CardDefaults.cardColors(containerColor = if (mitmEnabled) Color(0x1A1A12) else Color(0xFF131722))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "TLS MITM INSPECTION",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (mitmEnabled) Color(0xFFFFD740) else Color.Gray,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = if (mitmEnabled) "Aktivní • ${mitmSessionInfos.size} sessions" else "Vypnuto",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                            Switch(
                                checked = mitmEnabled,
                                onCheckedChange = { enabled ->
                                    mitmEnabled = enabled
                                    val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
                                    prefs.edit().putBoolean("enable_mitm", enabled).apply()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFFFFD740),
                                    checkedTrackColor = Color(0xFFFFD740).copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                }

                // CA certificate export
                if (mitmEnabled) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFFFFD740), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Root CA certifikát", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("Exportujte pro instalaci do trust store zařízení", fontSize = 10.sp, color = Color.Gray)
                                }
                                Button(
                                    onClick = {
                                        val cert = try {
                                            val inputStream = context.assets.open("certs/mitm-ca.crt")
                                            inputStream.bufferedReader().use { it.readText() }
                                        } catch (e: Exception) { null }
                                        if (cert != null) {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("MITM CA", cert))
                                            Toast.makeText(context, "CA certifikát zkopírován do schránky", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "CA certifikát nebyl nalezen", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x1A1A12)),
                                    border = BorderStroke(1.dp, Color(0xFFFFD740)),
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFFFFD740), modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("KOPÍROVAT CA", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD740))
                                }
                            }
                        }
                    }
                }

                // Session list
                if (mitmSessionInfos.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (mitmEnabled) "Žádné aktivní MITM sessions. Navštivte HTTPS stránku pro zahájení." else "Zapněte TLS MITM Inspection pro dešifrování provozu.",
                                color = Color.DarkGray,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(mitmSessionInfos) { session ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            MitmSessionRow(session, onViewTraffic = { selectedMitmPort = it.clientPort })
                        }
                    }
                }

                // MITM traffic history (snippets when no specific port selected, or always as fallback)
                if (mitmSnippets.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFFFD740)),
                            colors = CardDefaults.cardColors(containerColor = Color(0x1A1A12)
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.size(8.dp).background(Color(0xFFFFD740), RoundedCornerShape(2.dp)))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("LIVE DECRYPTED TLS TRAFFIC", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD740), fontFamily = FontFamily.Monospace)
                                    }
                                    Text("MITM • ${mitmSessionInfos.size} active sessions", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                val records = selectedMitmPort?.let { port ->
                                    TlsMitmEngine.getTrafficRecords(port, limit = 25)
                                } ?: emptyList()

                                if (records.isNotEmpty() && selectedMitmPort != null) {
                                    records.forEach { record ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "[${record.direction}] ${record.method ?: "?"} ${record.host ?: ""}${record.path ?: ""}",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (record.direction == "REQUEST") Color(0xFF2CC47B) else Color(0xFF00E5FF),
                                                fontFamily = FontFamily.Monospace
                                            )
                                            if (record.status != null) {
                                                Text("Status: ${record.status}", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                                            }
                                            record.headers.forEach { (k, v) ->
                                                Text("$k: $v", fontSize = 9.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                                            }
                                            if (record.body.isNotBlank()) {
                                                Text(record.body.take(200), fontSize = 9.sp, color = Color.DarkGray, fontFamily = FontFamily.Monospace)
                                            }
                                            HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = Color(0x22FFFFFF))
                                        }
                                    }
                                } else {
                                    mitmSnippets.forEach { (port, snippet) ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                        ) {
                                            Text("Port $port", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                                            Text(
                                                text = snippet,
                                                fontSize = 10.sp,
                                                color = Color(0xFFE0E0E0),
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                                            )
                                            HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = Color(0x22FFFFFF))
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

    // Request Details Dialog / Bottom Sheet
    selectedLogForDetails?.let { log ->
        RequestDetailsDialog(
            entry = log,
            blockedIps = blockedIps,
            onDismiss = { selectedLogForDetails = null }
        )
    }

    // MITM Traffic Dialog
    selectedMitmPort?.let { port ->
        MitmTrafficDialog(
            port = port,
            onDismiss = { selectedMitmPort = null }
        )
    }
}

@Composable
fun LogEntryRow(
    entry: VpnLogManager.LogEntry,
    blockedIps: SnapshotStateList<String>,
    onDetailsClick: () -> Unit
) {
    val context = LocalContext.current
    val isBlocked = entry.category == VpnLogManager.AuditCategory.BLOCKED || blockedIps.contains(entry.dstIp)

    var ipInfo by remember(entry.dstIp) { mutableStateOf<IpInfo?>(null) }
    LaunchedEffect(entry.dstIp) {
        IpInfoResolver.resolve(entry.dstIp) { info ->
            ipInfo = info
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDetailsClick() },
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isBlocked) Color(0x33FF5252) else Color(0x0AFFFFFF)),
        colors = CardDefaults.cardColors(containerColor = if (isBlocked) Color(0xFF2C1616) else Color(0xFF131722))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Top row: App icon + name + package
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = getAppIconPainter(context, entry.packageName),
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.appName.ifEmpty { "System" },
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (entry.sessionName != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "(${entry.sessionName})",
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (!entry.packageName.isNullOrEmpty()) {
                        Text(
                            text = entry.packageName,
                            color = Color.DarkGray,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
                Text(text = timeStr, color = Color.DarkGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Connection details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val flag = ipInfo?.flagEmoji ?: "🌐"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$flag ${entry.dstIp}:${entry.dstPort}",
                            color = if (isBlocked) Color(0xFFFF5252) else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (ipInfo?.countryName != null || ipInfo?.cityName != null) {
                        val location = listOfNotNull(ipInfo?.cityName, ipInfo?.countryName).joinToString(", ")
                        Text(
                            text = location,
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    val categoryColor = when (entry.category) {
                        VpnLogManager.AuditCategory.ALLOWED -> Color(0xFF2CC47B)
                        VpnLogManager.AuditCategory.BLOCKED -> Color(0xFFFF5252)
                        VpnLogManager.AuditCategory.SUSPICIOUS -> Color(0xFFFFD740)
                        VpnLogManager.AuditCategory.CRITICAL -> Color(0xFFFF3333)
                        VpnLogManager.AuditCategory.VERBOSE -> Color(0xFF4488FF)
                    }
                    Box(
                        modifier = Modifier
                            .background(categoryColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = entry.category.name,
                            color = categoryColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${entry.protocol} • ${entry.srcIp}:${entry.srcPort}",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("▲ ${formatBytes(entry.bytesSent)}", fontSize = 9.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    Text("▼ ${formatBytes(entry.bytesReceived)}", fontSize = 9.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    Text("${entry.elapsedTimeMs}ms", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                }
            }

            if (entry.entropy > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Entropy: ${"%.2f".format(entry.entropy)} bits/byte",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (entry.detail.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.detail,
                    color = Color.DarkGray,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDetailsClick) {
                    Text("DETAILS", fontSize = 9.sp, color = Color(0xFF2CC47B))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (isBlocked) {
                            VpnFirewallManager.unblockIp(entry.dstIp)
                            blockedIps.remove(entry.dstIp)
                            Toast.makeText(context, "IP odblokována", Toast.LENGTH_SHORT).show()
                        } else {
                            VpnFirewallManager.blockIp(entry.dstIp)
                            blockedIps.add(entry.dstIp)
                            Toast.makeText(context, "IP zablokována", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBlocked) Color(0xFF2CC47B) else Color(0xFFFF5252)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = if (isBlocked) "ALLOW" else "BLOCK",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun ActiveSocketRow(
    socket: com.linux_core.core.ActiveSocket,
    blockedIps: SnapshotStateList<String>,
    onBlockToggle: () -> Unit
) {
    val context = LocalContext.current
    val isBlocked = blockedIps.contains(socket.dstIp)

    var ipInfo by remember(socket.dstIp) { mutableStateOf<IpInfo?>(null) }
    LaunchedEffect(socket.dstIp) {
        IpInfoResolver.resolve(socket.dstIp) { info ->
            ipInfo = info
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isBlocked) Color(0x33FF5252) else Color(0x0AFFFFFF)),
        colors = CardDefaults.cardColors(containerColor = if (isBlocked) Color(0xFF2C1616) else Color(0xFF131722))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Top: App + state
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = getAppIconPainter(context, socket.packageName),
                    contentDescription = null,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = socket.appName.ifEmpty { "System" },
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (socket.packageName != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = socket.packageName,
                                color = Color.DarkGray,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Text(
                        text = "${socket.protocol} • ${socket.state}",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .background(
                            if (socket.isTlsMitm) Color(0x33FFD740) else Color(0x11FFFFFF),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (socket.isTlsMitm) "TLS MITM" else socket.protocol,
                        color = if (socket.isTlsMitm) Color(0xFFFFD740) else Color.Gray,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Connection endpoints
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SRC", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("${socket.srcIp}:${socket.srcPort}", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("DST", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${socket.flagEmoji} ${socket.dstIp}:${socket.dstPort}",
                            color = if (isBlocked) Color(0xFFFF5252) else Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (ipInfo?.countryName != null) {
                        val location = listOfNotNull(ipInfo?.cityName, ipInfo?.countryName).joinToString(", ")
                        Text(location, color = Color.Gray, fontSize = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }

            if (socket.isTlsMitm && socket.sni != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SNI: ${socket.sni}",
                    color = Color(0xFFFFD740),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Speed and totals
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Upload", color = Color.Gray, fontSize = 9.sp)
                    Text("▲ ${formatBytes(socket.speedUpload)}/s", color = Color(0xFF2CC47B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Celkem: ▲ ${formatBytes(socket.bytesSent)}", color = Color.DarkGray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Download", color = Color.Gray, fontSize = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("▼ ${formatBytes(socket.speedDownload)}/s", color = Color(0xFF00E5FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Celkem: ▼ ${formatBytes(socket.bytesReceived)}", color = Color.DarkGray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Block toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onBlockToggle,
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBlocked) Color(0xFF2CC47B) else Color(0xFFFF5252)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = if (isBlocked) "ALLOW" else "BLOCK",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun MitmSessionRow(
    session: TlsMitmEngine.MitmSessionInfo,
    onViewTraffic: (TlsMitmEngine.MitmSessionInfo) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0x33FFD740)),
        colors = CardDefaults.cardColors(containerColor = Color(0x1A1A12))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (session.isActivelyDecrypting) Color(0xFFFFD740) else Color.Gray,
                                RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Port ${session.clientPort}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = session.sni ?: "Unknown SNI",
                            fontSize = 11.sp,
                            color = Color(0xFFFFD740),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Text(
                    text = if (session.isActivelyDecrypting) "DECRYPTING" else "PASSTHROUGH",
                    color = if (session.isActivelyDecrypting) Color(0xFFFFD740) else Color.Gray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // TLS Details grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    DetailRow(label = "ALPN", value = session.alpn ?: "—")
                    DetailRow(label = "Cipher", value = session.cipherSuite ?: "—")
                    DetailRow(label = "Valid", value = "${session.certNotBefore ?: "—"} → ${session.certNotAfter ?: "—"}")
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    DetailRow(label = "Subject", value = session.certSubject?.take(40) ?: "—", alignEnd = true)
                    DetailRow(label = "Issuer", value = session.certIssuer?.take(40) ?: "—", alignEnd = true)
                    DetailRow(label = "Uptime", value = formatDuration(session.startTime), alignEnd = true)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = { onViewTraffic(session) },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x1A1A12)),
                border = BorderStroke(1.dp, Color(0xFFFFD740)),
                modifier = Modifier.fillMaxWidth().height(36.dp)
            ) {
                Icon(Icons.Default.Visibility, contentDescription = null, tint = Color(0xFFFFD740), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("ZOBRAZIT DEŠIFROVANÝ PROVOZ", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD740))
            }
        }
    }
}

@Composable
fun MitmTrafficDialog(
    port: Int,
    onDismiss: () -> Unit
) {
    val records = remember(port) { mutableStateOf(TlsMitmEngine.getTrafficRecords(port, limit = 50)) }

    LaunchedEffect(port) {
        while (true) {
            records.value = TlsMitmEngine.getTrafficRecords(port, limit = 50)
            delay(1000)
        }
    }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zavřít", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF131722),
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = "TLS MITM • Port $port",
                color = Color(0xFFFFD740),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (records.value.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Žádný dešifrovaný provoz pro tento port.", color = Color.DarkGray, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxHeight().height(400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(records.value) { record ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0E14)),
                                border = BorderStroke(1.dp, Color(0x11FFFFFF))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(
                                                        if (record.direction == "REQUEST") Color(0xFF2CC47B) else Color(0xFF00E5FF),
                                                        RoundedCornerShape(2.dp)
                                                    )
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = record.direction,
                                                color = if (record.direction == "REQUEST") Color(0xFF2CC47B) else Color(0xFF00E5FF),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))
                                        Text(text = time, color = Color.DarkGray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    if (record.method != null || record.path != null) {
                                        Text(
                                            text = "${record.method ?: "?"} ${record.host ?: ""}${record.path ?: ""}",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    if (record.status != null) {
                                        Text(
                                            text = "Status: ${record.status}",
                                            color = Color.Gray,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    if (record.headers.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        record.headers.forEach { (k, v) ->
                                            Text(
                                                text = "$k: $v",
                                                color = Color.DarkGray,
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }

                                    if (record.body.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = record.body.take(500) + if (record.body.length > 500) "\n..." else "",
                                            color = Color.Gray,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun RequestDetailsDialog(
    entry: VpnLogManager.LogEntry,
    blockedIps: SnapshotStateList<String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isBlocked = entry.category == VpnLogManager.AuditCategory.BLOCKED || blockedIps.contains(entry.dstIp)

    var ipInfo by remember(entry.dstIp) { mutableStateOf<IpInfo?>(null) }
    LaunchedEffect(entry.dstIp) {
        IpInfoResolver.resolve(entry.dstIp) { info ->
            ipInfo = info
        }
    }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        confirmButton = {},
        dismissButton = {},
        containerColor = Color(0xFF131722),
        shape = RoundedCornerShape(16.dp),
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp)
            ) {
                // Header (App Name + Icon + Time)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = getAppIconPainter(context, entry.packageName),
                            contentDescription = null,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = entry.appName.ifEmpty { "System" },
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!entry.packageName.isNullOrEmpty()) {
                                Text(
                                    text = entry.packageName,
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (entry.sessionName != null) {
                                Text(
                                    text = "Session: ${entry.sessionName}",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
                    Text(text = timeStr, color = Color.Gray, fontSize = 12.sp)
                }

                Text(
                    text = "Detaily požadavku",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Divider line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0x11FFFFFF))
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Connection details
                Text("Spojení", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                DetailRow(label = "Protokol", value = entry.protocol)
                DetailRow(label = "Stav", value = if (isBlocked) "Blokováno (REFUSED)" else "Zpracováno", valueColor = if (isBlocked) Color(0xFFFF5252) else Color(0xFF2CC47B))
                DetailRow(label = "Zdroj", value = "${entry.srcIp}:${entry.srcPort}")
                DetailRowWithCopy(label = "Cíl", value = "${entry.dstIp}:${entry.dstPort}", context = context)

                val scheme = if (entry.protocol == "TCP") "https://${entry.dstIp}" else "iquic://${entry.dstIp}"
                DetailRowWithCopy(label = "URL", value = scheme, context = context)

                Spacer(modifier = Modifier.height(10.dp))

                // Metrics
                Text("Metry", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                DetailRow(label = "Velikost paketu", value = "${entry.size} B")
                DetailRow(label = "Uplynulý čas", value = "${entry.elapsedTimeMs} ms")
                DetailRow(label = "Odesláno", value = formatBytes(entry.bytesSent))
                DetailRow(label = "Přijato", value = formatBytes(entry.bytesReceived))
                if (entry.entropy > 0) {
                    DetailRow(label = "Entropie", value = "${"%.2f".format(entry.entropy)} bits/byte")
                }

                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
                DetailRow(label = "Čas spuštění", value = dateStr)
                DetailRow(label = "ID připojení", value = entry.timestamp.toString().takeLast(7))

                // Geolocation Details Card
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Geolokace & Poskytovatel (GeoIP)",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (ipInfo != null) {
                    val locationStr = listOfNotNull(ipInfo?.cityName, ipInfo?.regionName, ipInfo?.countryName).joinToString(", ")
                    DetailRow(label = "Umístění", value = "${ipInfo?.flagEmoji} $locationStr")
                    if (ipInfo?.zipCode?.isNotEmpty() == true) {
                        DetailRow(label = "PSČ", value = ipInfo?.zipCode ?: "")
                    }
                    ipInfo?.isp?.let { if (it.isNotBlank()) DetailRow(label = "ISP", value = it) }
                    ipInfo?.org?.let { if (it.isNotBlank()) DetailRow(label = "Organizace", value = it) }
                    ipInfo?.asn?.let { if (it.isNotBlank()) DetailRow(label = "ASN", value = it) }
                    if (ipInfo?.isProxy == true) {
                        DetailRow(label = "Detekováno", value = "Proxy / VPN / Tor", valueColor = Color(0xFFFF5252))
                    }
                } else {
                    Text("Načítání geolokačních dat...", color = Color.DarkGray, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Použitá pravidla",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = if (isBlocked) "Pravidlo firewallu: Blokování IP adresy" else "Žádná použitá pravidla",
                    color = if (isBlocked) Color(0xFFFF5252) else Color.LightGray,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Block/Unblock Button styled beautifully
                Button(
                    onClick = {
                        if (isBlocked) {
                            VpnFirewallManager.unblockIp(entry.dstIp)
                            blockedIps.remove(entry.dstIp)
                            Toast.makeText(context, "IP adresa odblokována", Toast.LENGTH_SHORT).show()
                        } else {
                            VpnFirewallManager.blockIp(entry.dstIp)
                            if (!blockedIps.contains(entry.dstIp)) {
                                blockedIps.add(entry.dstIp)
                            }
                            Toast.makeText(context, "IP adresa zablokována", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBlocked) Color(0xFF2CC47B) else Color(0xFFFF5252)
                    ),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text(
                        text = if (isBlocked) "Povolit připojení (Odblokovat)" else "Přidat pravidlo pro blokování",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    )
}

@Composable
fun DetailRow(label: String, value: String, valueColor: Color = Color.LightGray, alignEnd: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = if (alignEnd) Arrangement.SpaceBetween else Arrangement.Start
    ) {
        Text(text = label, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.weight(0.4f))
        Text(text = value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.6f), textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start)
    }
}

@Composable
fun DetailRowWithCopy(label: String, value: String, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.weight(0.35f))
        Row(
            modifier = Modifier.weight(0.65f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                color = Color.LightGray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Kopírovat",
                tint = Color.DarkGray,
                modifier = Modifier
                    .size(16.dp)
                    .clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(label, value)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Kopírováno do schránky", Toast.LENGTH_SHORT).show()
                    }
            )
        }
    }
}

@Composable
fun FirewallController(blockedIps: SnapshotStateList<String>) {
    val context = LocalContext.current
    var newIpToBlock by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFFFF5252)),
        colors = CardDefaults.cardColors(containerColor = Color(0x1A8F0011))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Aktivní firewall (Intrusion Prevention)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newIpToBlock,
                    onValueChange = { newIpToBlock = it },
                    placeholder = { Text("Zadejte IP adresu k blokování...", color = Color.DarkGray, fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF5252),
                        unfocusedBorderColor = Color(0xFF2C3E50),
                        focusedContainerColor = Color(0xFF0C0E14),
                        unfocusedContainerColor = Color(0xFF0C0E14)
                    ),
                    modifier = Modifier.weight(1f).height(46.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val ip = newIpToBlock.trim()
                        if (ip.isNotEmpty()) {
                            VpnFirewallManager.blockIp(ip)
                            if (!blockedIps.contains(ip)) {
                                blockedIps.add(ip)
                            }
                            newIpToBlock = ""
                            Toast.makeText(context, "IP zablokována", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8F0011)),
                    border = BorderStroke(1.dp, Color(0xFFFF5252)),
                    modifier = Modifier.height(46.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Text("BLOKOVAT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            if (blockedIps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text("Seznam blokovaných IP (klepnutím odblokujete):", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    items(blockedIps.toList()) { ip ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0x33FF5252),
                            border = BorderStroke(1.dp, Color(0x66FF5252)),
                            modifier = Modifier.clickable {
                                VpnFirewallManager.unblockIp(ip)
                                blockedIps.remove(ip)
                                Toast.makeText(context, "IP odblokována", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(ip, color = Color(0xFFFF5252), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
