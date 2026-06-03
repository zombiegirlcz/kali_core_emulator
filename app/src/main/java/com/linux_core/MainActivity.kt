package com.linux_core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linux_core.core.Distro
import com.linux_core.core.RootfsManager
import com.linux_core.ui.terminal.TerminalActivity
import com.linux_core.ui.theme.NethunteraioperatorTheme
import com.linux_core.ui.vpn.VpnCenterScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date


private fun hasAllFilesAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

private fun requestAllFilesAccess(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            context.startActivity(intent)
        }
    } else {
        if (context is ComponentActivity) {
            androidx.core.app.ActivityCompat.requestPermissions(
                context,
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                1001
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        com.linux_core.core.ShortcutHelper.registerShortcuts(this)
        setContent {
            NethunteraioperatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF07080A) // Deep cyber black
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MatrixBackground() {
    var time by remember { mutableStateOf(0f) }
    var lastFrameTime by remember { mutableStateOf(0L) }
    var delta by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameTime ->
                if (lastFrameTime == 0L) {
                    lastFrameTime = frameTime
                }
                delta = (frameTime - lastFrameTime) / 1_000_000_000f
                lastFrameTime = frameTime
                time += delta
            }
        }
    }

    val drops = remember {
        val pool = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789$+-*/=%#&_<>[]~"
        Array(150) {
            object {
                var x = (Random.nextFloat() - 0.5f) * 3000f
                var y = (Random.nextFloat() - 0.5f) * 3000f
                var z = Random.nextFloat() * 1500f
                var speed = 300f + Random.nextFloat() * 400f
                var chars = CharArray(10 + Random.nextInt(15)) { pool.random() }
                var updateTimer = Random.nextFloat()
            }
        }
    }

    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#00FF41")
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            setShadowLayer(5f, 0f, 0f, android.graphics.Color.parseColor("#00FF41"))
        }
    }

    val whitePaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            setShadowLayer(8f, 0f, 0f, android.graphics.Color.WHITE)
        }
    }

    val pool = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789$+-*/=%#&_<>[]~"

    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        val currentDelta = delta
        val cx = size.width / 2
        val cy = size.height / 2
        val fov = 600f
        val maxZ = 1500f
        val camZ = time * 250f // Camera moves forward through the Matrix

        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas

            for (i in drops.indices) {
                val drop = drops[i]

                // Update falling y
                drop.y += drop.speed * currentDelta
                if (drop.y > 1500f) {
                    drop.y = -1500f
                    drop.x = (Random.nextFloat() - 0.5f) * 3000f
                }

                // Update characters randomly
                drop.updateTimer -= currentDelta
                if (drop.updateTimer <= 0f) {
                    drop.updateTimer = 0.05f + Random.nextFloat() * 0.1f
                    drop.chars[Random.nextInt(drop.chars.size)] = pool.random()
                }

                // Calculate relative Z for 3D camera movement
                val dz = ((drop.z - camZ) % maxZ + maxZ) % maxZ

                if (dz < 10f || dz > maxZ - 10f) continue // Too close or too far to render

                val scale = fov / dz
                val pFontSize = 36f * scale
                textPaint.textSize = pFontSize
                whitePaint.textSize = pFontSize

                val px = cx + drop.x * scale
                
                // Only draw if roughly within horizontal screen bounds
                if (px < -200f || px > size.width + 200f) continue

                for (j in drop.chars.indices) {
                    val charY = drop.y - j * 45f // vertical spacing in world space
                    val pCharY = cy + charY * scale

                    // Skip characters outside vertical bounds
                    if (pCharY < -100f || pCharY > size.height + 100f) continue

                    val alpha = 255 - (j * 255 / drop.chars.size)
                    
                    if (j == 0) {
                        whitePaint.alpha = 255
                        nativeCanvas.drawText(drop.chars[j].toString(), px, pCharY, whitePaint)
                    } else {
                        textPaint.alpha = alpha
                        nativeCanvas.drawText(drop.chars[j].toString(), px, pCharY, textPaint)
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var selectedDistro by remember { mutableStateOf(RootfsManager.DISTROS[0]) }
    var isExtracted by remember(selectedDistro) { mutableStateOf(RootfsManager.isRootfsExtracted(context, selectedDistro)) }
    var downloadProgress by remember { mutableStateOf(0) }
    var isDownloading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var mountStorage by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var isMoreMenuExpanded by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var backupFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    var hasStoragePermission by remember { mutableStateOf(hasAllFilesAccess(context)) }
    var currentTab by remember { mutableStateOf("home") }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = hasAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        onDispose { downloadJob?.cancel() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 3D Matrix Background Layer
        MatrixBackground()

        // Content Layer
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cyber Tab Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = { currentTab = "home" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentTab == "home") Color(0xFF008F11) else Color(0xCC1E2026)
                    ),
                    shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text("Distros", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(2.dp))
                Button(
                    onClick = { currentTab = "vpn_center" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentTab == "vpn_center") Color(0xFF008F11) else Color(0xCC1E2026)
                    ),
                    shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text("VPN Center", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (currentTab == "home") {
                Text(
                    text = "linux-distro",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00FF41), // Matrix Green
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "Select active guest environment",
                    fontSize = 14.sp,
                    color = Color.LightGray,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                // Distro Selector Cards
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RootfsManager.DISTROS.forEach { distro ->
                        val isSelected = (distro == selectedDistro)
                        val cardBorder = if (isSelected) {
                            BorderStroke(2.dp, Color(0xFF00FF41))
                        } else {
                            BorderStroke(1.dp, Color(0xFF1E2026))
                        }

                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .clickable(enabled = !isDownloading) {
                                    selectedDistro = distro
                                },
                            shape = RoundedCornerShape(12.dp),
                            border = cardBorder,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) Color(0xDD12141C) else Color(0xDD0B0D13)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = distro.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else Color.Gray,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                val exists = RootfsManager.isRootfsExtracted(context, distro)
                                Text(
                                    text = if (exists) "Installed" else "Not Ready",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (exists) Color(0xFF00FF66) else Color.DarkGray
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 24.dp).clickable(enabled = !isDownloading) {
                        val nextState = !mountStorage
                        if (nextState && !hasStoragePermission) {
                            requestAllFilesAccess(context)
                        }
                        mountStorage = nextState
                    }
                ) {
                    androidx.compose.material3.Switch(
                        checked = mountStorage,
                        onCheckedChange = { checked ->
                            if (checked && !hasStoragePermission) {
                                requestAllFilesAccess(context)
                            }
                            mountStorage = checked
                        },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00FF41),
                            checkedTrackColor = Color(0x8800FF41)
                        )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Mount /sdcard Storage",
                            color = Color.LightGray,
                            fontSize = 14.sp
                        )
                        if (mountStorage && !hasStoragePermission) {
                            Text(
                                text = "All Files Access required! Tap to grant.",
                                color = Color(0xFFFF3333),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { requestAllFilesAccess(context) }
                            )
                        }
                    }
                }

                // Action Flow — isDownloading has HIGHEST priority so progress is always visible
                if (isDownloading) {
                    // Universal progress display (backup, restore, reinstall, install)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF00FF41)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xDD0C0E14))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = statusText,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            // Progress bar — always visible
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    // Background track
                                    drawRoundRect(
                                        color = Color(0xFF1E2026),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                                    )
                                    // Filled progress
                                    val fraction = if (downloadProgress > 0) downloadProgress / 100f else 0f
                                    drawRoundRect(
                                        color = Color(0xFF00FF41),
                                        size = androidx.compose.ui.geometry.Size(
                                            size.width * fraction,
                                            size.height
                                        ),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            // Percentage — always visible
                            Text(
                                text = if (downloadProgress >= 0) "$downloadProgress%" else "…",
                                color = Color(0xFF00FF41),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    downloadJob?.cancel()
                                    isDownloading = false
                                    statusText = ""
                                    downloadProgress = 0
                                    // Re-check extraction state after cancel
                                    isExtracted = RootfsManager.isRootfsExtracted(context, selectedDistro)
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF8F0011)
                                ),
                                modifier = Modifier.fillMaxWidth().height(42.dp)
                            ) {
                                Text("CANCEL", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                } else if (isExtracted) {
                    Button(
                        onClick = {
                            val intent = Intent(context, TerminalActivity::class.java).apply {
                                putExtra("rootfsDirName", selectedDistro.rootfsDirName)
                                putExtra("mountStorage", mountStorage)
                            }
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF008F11) // Darker Matrix Green
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            text = "LAUNCH " + selectedDistro.name.uppercase(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // More Options Expandable Cyber Panel
                    Button(
                        onClick = { isMoreMenuExpanded = !isMoreMenuExpanded },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xCC1E2026) // Sleek dark grey button, slightly transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            text = if (isMoreMenuExpanded) "HIDE OPTIONS" else "MORE OPTIONS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFF00FF41) // Accented green text
                        )
                    }

                    if (isMoreMenuExpanded) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF1E2026)),
                            colors = CardDefaults.cardColors(containerColor = Color(0x990B0D13))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "GUEST MANAGEMENT TOOLS",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00FF41)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Backup Button
                                    Button(
                                        onClick = {
                                            if (!hasStoragePermission) {
                                                requestAllFilesAccess(context)
                                            } else {
                                                isMoreMenuExpanded = false
                                                downloadJob = scope.launch {
                                                    isDownloading = true
                                                    try {
                                                        statusText = "Backing up rootfs…"
                                                        downloadProgress = 0
                                                        RootfsManager.backupRootfs(context, selectedDistro).collect { (progress, status) ->
                                                            downloadProgress = progress
                                                            statusText = status
                                                        }
                                                        Toast.makeText(context, "Backup saved to Downloads folder!", Toast.LENGTH_LONG).show()
                                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                    } finally {
                                                        isDownloading = false
                                                        statusText = ""
                                                        downloadProgress = 0
                                                    }
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E2026)),
                                        modifier = Modifier.weight(1f).height(42.dp)
                                    ) {
                                        Text("BACKUP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }

                                    // Restore Button
                                    Button(
                                        onClick = {
                                            if (!hasStoragePermission) {
                                                requestAllFilesAccess(context)
                                            } else {
                                                backupFiles = RootfsManager.getBackupFiles(selectedDistro)
                                                showRestoreDialog = true
                                            }
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E2026)),
                                        modifier = Modifier.weight(1f).height(42.dp)
                                    ) {
                                        Text("RESTORE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }

                                // Reinstall Button
                                Button(
                                    onClick = {
                                        downloadJob = scope.launch {
                                            isDownloading = true
                                            isMoreMenuExpanded = false
                                            try {
                                                val cacheDir = File(context.filesDir, selectedDistro.id)
                                                val archiveFile = File(cacheDir, selectedDistro.tarFileName)
                                                
                                                statusText = "Reinstalling: Deleting old files…"
                                                val rootfsDir = File(context.filesDir, selectedDistro.rootfsDirName)
                                                if (rootfsDir.exists()) {
                                                    rootfsDir.deleteRecursively()
                                                }
                                                isExtracted = false
                                                downloadProgress = 0

                                                val hasArchive = archiveFile.exists() && archiveFile.length() > 0
                                                if (!hasArchive) {
                                                    statusText = "Reinstalling: Downloading rootfs archive…"
                                                    RootfsManager.downloadRootfs(context, selectedDistro).collect { progress ->
                                                        downloadProgress = progress
                                                    }
                                                    downloadProgress = 0
                                                }

                                                statusText = "Reinstalling: Extracting filesystem…"
                                                RootfsManager.extractRootfs(context, selectedDistro).collect { progress ->
                                                    downloadProgress = progress
                                                }

                                                isExtracted = true
                                                statusText = ""
                                                Toast.makeText(context, "Reinstallation complete!", Toast.LENGTH_LONG).show()
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                isExtracted = RootfsManager.isRootfsExtracted(context, selectedDistro)
                                                throw e
                                            } catch (e: Exception) {
                                                isExtracted = RootfsManager.isRootfsExtracted(context, selectedDistro)
                                                Toast.makeText(context, "Reinstallation failed: ${e.message}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                isDownloading = false
                                                statusText = ""
                                                downloadProgress = 0
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8F0011)),
                                    modifier = Modifier.fillMaxWidth().height(42.dp)
                                ) {
                                    Text("REINSTALL (REEXTRACT)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Custom 1x1 Script Launchers Card
                    var customCommandText by remember { mutableStateOf("") }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E2026)),
                        colors = CardDefaults.cardColors(containerColor = Color(0x770B0D13))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "1x1 Home Screen Launchers",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00FF41),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = "Create desktop icons to run specific scripts directly",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            androidx.compose.material3.OutlinedTextField(
                                value = customCommandText,
                                onValueChange = { customCommandText = it },
                                placeholder = { Text("e.g. nethunter-vibrate 1000", color = Color.DarkGray, fontSize = 12.sp) },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00FF41),
                                    unfocusedBorderColor = Color(0xFF1E2026)
                                ),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            )
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        val cmd = customCommandText.trim().ifEmpty { null }
                                        com.linux_core.core.ShortcutHelper.pinShortcut(context, "kali", cmd, mountStorage)
                                        Toast.makeText(context, "Requested Kali shortcut!", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E2026)),
                                    modifier = Modifier.weight(1f).height(38.dp)
                                ) {
                                    Text("PIN KALI 1x1", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00FF41))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val cmd = customCommandText.trim().ifEmpty { null }
                                        com.linux_core.core.ShortcutHelper.pinShortcut(context, "parrot", cmd, mountStorage)
                                        Toast.makeText(context, "Requested Parrot shortcut!", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E2026)),
                                    modifier = Modifier.weight(1f).height(38.dp)
                                ) {
                                    Text("PIN PARROT 1x1", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00FF41))
                                }
                            }
                        }
                    }
                } else {
                    // Not extracted and not downloading — show INSTALL button
                    Button(
                        onClick = {
                            downloadJob = scope.launch {
                                isDownloading = true
                                try {
                                    statusText = "Downloading rootfs archive…"
                                    RootfsManager.downloadRootfs(context, selectedDistro).collect { progress ->
                                        downloadProgress = progress
                                    }

                                    downloadProgress = 0
                                    statusText = "Extracting rootfs filesystem…"
                                    RootfsManager.extractRootfs(context, selectedDistro).collect { progress ->
                                        downloadProgress = progress
                                    }

                                    isExtracted = true
                                    statusText = ""
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    downloadProgress = 0
                                    statusText = ""
                                    throw e
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Installation failed: " + (e.message ?: "Unknown error"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    isDownloading = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF008F11)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            text = "INSTALL " + selectedDistro.name.uppercase(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    }
                }
            } else {
                VpnCenterScreen(modifier = Modifier.weight(1f))
            }
        }

        if (showRestoreDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showRestoreDialog = false },
                title = {
                    Text(
                        text = "SELECT BACKUP TO RESTORE",
                        color = Color(0xFF00FF41),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                text = {
                    if (backupFiles.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No backup archives (.tar.gz) found in Downloads folder.",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(250.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(backupFiles) { file ->
                                val sizeMb = file.length() / (1024f * 1024f)
                                val dateStr = try {
                                    val timestampPart = file.name.substringAfter("-backup-").substringBefore(".tar.gz")
                                    val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).parse(timestampPart)
                                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date!!)
                                } catch (_: Exception) {
                                    val date = Date(file.lastModified())
                                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
                                }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showRestoreDialog = false
                                            downloadJob = scope.launch {
                                                isDownloading = true
                                                isMoreMenuExpanded = false
                                                try {
                                                    statusText = "Starting restore..."
                                                    downloadProgress = 0
                                                    isExtracted = false
                                                    
                                                    RootfsManager.restoreRootfs(context, file, selectedDistro).collect { (progress, status) ->
                                                        downloadProgress = progress
                                                        statusText = status
                                                    }
                                                    
                                                    isExtracted = true
                                                    Toast.makeText(context, "Restore complete!", Toast.LENGTH_LONG).show()
                                                } catch (e: kotlinx.coroutines.CancellationException) {
                                                    isExtracted = RootfsManager.isRootfsExtracted(context, selectedDistro)
                                                    throw e
                                                } catch (e: Exception) {
                                                    isExtracted = RootfsManager.isRootfsExtracted(context, selectedDistro)
                                                    Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                } finally {
                                                    isDownloading = false
                                                    statusText = ""
                                                    downloadProgress = 0
                                                }
                                            }
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2026)),
                                    border = BorderStroke(1.dp, Color(0x3300FF41))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = file.name,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "$dateStr | ${String.format("%.1f MB", sizeMb)}",
                                                color = Color.Gray,
                                                fontSize = 10.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "RESTORE",
                                            color = Color(0xFF00FF41),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    Button(
                        onClick = { showRestoreDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E2026))
                    ) {
                        Text("Close", color = Color.White)
                    }
                },
                containerColor = Color(0xFF0C0E14),
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}
