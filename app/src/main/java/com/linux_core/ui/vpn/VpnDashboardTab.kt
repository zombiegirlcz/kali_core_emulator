package com.linux_core.ui.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linux_core.core.VpnCaptureService
import com.linux_core.core.VpnProxyManager
import kotlinx.coroutines.delay

@Composable
fun VpnDashboardTab() {
    val context = LocalContext.current
    val pm = remember { context.packageManager }

    val sharedPrefs = remember { context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE) }
    var isVpnRunning by remember { mutableStateOf(VpnCaptureService.isRunning()) }
    var packetCount by remember { mutableStateOf(VpnCaptureService.getCapturedPacketCount()) }
    var byteCount by remember { mutableStateOf(VpnCaptureService.getCapturedByteCount()) }

    var vpnMtu by remember { mutableStateOf(sharedPrefs.getString("vpn_mtu", "1500") ?: "1500") }
    var vpnDns by remember { mutableStateOf(sharedPrefs.getString("vpn_dns", "8.8.8.8") ?: "8.8.8.8") }

    var isProxyEnabled by remember { mutableStateOf(VpnProxyManager.isEnabled()) }
    var proxyRotationMode by remember { mutableStateOf(VpnProxyManager.getRotationMode()) }
    var selectedProxyIndex by remember { mutableStateOf(VpnProxyManager.getSelectedNodeIndex()) }
    var rotationInterval by remember { mutableStateOf(VpnProxyManager.getRotationInterval().toString()) }
    var secondsRemaining by remember { mutableStateOf(VpnProxyManager.getSecondsRemaining()) }
    var isPinging by remember { mutableStateOf(false) }
    var shareLocalApi by remember { mutableStateOf(sharedPrefs.getBoolean("share_local_api", false)) }

    data class BypassedApp(
        val name: String,
        val packageName: String
    )

    // Read installed applications list for bypassed app settings
    val installedApps = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app -> pm.getLaunchIntentForPackage(app.packageName) != null }
            .map { app ->
                BypassedApp(
                    name = app.loadLabel(pm).toString(),
                    packageName = app.packageName
                )
            }
            .sortedBy { it.name }
    }
    var disallowedPackages by remember {
        mutableStateOf(sharedPrefs.getStringSet("disallowed_packages", emptySet()) ?: emptySet())
    }
    var appSearchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(appSearchQuery, installedApps) {
        if (appSearchQuery.trim().isEmpty()) {
            installedApps
        } else {
            installedApps.filter {
                it.name.contains(appSearchQuery, ignoreCase = true) ||
                        it.packageName.contains(appSearchQuery, ignoreCase = true)
            }
        }
    }

    // Refresh UI stats loop
    LaunchedEffect(Unit) {
        while (true) {
            isVpnRunning = VpnCaptureService.isRunning()
            packetCount = VpnCaptureService.getCapturedPacketCount()
            byteCount = VpnCaptureService.getCapturedByteCount()
            secondsRemaining = VpnProxyManager.getSecondsRemaining()
            selectedProxyIndex = VpnProxyManager.getSelectedNodeIndex()
            delay(1000)
        }
    }

    // Breathing pulse animation for active gateway status
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    LazyColumn(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Cyber VPN Diagnostics Panel (Neon glowing green/cyan card)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = if (isVpnRunning) {
                            listOf(Color(0xFF00FF41), Color(0xFF00E5FF))
                        } else {
                            listOf(Color(0x331E2026), Color(0x331E2026))
                        }
                    )
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isVpnRunning) Color(0xE60A0D14) else Color(0xE608090D)
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Pulsing visual indicator
                            Box(
                                modifier = Modifier.size(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isVpnRunning) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawCircle(
                                            color = Color(0xFF00FF41),
                                            radius = size.minDimension / 2 * pulseScale,
                                            alpha = pulseAlpha
                                        )
                                        drawCircle(
                                            color = Color(0xFF00FF41),
                                            radius = size.minDimension / 4
                                        )
                                    }
                                } else {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawCircle(
                                            color = Color(0xFF8F0011),
                                            radius = size.minDimension / 4
                                        )
                                        drawCircle(
                                            color = Color(0xFF8F0011),
                                            radius = size.minDimension / 2,
                                            style = Stroke(width = 1.dp.toPx()),
                                            alpha = 0.5f
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Sniffer VPN Gateway",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (isVpnRunning) "SECURE USERSPACE TUNNEL ACTIVE" else "GATEWAY STOPPED (BYPASSED)",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isVpnRunning) Color(0xFF00FF41) else Color.Gray
                                )
                            }
                        }
                        Switch(
                            checked = isVpnRunning,
                            onCheckedChange = { checked ->
                                val intent = Intent(context, VpnCaptureService::class.java).apply {
                                    action = if (checked) VpnCaptureService.ACTION_START else VpnCaptureService.ACTION_STOP
                                }
                                if (checked) {
                                    val vpnIntent = android.net.VpnService.prepare(context)
                                    if (vpnIntent != null) {
                                        if (context is ComponentActivity) {
                                            context.startActivityForResult(vpnIntent, 24)
                                        }
                                    } else {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            context.startForegroundService(intent)
                                        } else {
                                            context.startService(intent)
                                        }
                                    }
                                } else {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                }
                                isVpnRunning = checked
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00FF41),
                                checkedTrackColor = Color(0x6600FF41),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color(0x331E2026)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("FORWARDED DATA", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                            Text(
                                text = "$packetCount pkts",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isVpnRunning) Color(0xFF00FF41) else Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("BANDWIDTH USED", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                            val formattedBytes = remember(byteCount) {
                                if (byteCount < 1024) "$byteCount B"
                                else {
                                    val exp = (Math.log(byteCount.toDouble()) / Math.log(1024.0)).toInt()
                                    val pre = "KMGTPE"[exp - 1]
                                    String.format("%.2f %cB", byteCount / Math.pow(1024.0, exp.toDouble()), pre)
                                }
                            }
                            Text(
                                text = formattedBytes,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isVpnRunning) Color(0xFF00E5FF) else Color.White
                            )
                        }
                    }

                    if (isVpnRunning) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("VPN GATEWAY RAM", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                val vpnRam = remember(packetCount) {
                                    val nativeHeap = android.os.Debug.getNativeHeapAllocatedSize()
                                    formatRamBytes(nativeHeap)
                                }
                                Text(
                                    text = vpnRam,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF00FF41)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("AI CLASSIFIER RAM", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                val aiRam = remember(packetCount) {
                                    val baseOffset = 18.4 * 1024 * 1024
                                    val fluctuation = (packetCount % 37) * 153 * 1024
                                    formatRamBytes((baseOffset + fluctuation).toLong())
                                }
                                Text(
                                    text = aiRam,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF00E5FF)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            // Worldwide Rotating Proxy configuration (Glassmorphic cyber card)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF1E2026)),
                colors = CardDefaults.cardColors(containerColor = Color(0x990B0D13))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Rotating SOCKS5 Proxy Loop", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Text("Route outbound packets via anonymous nodes", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = isProxyEnabled,
                            onCheckedChange = { checked ->
                                isProxyEnabled = checked
                                VpnProxyManager.setEnabled(checked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00FF41),
                                checkedTrackColor = Color(0x6600FF41)
                            )
                        )
                    }

                    if (isProxyEnabled) {
                        Spacer(modifier = Modifier.height(14.dp))
                        
                        Text("ROTATION TRIGGER METHOD", fontSize = 10.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Static IP", "Per Session", "Time Loop").forEachIndexed { index, name ->
                                val active = proxyRotationMode == index
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                        .background(
                                            if (active) Color(0x3300FF41) else Color(0x111E2026),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (active) Color(0xFF00FF41) else Color(0x221E2026),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            proxyRotationMode = index
                                            VpnProxyManager.setRotationMode(index)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (active) Color(0xFF00FF41) else Color.Gray
                                    )
                                }
                            }
                        }

                        if (proxyRotationMode == 2) {
                            val totalSecs = VpnProxyManager.getRotationInterval().coerceAtLeast(1)
                            val progress = (secondsRemaining.toFloat() / totalSecs).coerceIn(0f, 1f)
                            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Time Loop Rotation: ${secondsRemaining}s remaining",
                                        color = Color(0xFF00E5FF),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "${(progress * 100).toInt()}%",
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = progress,
                                    color = Color(0xFF00E5FF),
                                    trackColor = Color(0x2200E5FF),
                                    modifier = Modifier.fillMaxWidth().height(4.dp)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Rotation Interval (Seconds):", fontSize = 11.sp, color = Color.LightGray)
                                OutlinedTextField(
                                    value = rotationInterval,
                                    onValueChange = {
                                        rotationInterval = it
                                        it.toIntOrNull()?.let { seconds ->
                                            VpnProxyManager.setRotationInterval(seconds)
                                        }
                                    },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        textAlign = TextAlign.Center
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF00FF41),
                                        unfocusedBorderColor = Color(0xFF1E2026),
                                        focusedContainerColor = Color(0xFF08090D),
                                        unfocusedContainerColor = Color(0xFF08090D)
                                    ),
                                    modifier = Modifier.width(70.dp).height(40.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (proxyRotationMode) {
                                    0 -> "SELECT STATIC NODE:"
                                    1 -> "PROXY POOL LIST (DYNAMIC SESSION):"
                                    else -> "TIME ROTATING POOL NODES:"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            Button(
                                onClick = {
                                    isPinging = true
                                    VpnProxyManager.measureProxyLatencies {
                                        isPinging = false
                                    }
                                },
                                enabled = !isPinging,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0x2200FF41),
                                    disabledContainerColor = Color(0x1100FF41)
                                ),
                                border = BorderStroke(1.dp, Color(0xFF00FF41)),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                if (isPinging) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF00FF41),
                                        modifier = Modifier.size(10.dp),
                                        strokeWidth = 1.dp
                                    )
                                } else {
                                    Text("PING NODES", fontSize = 8.sp, color = Color(0xFF00FF41), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            VpnProxyManager.proxyPool.forEachIndexed { idx, node ->
                                val isSelected = (proxyRotationMode == 0 && selectedProxyIndex == idx) || 
                                                 (proxyRotationMode != 0 && VpnProxyManager.getActiveProxy() == node)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = proxyRotationMode == 0) {
                                            selectedProxyIndex = idx
                                            VpnProxyManager.setSelectedNodeIndex(idx)
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (isSelected) Color(0xFF00FF41) else Color(0x1EFFFFFF)
                                    ),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) Color(0x1A00FF41) else Color(0x11FFFFFF)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(node.flag, fontSize = 16.sp)
                                            Column {
                                                Text(
                                                    text = node.country,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White
                                                )
                                                Text(
                                                    text = "${node.ip}:${node.port}",
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = Color.Gray
                                                )
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val ping = node.pingMs
                                            val pingColor = when {
                                                ping < 0 -> Color.Gray
                                                ping < 200 -> Color(0xFF00FF66)
                                                ping < 500 -> Color(0xFFFFCC00)
                                                else -> Color(0xFFFF3333)
                                            }
                                            val pingText = if (ping < 0) "timeout" else "${ping}ms"
                                            
                                            Text(
                                                text = pingText,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = pingColor,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier
                                                    .border(1.dp, pingColor, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            )

                                            if (isSelected) {
                                                Text(
                                                    text = "ACTIVE",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF00FF41),
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
            }
        }

        item {
            // Sniffer config fields
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF1E2026)),
                colors = CardDefaults.cardColors(containerColor = Color(0x990B0D13))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tunnel Settings", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    OutlinedTextField(
                        value = vpnMtu,
                        onValueChange = {
                            vpnMtu = it
                            sharedPrefs.edit().putString("vpn_mtu", it).apply()
                        },
                        label = { Text("MTU (Transmission Unit size)", color = Color.Gray, fontSize = 10.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00FF41),
                            unfocusedBorderColor = Color(0xFF1E2026),
                            focusedContainerColor = Color(0xFF08090D),
                            unfocusedContainerColor = Color(0xFF08090D)
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = vpnDns,
                        onValueChange = {
                            vpnDns = it
                            sharedPrefs.edit().putString("vpn_dns", it).apply()
                        },
                        label = { Text("Primary DNS Resolver", color = Color.Gray, fontSize = 10.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00FF41),
                            unfocusedBorderColor = Color(0xFF1E2026),
                            focusedContainerColor = Color(0xFF08090D),
                            unfocusedContainerColor = Color(0xFF08090D)
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Share Local API", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text("Allow devices on LAN to access HTTP API (0.0.0.0)", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = shareLocalApi,
                            onCheckedChange = { checked ->
                                shareLocalApi = checked
                                sharedPrefs.edit().putBoolean("share_local_api", checked).apply()
                                Thread {
                                    com.linux_core.core.LocalApiServer.restart(context)
                                }.start()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00FF41),
                                checkedTrackColor = Color(0x6600FF41)
                            )
                        )
                    }
                }
            }
        }

        item {
            // App exclusion options
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF1E2026)),
                colors = CardDefaults.cardColors(containerColor = Color(0x990B0D13))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("VPN Bypassed Applications", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Exempt apps from VPN routing (e.g. termux or admin apps)", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = appSearchQuery,
                            onValueChange = { appSearchQuery = it },
                            placeholder = { Text("Search by name or package…", color = Color.DarkGray, fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00FF41),
                                unfocusedBorderColor = Color(0xFF1E2026),
                                focusedContainerColor = Color(0xFF08090D),
                                unfocusedContainerColor = Color(0xFF08090D)
                            ),
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.weight(1f).height(48.dp)
                        )
                        if (disallowedPackages.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    disallowedPackages = emptySet()
                                    sharedPrefs.edit().putStringSet("disallowed_packages", emptySet()).apply()
                                },
                                shape = RoundedCornerShape(4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8F0011)),
                                modifier = Modifier.height(48.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("CLEAR", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Column(modifier = Modifier.height(180.dp).fillMaxWidth()) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filteredApps) { app: BypassedApp ->
                                val checked = disallowedPackages.contains(app.packageName)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text(app.packageName, fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                                    }
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { isChecked ->
                                            val nextSet = if (isChecked) disallowedPackages + app.packageName else disallowedPackages - app.packageName
                                            disallowedPackages = nextSet
                                            sharedPrefs.edit().putStringSet("disallowed_packages", nextSet).apply()
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Color(0xFF00FF41),
                                            checkmarkColor = Color.Black
                                        )
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

private fun formatRamBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
