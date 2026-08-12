package com.linux_core.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "EditorTab"
private const val EDITOR_HOST = "127.0.0.1"
private const val EDITOR_PORT = 8443
private const val EDITOR_URL = "http://$EDITOR_HOST:$EDITOR_PORT"

private enum class EditorState { Stopped, Starting, Running, NotInstalled, Error }

private data class EditorInfo(
    val bind: String,
    val port: Int,
    val workspace: String,
    val config: String,
    val binary: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accentGreen = Color(0xFF00FF41)
    val accentYellow = Color(0xFFFFD740)
    val accentRed = Color(0xFFFF5252)
    val cardColors = CardDefaults.cardColors(containerColor = Color(0xFF0C0E14))
    val cardBorder = BorderStroke(1.dp, Color(0xFF1E2026))

    var state by remember { mutableStateOf(EditorState.Stopped) }
    var statusText by remember { mutableStateOf("Editor stopped") }
    var password by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<EditorInfo?>(null) }
    var showPasswordSheet by remember { mutableStateOf(false) }
    var showFirstTimeDialog by remember { mutableStateOf(false) }

    suspend fun callEditor(method: String, path: String): String = withContext(Dispatchers.IO) {
        val conn = (URL("http://127.0.0.1:1337$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4000
            readTimeout = 12000
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        } catch (e: Exception) {
            "{\"error\":\"${e.message}\"}"
        } finally {
            conn.disconnect()
        }
    }

    suspend fun refreshStatus() {
        try {
            val raw = callEditor("GET", "/editor/status")
            val obj = JSONObject(raw)
            val s = obj.optString("status", "unknown")
            val errorOutput = obj.optString("output", "") + obj.optString("error", "")
            state = when {
                s == "running" -> EditorState.Running
                s == "stopped" -> EditorState.Stopped
                s == "port_busy" -> EditorState.Error
                errorOutput.contains("binary not found", ignoreCase = true) ||
                    errorOutput.contains("not installed", ignoreCase = true) ||
                    errorOutput.contains("not found", ignoreCase = true) ->
                    EditorState.NotInstalled
                else -> EditorState.Error
            }
            statusText = when {
                state == EditorState.NotInstalled -> "code-server is not installed in rootfs"
                s == "running" -> "Editor running on :$EDITOR_PORT"
                s == "stopped" -> "Editor stopped"
                s == "port_busy" -> "Port $EDITOR_PORT busy (another process holds it)"
                else -> "Status: $s"
            }
        } catch (e: Exception) {
            statusText = "Status check failed: ${e.message}"
        }
    }

    suspend fun loadPassword(showSheet: Boolean = true) {
        try {
            val raw = callEditor("GET", "/editor/password")
            val obj = JSONObject(raw)
            val pw = obj.optString("password", "")
            if (pw.isNotEmpty()) {
                password = pw
                val firstRun = !context.getSharedPreferences("editor_settings", Context.MODE_PRIVATE)
                    .getBoolean("first_time_done", false)
                if (firstRun) {
                    showFirstTimeDialog = true
                    context.getSharedPreferences("editor_settings", Context.MODE_PRIVATE)
                        .edit().putBoolean("first_time_done", true).apply()
                }
                if (showSheet) showPasswordSheet = true
            } else {
                Toast.makeText(context, "Password not yet available — start the editor first", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            statusText = "Password load failed: ${e.message}"
        }
    }

    suspend fun loadInfo() {
        try {
            val raw = callEditor("GET", "/editor/info")
            val obj = JSONObject(raw)
            info = EditorInfo(
                bind = obj.optString("bind", "$EDITOR_HOST:$EDITOR_PORT"),
                port = obj.optInt("port", EDITOR_PORT),
                workspace = obj.optString("workspace", "/root/projects"),
                config = obj.optString("config", "/root/.config/code-server/config.yaml"),
                binary = obj.optString("binary", "/opt/code-server/bin/code-server")
            )
        } catch (e: Exception) {
            statusText = "Info load failed: ${e.message}"
        }
    }

    fun startEditor() {
        scope.launch {
            state = EditorState.Starting
            statusText = "Starting editor…"
            val raw = callEditor("POST", "/editor/start")
            val obj = JSONObject(raw)
            if (obj.has("error")) {
                state = EditorState.Error
                statusText = "Start failed: ${obj.optString("error")}"
            } else {
                var attempts = 0
                while (attempts < 20) {
                    delay(500)
                    refreshStatus()
                    if (state == EditorState.Running) break
                    attempts += 1
                }
                if (state != EditorState.Running) {
                    state = EditorState.Error
                    statusText = "Editor did not become ready in time"
                } else {
                    loadPassword(showSheet = true)
                }
            }
        }
    }

    fun stopEditor() {
        scope.launch {
            callEditor("POST", "/editor/stop")
            delay(500)
            refreshStatus()
        }
    }

    fun openInBrowser() {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(EDITOR_URL))
            context.startActivity(browserIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        loadInfo()
        refreshStatus()
        if (state == EditorState.Running) loadPassword(showSheet = false)
    }

    val pillColor = when (state) {
        EditorState.Running -> accentGreen
        EditorState.Starting -> accentYellow
        EditorState.NotInstalled -> Color(0xFFFF9800)
        EditorState.Stopped -> Color.Gray
        EditorState.Error -> accentRed
    }
    val pillText = when (state) {
        EditorState.Running -> "RUNNING"
        EditorState.Starting -> "STARTING…"
        EditorState.NotInstalled -> "NOT INSTALLED"
        EditorState.Stopped -> "STOPPED"
        EditorState.Error -> "ERROR"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(pillColor, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    pillText,
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "CODE EDITOR",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = cardColors,
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(statusText, color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

                Spacer(Modifier.height(12.dp))

                when (state) {
                    EditorState.NotInstalled -> {
                        Button(
                            onClick = {
                                scope.launch {
                                    state = EditorState.Starting
                                    statusText = "Installing code-server…"
                                    val raw = callEditor("POST", "/editor/install")
                                    val obj = JSONObject(raw)
                                    if (obj.has("error")) {
                                        state = EditorState.Error
                                        statusText = "Install failed: ${obj.optString("error")}"
                                    } else {
                                        statusText = "Install succeeded. You can now start the editor."
                                        state = EditorState.Stopped
                                        refreshStatus()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "INSTALL CODE-SERVER",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    EditorState.Stopped, EditorState.Error -> {
                        Button(
                            onClick = { startEditor() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF008F11))
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "START EDITOR",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    EditorState.Starting -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                disabledContainerColor = Color(0xFF665700)
                            )
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = accentYellow,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "STARTING…",
                                color = accentYellow,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    EditorState.Running -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { openInBrowser() },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFF008F11))
                            ) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = accentGreen)
                                Spacer(Modifier.width(8.dp))
                                Text("Open", color = accentGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            OutlinedButton(
                                onClick = { stopEditor() },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, accentRed)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, tint = accentRed)
                                Spacer(Modifier.width(8.dp))
                                Text("Stop", color = accentRed, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { scope.launch { loadPassword(showSheet = true) } },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = accentYellow, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Password", color = accentYellow, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    OutlinedButton(
                        onClick = { scope.launch { loadInfo(); refreshStatus() } },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Refresh", color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                Spacer(Modifier.height(14.dp))

                info?.let { i ->
                    InfoRow("Bind", i.bind)
                    InfoRow("Workspace", i.workspace)
                    InfoRow("Config", i.config)
                    InfoRow("Binary", i.binary)
                } ?: run {
                    Text(
                        "Loading info…",
                        color = Color.DarkGray,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    if (showPasswordSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPasswordSheet = false },
            containerColor = Color(0xFF0C0E14)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    "Editor password",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E2026), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF008F11), RoundedCornerShape(8.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        password ?: "—",
                        color = accentGreen,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("code-server password", password ?: ""))
                        Toast.makeText(context, "Password copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF008F11))
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy to clipboard", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showFirstTimeDialog) {
        AlertDialog(
            onDismissRequest = { showFirstTimeDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = accentYellow)
                    Spacer(Modifier.width(8.dp))
                    Text("First-time setup", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        "A password has been generated for this editor. It's stored only on this device.",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E2026), RoundedCornerShape(6.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            password ?: "—",
                            color = accentGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("code-server password", password ?: ""))
                    showFirstTimeDialog = false
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy & Continue", color = accentGreen, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFirstTimeDialog = false }) {
                    Text("Later", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF0C0E14)
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "$label:",
            color = Color.DarkGray,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(82.dp)
        )
        Text(
            value,
            color = Color.LightGray,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
