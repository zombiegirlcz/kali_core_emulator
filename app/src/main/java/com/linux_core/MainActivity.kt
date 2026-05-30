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
                VpnControlCenterScreen(modifier = Modifier.weight(1f))
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

@Composable
fun VpnControlCenterScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pm = remember { context.packageManager }

    val sharedPrefs = remember { context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE) }
    var isVpnRunning by remember { mutableStateOf(com.linux_core.core.VpnCaptureService.isRunning()) }
    var packetCount by remember { mutableStateOf(com.linux_core.core.VpnCaptureService.getCapturedPacketCount()) }
    var byteCount by remember { mutableStateOf(com.linux_core.core.VpnCaptureService.getCapturedByteCount()) }

    var vpnMtu by remember { mutableStateOf(sharedPrefs.getString("vpn_mtu", "1500") ?: "1500") }
    var vpnDns by remember { mutableStateOf(sharedPrefs.getString("vpn_dns", "8.8.8.8") ?: "8.8.8.8") }

    var isProxyEnabled by remember { mutableStateOf(com.linux_core.core.VpnProxyManager.isEnabled()) }
    var proxyRotationMode by remember { mutableStateOf(com.linux_core.core.VpnProxyManager.getRotationMode()) }
    var selectedProxyIndex by remember { mutableStateOf(com.linux_core.core.VpnProxyManager.getSelectedNodeIndex()) }
    var rotationInterval by remember { mutableStateOf(com.linux_core.core.VpnProxyManager.getRotationInterval().toString()) }

    var activeSubTab by remember { mutableStateOf("dashboard") }

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
            isVpnRunning = com.linux_core.core.VpnCaptureService.isRunning()
            packetCount = com.linux_core.core.VpnCaptureService.getCapturedPacketCount()
            byteCount = com.linux_core.core.VpnCaptureService.getCapturedByteCount()
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Futuristic mini sub-tab panel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("dashboard" to "Panel", "traffic" to "Traffic", "security" to "Security").forEach { (tabId, label) ->
                val active = activeSubTab == tabId
                Button(
                    onClick = { activeSubTab = tabId },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) Color(0xCC008F11) else Color(0x771E2026)
                    ),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f).height(32.dp)
                ) {
                    Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        when (activeSubTab) {
            "dashboard" -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
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
                                    androidx.compose.material3.Switch(
                                        checked = isVpnRunning,
                                        onCheckedChange = { checked ->
                                            val intent = Intent(context, com.linux_core.core.VpnCaptureService::class.java).apply {
                                                action = if (checked) com.linux_core.core.VpnCaptureService.ACTION_START else com.linux_core.core.VpnCaptureService.ACTION_STOP
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
                                        colors = androidx.compose.material3.SwitchDefaults.colors(
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
                                    androidx.compose.material3.Switch(
                                        checked = isProxyEnabled,
                                        onCheckedChange = { checked ->
                                            isProxyEnabled = checked
                                            com.linux_core.core.VpnProxyManager.setEnabled(checked)
                                        },
                                        colors = androidx.compose.material3.SwitchDefaults.colors(
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
                                                    com.linux_core.core.VpnProxyManager.setRotationMode(index)
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

                                    if (proxyRotationMode == 0) {
                                        Text("Select geographic location:", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
                                        Column(modifier = Modifier.padding(top = 4.dp)) {
                                            com.linux_core.core.VpnProxyManager.proxyPool.forEachIndexed { idx, node ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            selectedProxyIndex = idx
                                                            com.linux_core.core.VpnProxyManager.setSelectedNodeIndex(idx)
                                                        }
                                                        .padding(vertical = 4.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "• ${node.country} (${node.ip}:${node.port})",
                                                        fontSize = 12.sp,
                                                        color = if (selectedProxyIndex == idx) Color(0xFF00FF41) else Color.LightGray
                                                    )
                                                    if (selectedProxyIndex == idx) {
                                                        Text("active", fontSize = 10.sp, color = Color(0xFF00FF41), fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    } else if (proxyRotationMode == 2) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Interval (secs):", fontSize = 11.sp, color = Color.LightGray)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            androidx.compose.material3.OutlinedTextField(
                                                value = rotationInterval,
                                                onValueChange = {
                                                    rotationInterval = it
                                                    it.toIntOrNull()?.let { seconds ->
                                                        com.linux_core.core.VpnProxyManager.setRotationInterval(seconds)
                                                    }
                                                },
                                                singleLine = true,
                                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color(0xFF00FF41),
                                                    unfocusedBorderColor = Color(0xFF1E2026)
                                                ),
                                                modifier = Modifier.width(80.dp).height(45.dp)
                                            )
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
                                
                                androidx.compose.material3.OutlinedTextField(
                                    value = vpnMtu,
                                    onValueChange = {
                                        vpnMtu = it
                                        sharedPrefs.edit().putString("vpn_mtu", it).apply()
                                    },
                                    label = { Text("MTU (Default: 1500)", color = Color.Gray, fontSize = 11.sp) },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF00FF41),
                                        unfocusedBorderColor = Color(0xFF1E2026)
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                )

                                androidx.compose.material3.OutlinedTextField(
                                    value = vpnDns,
                                    onValueChange = {
                                        vpnDns = it
                                        sharedPrefs.edit().putString("vpn_dns", it).apply()
                                    },
                                    label = { Text("DNS Gateway Server", color = Color.Gray, fontSize = 11.sp) },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
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
                                    androidx.compose.material3.OutlinedTextField(
                                        value = appSearchQuery,
                                        onValueChange = { appSearchQuery = it },
                                        placeholder = { Text("Search apps…", color = Color.DarkGray, fontSize = 12.sp) },
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
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
                                                androidx.compose.material3.Checkbox(
                                                    checked = checked,
                                                    onCheckedChange = { isChecked ->
                                                        val nextSet = if (isChecked) disallowedPackages + app.packageName else disallowedPackages - app.packageName
                                                        disallowedPackages = nextSet
                                                        sharedPrefs.edit().putStringSet("disallowed_packages", nextSet).apply()
                                                    },
                                                    colors = androidx.compose.material3.CheckboxDefaults.colors(
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

            "traffic" -> {
                // Interactive Compose line graph representing multi-timeframe network telemetry
                var timeframe by remember { mutableStateOf("30d") } // "24h", "7d", "30d"
                val rawData = remember(timeframe) {
                    when (timeframe) {
                        "24h" -> com.linux_core.core.VpnLogManager.getHourlyTraffic()
                        "7d" -> {
                            val full = com.linux_core.core.VpnLogManager.getDailyTraffic()
                            Pair(full.first.takeLast(7).toLongArray(), full.second.takeLast(7).toLongArray())
                        }
                        else -> com.linux_core.core.VpnLogManager.getDailyTraffic()
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
                                val date = java.util.Date(System.currentTimeMillis() - (6 - idx) * dayMs)
                                java.text.SimpleDateFormat("dd.MM", java.util.Locale.getDefault()).format(date)
                            }
                            else -> {
                                val dayMs = 1000L * 60 * 60 * 24
                                val date = java.util.Date(System.currentTimeMillis() - (29 - idx) * dayMs)
                                java.text.SimpleDateFormat("dd.MM", java.util.Locale.getDefault()).format(date)
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

            "security" -> {
                // Interactive live audit security logs
                var activeFilter by remember { mutableStateOf("ALL") }
                val auditLogs = remember { mutableStateOf(com.linux_core.core.VpnLogManager.getLogs()) }

                // Periodic logger updater
                LaunchedEffect(Unit) {
                    while (true) {
                        auditLogs.value = com.linux_core.core.VpnLogManager.getLogs()
                        kotlinx.coroutines.delay(1500)
                    }
                }

                val filteredLogs = remember(activeFilter, auditLogs.value) {
                    if (activeFilter == "ALL") {
                        auditLogs.value
                    } else {
                        auditLogs.value.filter { it.category.name == activeFilter }
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Cyber Security Auditor", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Button(
                            onClick = {
                                val path = com.linux_core.core.VpnLogManager.exportLogsToDownloads(context)
                                if (path != null) {
                                    Toast.makeText(context, "Logs extracted to: Downloads folder", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Log extraction failed", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC008F11)),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("EXPORT AUDIT", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Logs category selection
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("ALL" to "All", "ALLOWED" to "Ok", "SUSPICIOUS" to "Suspicious", "CRITICAL" to "Alert").forEach { (filterId, label) ->
                            val active = activeFilter == filterId
                            Button(
                                onClick = { activeFilter = filterId },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (active) Color(0xFF008F11) else Color(0x221E2026)
                                ),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.weight(1f).height(28.dp)
                            ) {
                                Text(label, fontSize = 9.sp, color = Color.White)
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E2026)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xCC08090D))
                    ) {
                        if (filteredLogs.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No logged connections yet", color = Color.DarkGray, fontSize = 12.sp)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(filteredLogs) { entry: com.linux_core.core.VpnLogManager.LogEntry ->
                                    val color = when (entry.category) {
                                        com.linux_core.core.VpnLogManager.AuditCategory.CRITICAL -> Color(0xFFFF3333)
                                        com.linux_core.core.VpnLogManager.AuditCategory.SUSPICIOUS -> Color(0xFFFF9900)
                                        com.linux_core.core.VpnLogManager.AuditCategory.BLOCKED -> Color(0xFFFFDD00)
                                        com.linux_core.core.VpnLogManager.AuditCategory.ALLOWED -> Color(0xFF00FF41)
                                        else -> Color.Gray
                                    }
                                    
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "${entry.protocol} Outbound Connection",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = color
                                            )
                                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
                                            Text(timeStr, fontSize = 9.sp, color = Color.Gray)
                                        }
                                        Text(
                                            text = "Dst: ${entry.dstIp}:${entry.dstPort} (size: ${entry.size}B)",
                                            fontSize = 12.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = Color.LightGray
                                        )
                                        if (entry.detail.isNotEmpty()) {
                                            Text(
                                                text = "Info: ${entry.detail}",
                                                fontSize = 9.sp,
                                                color = Color.LightGray,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        androidx.compose.material3.HorizontalDivider(
                                            thickness = 1.dp,
                                            color = Color(0x11FFFFFF)
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