package com.linux_core.ui.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

    LazyColumn(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Cyber VPN Diagnostics Panel
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF00FF41)),
                colors = CardDefaults.cardColors(containerColor = Color(0xDD0C0E14))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Sniffer VPN Service", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                text = if (isVpnRunning) "● active gateway online" else "○ gateway offline",
                                fontSize = 11.sp,
                                color = if (isVpnRunning) Color(0xFF00FF66) else Color.Gray
                            )
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
                                        context.startService(intent)
                                    }
                                } else {
                                    context.startService(intent)
                                }
                                isVpnRunning = checked
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00FF41),
                                checkedTrackColor = Color(0x8800FF41)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Packets forwarded", fontSize = 10.sp, color = Color.Gray)
                            Text("$packetCount pkts", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00FF41))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Total bandwidth", fontSize = 10.sp, color = Color.Gray)
                            val mb = byteCount / (1024f * 1024f)
                            Text(String.format("%.2f MB", mb), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00FF41))
                        }
                    }
                }
            }
        }

        item {
            // Worldwide Rotating Proxy configuration
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF1E2026)),
                colors = CardDefaults.cardColors(containerColor = Color(0xBB0B0D13))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Worldwide Rotating Proxy", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Route system packets through SOCKS5 tunnel nodes", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = isProxyEnabled,
                            onCheckedChange = { checked ->
                                isProxyEnabled = checked
                                VpnProxyManager.setEnabled(checked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00FF41),
                                checkedTrackColor = Color(0x8800FF41)
                            )
                        )
                    }

                    if (isProxyEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text("Proxy Rotation Mode", fontSize = 11.sp, color = Color.LightGray)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("Static Node", "Random Sess.", "Time Loop").forEachIndexed { index, name ->
                                val active = proxyRotationMode == index
                                Button(
                                    onClick = {
                                        proxyRotationMode = index
                                        VpnProxyManager.setRotationMode(index)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (active) Color(0xFF008F11) else Color(0x331E2026)
                                    ),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.weight(1f).height(30.dp)
                                ) {
                                    Text(name, fontSize = 9.sp, color = Color.White)
                                }
                            }
                        }

                        if (proxyRotationMode == 2) {
                            val totalSecs = VpnProxyManager.getRotationInterval().coerceAtLeast(1)
                            val progress = (secondsRemaining.toFloat() / totalSecs).coerceIn(0f, 1f)
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Auto Rotating in: ${secondsRemaining}s",
                                        color = Color(0xFF00FF41),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "${(progress * 100).toInt()}%",
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = progress,
                                    color = Color(0xFF00FF41),
                                    trackColor = Color(0x3300FF41),
                                    modifier = Modifier.fillMaxWidth().height(4.dp)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Interval (secs):", fontSize = 11.sp, color = Color.LightGray)
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = rotationInterval,
                                    onValueChange = {
                                        rotationInterval = it
                                        it.toIntOrNull()?.let { seconds ->
                                            VpnProxyManager.setRotationInterval(seconds)
                                        }
                                    },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF00FF41),
                                        unfocusedBorderColor = Color(0xFF1E2026)
                                    ),
                                    modifier = Modifier.width(80.dp).height(45.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (proxyRotationMode) {
                                    0 -> "Available Proxy Locations:"
                                    1 -> "Active Proxy Pool (Rotates per Session):"
                                    else -> "Proxy Loop Pool:"
                                },
                                fontSize = 11.sp,
                                color = Color.LightGray
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
                                    containerColor = Color(0x3300FF41),
                                    disabledContainerColor = Color(0x1100FF41)
                                ),
                                border = BorderStroke(1.dp, Color(0xFF00FF41)),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                if (isPinging) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF00FF41),
                                        modifier = Modifier.size(10.dp),
                                        strokeWidth = 1.dp
                                    )
                                } else {
                                    Text("PING ALL", fontSize = 8.sp, color = Color(0xFF00FF41), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 6.dp)
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
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, if (isSelected) Color(0xFF00FF41) else Color(0x221E2026)),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) Color(0x2200FF41) else Color(0x11FFFFFF)
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
                                                modifier = Modifier
                                                    .border(1.dp, pingColor, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            )

                                            if (isSelected) {
                                                Text(
                                                    text = "ACTIVE",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF00FF41)
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
                colors = CardDefaults.cardColors(containerColor = Color(0xBB0B0D13))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Advanced Sniffer Configuration", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 12.dp))
                    
                    OutlinedTextField(
                        value = vpnMtu,
                        onValueChange = {
                            vpnMtu = it
                            sharedPrefs.edit().putString("vpn_mtu", it).apply()
                        },
                        label = { Text("MTU (Default: 1500)", color = Color.Gray, fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00FF41),
                            unfocusedBorderColor = Color(0xFF1E2026)
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = vpnDns,
                        onValueChange = {
                            vpnDns = it
                            sharedPrefs.edit().putString("vpn_dns", it).apply()
                        },
                        label = { Text("DNS Gateway Server", color = Color.Gray, fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00FF41),
                            unfocusedBorderColor = Color(0xFF1E2026)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            // App exclusion options
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF1E2026)),
                colors = CardDefaults.cardColors(containerColor = Color(0xBB0B0D13))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Bypassed Applications", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Check applications to let them bypass Sniffer VPN routing", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = appSearchQuery,
                            onValueChange = { appSearchQuery = it },
                            placeholder = { Text("Search apps…", color = Color.DarkGray, fontSize = 12.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00FF41),
                                unfocusedBorderColor = Color(0xFF1E2026)
                            ),
                            modifier = Modifier.weight(1f).height(48.dp)
                        )
                        if (disallowedPackages.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    disallowedPackages = emptySet()
                                    sharedPrefs.edit().putStringSet("disallowed_packages", emptySet()).apply()
                                },
                                shape = RoundedCornerShape(4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8F0011)),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Clear", fontSize = 10.sp, color = Color.White)
                            }
                        }
                    }

                    Column(modifier = Modifier.height(180.dp).fillMaxWidth()) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filteredApps.take(15)) { app: BypassedApp ->
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
                                        Text(app.packageName, fontSize = 9.sp, color = Color.Gray)
                                    }
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { isChecked ->
                                            val nextSet = if (isChecked) disallowedPackages + app.packageName else disallowedPackages - app.packageName
                                            disallowedPackages = nextSet
                                            sharedPrefs.edit().putStringSet("disallowed_packages", nextSet).apply()
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Color(0xFF00FF41)
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
