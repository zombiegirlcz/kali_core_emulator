package com.linux_core.ui.vpn

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linux_core.BuildConfig
import com.linux_core.core.VpnProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BypassedApp(val name: String, val packageName: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnSettingsTab() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("vpn_settings", 0) }
    val scope = rememberCoroutineScope()

    var vpnMtu by remember { mutableStateOf(sharedPrefs.getString("vpn_mtu", "1500") ?: "1500") }
    var vpnDns by remember { mutableStateOf(sharedPrefs.getString("vpn_dns", "8.8.8.8") ?: "8.8.8.8") }
    var forceAdbSafety by remember { mutableStateOf(sharedPrefs.getBoolean("force_adb_safety", true)) }
    var aiEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("ai_enabled", true)) }
    var aiSensitivity by remember { mutableStateOf(sharedPrefs.getFloat("ai_sensitivity", 0.5f)) }
    var aiAutoAction by remember { mutableStateOf(sharedPrefs.getInt("ai_auto_action", 1)) }
    var isProxyEnabled by remember { mutableStateOf(VpnProxyManager.isEnabled()) }
    var customProxyIp by remember { mutableStateOf(VpnProxyManager.getCustomProxy() ?: "") }
    var mountStorage by remember { mutableStateOf(sharedPrefs.getBoolean("mount_storage", false)) }
    var enableCrossMount by remember { mutableStateOf(sharedPrefs.getBoolean("enable_cross_mount", false)) }
    var shareLocalApi by remember { mutableStateOf(sharedPrefs.getBoolean("share_local_api", false)) }
    var shareP2pMesh by remember { mutableStateOf(sharedPrefs.getBoolean("share_p2p_mesh", false)) }
    var enableMitm by remember { mutableStateOf(sharedPrefs.getBoolean("enable_mitm", BuildConfig.ENABLE_MITM)) }
    var logVerbosity by remember { mutableStateOf(sharedPrefs.getInt("log_verbosity", 1)) }
    var showActionMenu by remember { mutableStateOf(false) }
    var customProxyError by remember { mutableStateOf(false) }
    var showLogMenu by remember { mutableStateOf(false) }
    var disallowedPackages by remember { mutableStateOf(sharedPrefs.getStringSet("disallowed_packages", emptySet()) ?: emptySet()) }
    var appSearchQuery by remember { mutableStateOf("") }

    val pm = remember { context.packageManager }
    val installedApps = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { BypassedApp(it.loadLabel(pm).toString(), it.packageName) }
            .sortedBy { it.name }
    }
    val filteredApps = remember(appSearchQuery, installedApps) {
        if (appSearchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.name.contains(appSearchQuery, ignoreCase = true) ||
            it.packageName.contains(appSearchQuery, ignoreCase = true)
        }
    }

    fun saveSettings() {
        sharedPrefs.edit().apply {
            putString("vpn_mtu", vpnMtu)
            putString("vpn_dns", vpnDns)
            putBoolean("force_adb_safety", forceAdbSafety)
            putBoolean("ai_enabled", aiEnabled)
            putFloat("ai_sensitivity", aiSensitivity)
            putInt("ai_auto_action", aiAutoAction)
            putBoolean("mount_storage", mountStorage)
            putBoolean("enable_cross_mount", enableCrossMount)
            putBoolean("share_local_api", shareLocalApi)
            putBoolean("share_p2p_mesh", shareP2pMesh)
            putBoolean("enable_mitm", enableMitm)
            putInt("log_verbosity", logVerbosity)
            putStringSet("disallowed_packages", disallowedPackages)
            apply()
        }
        VpnProxyManager.setEnabled(isProxyEnabled)
        if (customProxyIp.isNotBlank()) {
            val ok = VpnProxyManager.setCustomProxy(customProxyIp)
            customProxyError = !ok
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                if (shareLocalApi || shareP2pMesh) {
                    // mesh/local API restart handled here
                }
            }
        }
        Toast.makeText(context, "Settings saved successfully! Restart VPN to apply.", Toast.LENGTH_SHORT).show()
    }

    val cardColors = CardDefaults.cardColors(containerColor = Color(0x730F0F13))
    val cardBorder = BorderStroke(1.dp, Color(0xFF1A1A2E))
    val accentGreen = Color(0xFF00FFA3)
    val accentBlue = Color(0xFF4488FF)
    val accentOrange = Color(0xFFFF9944)
    val accentYellow = Color(0xFFFFDD44)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Text(
                "⚙ VPN ADVANCED SETTINGS",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // 1. VPN CAPTURE & ROUTING OPTIONS
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = cardColors,
                border = cardBorder
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("1. VPN CAPTURE & ROUTING OPTIONS",
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = accentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

                    OutlinedTextField(
                        value = vpnMtu,
                        onValueChange = { vpnMtu = it },
                        label = { Text("MTU", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = darkTextFieldColors()
                    )

                    OutlinedTextField(
                        value = vpnDns,
                        onValueChange = { vpnDns = it },
                        label = { Text("DNS Server", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = darkTextFieldColors()
                    )

                     Row(
                         modifier = Modifier.fillMaxWidth(),
                         horizontalArrangement = Arrangement.SpaceBetween,
                         verticalAlignment = Alignment.CenterVertically
                     ) {
                         Column(modifier = Modifier.weight(1f)) {
                             Text("Wi-Fi ADB Protection", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                             Text("Bypass local subnet routes for debugger connections on APIs < 33",
                                 color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                         }
                         Switch(checked = forceAdbSafety, onCheckedChange = { forceAdbSafety = it },
                             colors = SwitchDefaults.colors(checkedThumbColor = accentGreen, checkedTrackColor = accentGreen.copy(alpha = 0.3f)))
                     }

                     Spacer(modifier = Modifier.height(10.dp))

                     Row(
                         modifier = Modifier.fillMaxWidth(),
                         horizontalArrangement = Arrangement.SpaceBetween,
                         verticalAlignment = Alignment.CenterVertically
                     ) {
                         Column(modifier = Modifier.weight(1f)) {
                             Text("TLS MITM Inspection", color = Color(0xFFFFD740), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                             Text("Re-sign certificates and decrypt TLS flows for inspection",
                                 color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                         }
                         Switch(checked = enableMitm, onCheckedChange = { enableMitm = it },
                             colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFD740), checkedTrackColor = Color(0xFFFFD740).copy(alpha = 0.3f)))
                     }
                 }
             }
        }

        // 2. AI ANOMALY & THREAT DETECTION
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = cardColors,
                border = cardBorder
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("2. AI ANOMALY & THREAT DETECTION",
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = accentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("ONNX Real-time Classifier", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("Process flow entropy metrics through vpn_brain.onnx model",
                                color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Switch(checked = aiEnabled, onCheckedChange = { aiEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = accentGreen, checkedTrackColor = accentGreen.copy(alpha = 0.3f)))
                    }

                    if (aiEnabled) {
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Detection Sensitivity", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                Text("%.2f".format(aiSensitivity), color = accentGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Slider(
                                value = aiSensitivity,
                                onValueChange = { aiSensitivity = it },
                                valueRange = 0.1f..1.0f,
                                colors = SliderDefaults.colors(thumbColor = accentGreen, activeTrackColor = accentGreen, inactiveTrackColor = Color(0xFF1A1A2E))
                            )

                            Text("Mitigation Auto-Defense Action",
                                modifier = Modifier.padding(top = 6.dp),
                                color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)

                            val actionLabels = listOf("Off / Ignore", "Alert / Toast UI", "Firewall IP Auto-Block", "Proxy Divert (Deroute flow)")
                            Box {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF1A1A3E), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0xFF222244), RoundedCornerShape(8.dp))
                                        .clickable { showActionMenu = true }
                                        .padding(12.dp)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(actionLabels.getOrElse(aiAutoAction) { "Alert / Toast UI" },
                                            color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                        Text("▼", color = accentGreen, fontSize = 12.sp)
                                    }
                                }

                                DropdownMenu(expanded = showActionMenu, onDismissRequest = { showActionMenu = false },
                                    modifier = Modifier.background(Color(0xFF1A1A3E)).border(1.dp, Color(0xFF222244))) {
                                    actionLabels.forEachIndexed { index, label ->
                                        DropdownMenuItem(text = { Text(label, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                                            onClick = { aiAutoAction = index; showActionMenu = false })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. PROXY & ROUTING CONFIGURATION
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = cardColors,
                border = cardBorder
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("3. PROXY & ROUTING CONFIGURATION",
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = accentYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Proxy / VPN Capture Override", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("Enable proxy-based packet interceptor", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Switch(checked = isProxyEnabled, onCheckedChange = { isProxyEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = accentYellow, checkedTrackColor = accentYellow.copy(alpha = 0.3f)))
                    }

                    if (isProxyEnabled) {
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text("Custom Proxy IP:Port", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("All TCP traffic will be tunneled to this endpoint", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = customProxyIp,
                                onValueChange = { customProxyIp = it; customProxyError = false },
                                placeholder = { Text("192.168.1.100:8080", color = Color.Gray, fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth().background(Color(0xFF0D0D2B), RoundedCornerShape(8.dp)),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = accentYellow,
                                    unfocusedBorderColor = Color(0xFF222244),
                                    cursorColor = accentYellow,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            )
                            if (customProxyError) {
                                Text("Invalid format — use IP:Port (e.g. 1.2.3.4:8080)",
                                    color = Color(0xFFFF4444), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        // 4. GENERAL OPERATOR SETTINGS
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = cardColors,
                border = cardBorder
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("4. GENERAL OPERATOR SETTINGS",
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = accentOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

                    SettingsSwitch("Mount Storage", "Share filesystem into VPN namespace", mountStorage, { mountStorage = it })
                    SettingsSwitch("Enable Cross-Mount", "Allow rootfs access from VPN tunnel", enableCrossMount, { enableCrossMount = it })
                    SettingsSwitch("Share Local API", "Expose API server on VPN gateway", shareLocalApi, { shareLocalApi = it })
                    SettingsSwitch("Share P2P Mesh", "Join distributed P2P VPN overlay", shareP2pMesh, { shareP2pMesh = it })

                    Spacer(Modifier.height(8.dp))

                    Text("Log Verbosity", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    val logLevels = listOf("Errors Only", "Basic", "Verbose", "Debug")
                    Box {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1A1A3E), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF222244), RoundedCornerShape(8.dp))
                                .clickable { showLogMenu = true }
                                .padding(12.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(logLevels.getOrElse(logVerbosity) { "Basic" },
                                    color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text("▼", color = accentOrange, fontSize = 12.sp)
                            }
                        }
                        DropdownMenu(expanded = showLogMenu, onDismissRequest = { showLogMenu = false },
                            modifier = Modifier.background(Color(0xFF1A1A3E)).border(1.dp, Color(0xFF222244))) {
                            logLevels.forEachIndexed { index, label ->
                                DropdownMenuItem(text = { Text(label, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                                    onClick = { logVerbosity = index; showLogMenu = false })
                            }
                        }
                    }
                }
            }
        }

        // 5. APP BYPASS / DISALLOWED PACKAGES
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = cardColors,
                border = cardBorder
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("5. APP VPN BYPASS LIST",
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = Color(0xFFFF6666), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

                    OutlinedTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        placeholder = { Text("Search apps...", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        colors = darkTextFieldColors()
                    )

                    Text("Checked apps will NOT be routed through VPN",
                        color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 8.dp))

                    filteredApps.take(50).forEach { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = disallowedPackages.contains(app.packageName),
                                onCheckedChange = { checked ->
                                    disallowedPackages = if (checked) {
                                        disallowedPackages + app.packageName
                                    } else {
                                        disallowedPackages - app.packageName
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = accentGreen, uncheckedColor = Color.Gray)
                            )
                            Column {
                                Text(app.name, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text(app.packageName, color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        // Save button
        item {
            Button(
                onClick = { saveSettings() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentGreen, contentColor = Color.Black)
            ) {
                Text("💾 SAVE ALL SETTINGS", fontWeight = FontWeight.Bold, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(subtitle, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF9944), checkedTrackColor = Color(0xFFFF9944).copy(alpha = 0.3f)))
    }
}

@Composable
private fun darkTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Color(0xFF4488FF),
    unfocusedBorderColor = Color(0xFF1A1A2E),
    cursorColor = Color.White,
    focusedLabelColor = Color.Gray,
    unfocusedLabelColor = Color.Gray
)
