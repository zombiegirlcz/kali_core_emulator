package com.linux_core.ui.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.linux_core.core.IpInfo
import com.linux_core.core.IpInfoResolver
import com.linux_core.core.VpnFirewallManager
import com.linux_core.core.VpnLogManager
import com.linux_core.core.VpnCaptureService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnSecurityTab() {
    val context = LocalContext.current
    var activeFilter by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    
    val auditLogs = remember { mutableStateOf(VpnLogManager.getLogs()) }
    val blockedIps = remember { 
        mutableStateListOf<String>().apply { 
            addAll(VpnFirewallManager.getBlockedIps()) 
        } 
    }
    
    var showFirewallConfig by remember { mutableStateOf(false) }
    var selectedLogForDetails by remember { mutableStateOf<VpnLogManager.LogEntry?>(null) }

    // Dynamic stats updated in real-time
    var totalRequests by remember { mutableStateOf(0L) }
    var blockedAds by remember { mutableStateOf(0L) }
    var blockedTrackers by remember { mutableStateOf(0L) }
    var bytesSaved by remember { mutableStateOf(0L) }
    var bytesUploaded by remember { mutableStateOf(0L) }
    var bytesDownloaded by remember { mutableStateOf(0L) }
    var topAppsList by remember { mutableStateOf(emptyList<Triple<String, String?, Int>>()) }

    var logsOrSockets by remember { mutableStateOf("LOGS") }
    val activeSockets = remember { mutableStateListOf<com.linux_core.core.ActiveSocket>() }

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
            
            // Load active sockets
            if (VpnCaptureService.isRunning()) {
                val list = VpnCaptureService.getActiveSockets(context)
                activeSockets.clear()
                activeSockets.addAll(list)
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
                        imageVector = Icons.Default.Shield,
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

        // LazyColumn containing Statistics Dashboard and logs to prevent double scrolling
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

            // 2. Top Apps Tracker List (AdGuard Style)
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

            // 3. Filter and Search Bar
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

            // 4. Logs vs Active Sockets Segmented Selector
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("LOGS" to "NEDÁVNÁ AKTIVITA (${filteredLogs.size})", "SOCKETS" to "AKTIVNÍ SPOJENÍ (${activeSockets.size})").forEach { (viewId, label) ->
                        val active = logsOrSockets == viewId
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .background(
                                    if (active) Color(0x332CC47B) else Color(0x0AFFFFFF),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    if (active) Color(0xFF2CC47B) else Color(0x11FFFFFF),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { logsOrSockets = viewId },
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

            // 5. Content list
            if (logsOrSockets == "LOGS") {
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
            } else {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Icon
            Image(
                painter = getAppIconPainter(context, entry.packageName),
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))

            // Main Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val flag = ipInfo?.flagEmoji ?: "🌐"
                    Text(
                        text = "$flag  ${entry.dstIp}",
                        color = if (isBlocked) Color(0xFFFF5252) else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                // Scheme & port
                val scheme = if (entry.protocol == "TCP") "https://${entry.dstIp}" else "iquic://${entry.dstIp}"
                Text(
                    text = scheme,
                    color = Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))

            // Status / Time Info
            Column(horizontalAlignment = Alignment.End) {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
                Text(
                    text = timeStr,
                    color = Color.DarkGray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (isBlocked) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFFF5252), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "REFUSED",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        text = entry.protocol,
                        color = Color(0xFF2CC47B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
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
                        Text(
                            text = entry.appName.ifEmpty { "System" },
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
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

                // Detail Rows
                DetailRow(label = "Stav", value = if (isBlocked) "Blokováno (REFUSED)" else "Zpracováno", valueColor = if (isBlocked) Color(0xFFFF5252) else Color(0xFF2CC47B))
                DetailRow(label = "Událost", value = "${entry.protocol} tunel")
                
                val scheme = if (entry.protocol == "TCP") "https://${entry.dstIp}" else "iquic://${entry.dstIp}"
                DetailRowWithCopy(label = "Doména", value = entry.dstIp, context = context)
                DetailRowWithCopy(label = "URL požadavku", value = scheme, context = context)
                DetailRowWithCopy(label = "Cílová adresa", value = "${entry.dstIp}:${entry.dstPort}", context = context)
                
                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
                DetailRow(label = "Čas spuštění", value = dateStr)
                DetailRow(label = "Uplynulý čas", value = "${entry.elapsedTimeMs} ms")
                DetailRow(label = "ID připojení", value = "${entry.timestamp.toString().takeLast(7)}")
                DetailRow(label = "Velikost", value = "▼ ${formatBytes(entry.bytesReceived)}   ▲ ${formatBytes(entry.bytesSent)}")

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
fun DetailRow(label: String, value: String, valueColor: Color = Color.LightGray) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.Gray, fontSize = 13.sp)
        Text(text = value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DetailRowWithCopy(label: String, value: String, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.Gray, fontSize = 13.sp, modifier = Modifier.weight(0.35f))
        Row(
            modifier = Modifier.weight(0.65f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                color = Color.LightGray,
                fontSize = 13.sp,
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
                                Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(10.dp))
                            }
                        }
                    }
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isBlocked) Color(0x33FF5252) else Color(0x0AFFFFFF)),
        colors = CardDefaults.cardColors(containerColor = if (isBlocked) Color(0xFF2C1616) else Color(0xFF131722))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Icon
            Image(
                painter = getAppIconPainter(context, socket.packageName),
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))

            // Main Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${socket.flagEmoji}  ${socket.dstIp}",
                        color = if (isBlocked) Color(0xFFFF5252) else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${socket.protocol} : ${socket.srcPort} ➔ ${socket.dstPort} (${socket.state})",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Speeds and totals
                Text(
                    text = "▲ ${formatBytes(socket.speedUpload)}/s  ▼ ${formatBytes(socket.speedDownload)}/s  (Celkem: ▲ ${formatBytes(socket.bytesSent)}  ▼ ${formatBytes(socket.bytesReceived)})",
                    color = Color(0xFF00E5FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.width(8.dp))

            // Block action button
            Button(
                onClick = onBlockToggle,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBlocked) Color(0xFF2CC47B) else Color(0xFFFF5252)
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
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
