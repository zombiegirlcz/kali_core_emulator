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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linux_core.core.Distro
import com.linux_core.core.RootfsManager
import com.linux_core.core.DockerImageRef
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

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 24 && resultCode == RESULT_OK) {
            val intent = Intent(this, com.linux_core.core.VpnCaptureService::class.java).apply {
                action = com.linux_core.core.VpnCaptureService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.linux_core.security.CertificateManager.init(applicationContext)
        com.linux_core.core.ImmersiveMode.enterImmersive(this)

        com.linux_core.core.ShortcutHelper.registerShortcuts(this)
        com.linux_core.core.VpnLogManager.initialize(applicationContext)
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
        }
    }

    val whitePaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
    }

    val pool = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789$+-*/=%#&_<>[]~"

    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        // Clear entire canvas first to prevent ghost text from previous frames
        drawRect(color = Color(0xFF07080A), size = size)

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
                val pFontSize = Math.round(36f * scale).toFloat().coerceIn(10f, 120f)
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
    val sharedPrefs = remember { context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE) }
    var selectedDistro by remember { mutableStateOf(RootfsManager.DISTROS[0]) }
    var isExtracted by remember(selectedDistro) { mutableStateOf(RootfsManager.isRootfsExtracted(context, selectedDistro)) }
    var downloadProgress by remember { mutableStateOf(0) }
    var isDownloading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var mountStorage by remember { mutableStateOf(sharedPrefs.getBoolean("mount_storage", false)) }
    var bootAutostart by remember { mutableStateOf(sharedPrefs.getBoolean("boot_autostart", true)) }
    val scope = rememberCoroutineScope()

    var isMoreMenuExpanded by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var backupFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    val restorePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        downloadJob = scope.launch {
            isDownloading = true
            isMoreMenuExpanded = false
            try {
                statusText = "Restoring from selected file..."
                downloadProgress = 0
                isExtracted = false
                RootfsManager.restoreRootfs(context, uri, selectedDistro).collect { (progress, status) ->
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
    }

    // Docker Hub custom image support
    var customDockerImage by remember { mutableStateOf("") }
    var dockerImageRef by remember { mutableStateOf<DockerImageRef?>(null) }
    var isPullingDocker by remember { mutableStateOf(false) }
    var dockerPullProgress by remember { mutableStateOf(0) }
    var dockerPullStatus by remember { mutableStateOf("") }
    var selectedDockerDir by remember { mutableStateOf<String?>(null) }
    var isDockerMode by remember { mutableStateOf(false) }
    // Seznam všech existujících Docker/OCI image dirů (pro výběr v UI)
    var dockerImageDirs by remember { mutableStateOf<List<String>>(emptyList()) }

    var hasStoragePermission by remember { mutableStateOf(hasAllFilesAccess(context)) }
    var currentTab by remember { mutableStateOf("home") }
    var activeSessionCount by remember { mutableStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = hasAllFilesAccess(context)
                activeSessionCount = com.linux_core.core.TerminalService.sessions.size
                // Skenovat existující Docker image adresáře
                val dirs = context.filesDir.listFiles()
                    ?.filter { it.isDirectory && (it.name.startsWith("docker-") || it.name.startsWith("oci-")) }
                    ?.map { it.name }
                    ?.sortedDescending() // nejnovější první (suffix timestamp)
                    ?: emptyList()
                dockerImageDirs = dirs
                // Pokud není vybrán žádný Docker dir a existuje alespoň jeden, vyber první
                if (selectedDockerDir == null && dirs.isNotEmpty()) {
                    selectedDockerDir = dirs.first()
                    // Automaticky přepnout do Docker módu, aby BOOT tlačítko bootovalo Docker
                    isDockerMode = true
                }
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
                    .padding(bottom = 20.dp)
                    .background(Color(0xE60D0E15), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0x3300FF41), RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isHome = (currentTab == "home")
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isHome) Color(0xFF008F11) else Color.Transparent)
                        .clickable { currentTab = "home" }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Distros",
                            tint = if (isHome) Color.White else Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "GUEST DISTROS",
                            color = if (isHome) Color.White else Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                val isEditor = (currentTab == "editor")
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isEditor) Color(0xFF008F11) else Color.Transparent)
                        .clickable { currentTab = "editor" }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "Code Editor",
                            tint = if (isEditor) Color.White else Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "EDITOR",
                            color = if (isEditor) Color.White else Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                val isVpn = (currentTab == "vpn_center")
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isVpn) Color(0xFF008F11) else Color.Transparent)
                        .clickable { currentTab = "vpn_center" }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "VPN Center",
                            tint = if (isVpn) Color.White else Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "VPN GATEWAY",
                            color = if (isVpn) Color.White else Color.Gray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                val isRootTab = (currentTab == "root")
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isRootTab) Color(0xFF008F11) else Color.Transparent)
                        .clickable { currentTab = "root" }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Root Bridge",
                            tint = if (isRootTab) Color.White else Color.Gray,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "ROOT BRIDGE",
                            color = if (isRootTab) Color.White else Color.Gray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }

            if (currentTab == "home") {
                val homeScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(homeScrollState)
                ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF00FF41), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "NETHUNTER // GUEST OS",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF00FF41),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Text(
                    text = "SELECT ACTIVE LINUX ENVIRONMENT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // Distro Selector Cards
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RootfsManager.DISTROS.forEach { distro ->
                        val isSelected = !isDockerMode && (distro == selectedDistro)
                        val exists = RootfsManager.isRootfsExtracted(context, distro)
                        val activeColor = if (distro.id == "kali") Color(0xFF00B0FF) else Color(0xFF00FF9F)
                        val borderBrush = if (isSelected) {
                            Brush.horizontalGradient(
                                colors = if (distro.id == "kali") {
                                    listOf(Color(0xFF0052D4), Color(0xFF4364F7), Color(0xFF00D2FF))
                                } else {
                                    listOf(Color(0xFF11998E), Color(0xFF38EF7D), Color(0xFF00FF9F))
                                }
                            )
                        } else {
                            Brush.horizontalGradient(colors = listOf(Color(0xFF1E2026), Color(0xFF1E2026)))
                        }

                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !isDownloading) {
                                    selectedDistro = distro
                                    isDockerMode = false
                                },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderBrush),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) Color(0xF20F111A) else Color(0xE608090D)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .background(
                                            if (isSelected) activeColor.copy(alpha = 0.15f) else Color(0xFF12131A),
                                            CircleShape
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) activeColor.copy(alpha = 0.5f) else Color(0xFF1E2026),
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (distro.id == "kali") "🐉" else "🦜",
                                        fontSize = 28.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = distro.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else Color.LightGray,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (distro.id == "kali") "PenTesting Env" else "Privacy OS",
                                    fontSize = 9.sp,
                                    color = Color.Gray,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(if (exists) Color(0xFF00FF66) else Color.DarkGray, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (exists) "INSTALLED" else "NOT READY",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (exists) Color(0xFF00FF66) else Color.DarkGray,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                // Docker Hub custom image card — 2nd row, centered
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Spacer(modifier = Modifier.weight(0.5f))
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !isDownloading && !isPullingDocker) {
                                isDockerMode = true
                            },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            if (isDockerMode) 2.dp else 1.dp,
                            if (isDockerMode) Brush.horizontalGradient(listOf(Color(0xFF00FF41), Color(0xFF00D2FF)))
                            else Brush.horizontalGradient(colors = listOf(Color(0xFF1E2026), Color(0xFF1E2026)))
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDockerMode) Color(0xF20F111A) else Color(0xE608090D)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .background(
                                        if (isDockerMode) Color(0xFF00FF41).copy(alpha = 0.15f) else Color(0xFF12131A),
                                        CircleShape
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isDockerMode) Color(0xFF00FF41).copy(alpha = 0.5f) else Color(0xFF1E2026),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                    // Docker whale icon (text-based)
                                    Text(
                                        text = "🐳",
                                        fontSize = 24.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "DOCKER HUB",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDockerMode) Color(0xFF00FF41) else Color.LightGray,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Custom Image",
                                fontSize = 9.sp,
                                color = Color.Gray,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(0.5f))
                }

                // Docker Hub — pull form (shown when Docker card selected but no image)
                if (isDockerMode && selectedDockerDir == null) {
                    // No Docker image yet — show pull UI
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0x3300FF41)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xE60B0D13))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = "Docker",
                                    tint = Color(0xFF00FF41),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "PULL FROM DOCKER HUB",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00FF41),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }

                            Text(
                                text = "Enter a Docker Hub image reference (e.g. kali/security, myuser/app:v1.0, alpine@sha256:digest) or an https:// URL to a rootfs archive (.tar.gz / .tar.xz)",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = customDockerImage,
                                    onValueChange = { customDockerImage = it },
                                    placeholder = { Text("kali/security:latest | https://…rootfs.tar.xz", color = Color.DarkGray, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
                                    singleLine = true,
                                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF00FF41),
                                        unfocusedBorderColor = Color(0xFF1E2026),
                                        focusedContainerColor = Color(0xFF07080A),
                                        unfocusedContainerColor = Color(0xFF07080A)
                                    ),
                                    modifier = Modifier.weight(1f),
                                    enabled = !isPullingDocker
                                )

                                Button(
                                    onClick = {
                                        if (customDockerImage.isBlank()) {
                                            Toast.makeText(context, "Enter a Docker image reference", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        downloadJob?.cancel()
                                        // isPullingDocker=true (nastaveno uvnitř jobu) brání double-click,
                                        // cancel() zajišťuje že starý job neruší nový
                                        downloadJob = scope.launch {
                                            isPullingDocker = true
                                            dockerPullProgress = 0
                                            val rawInput = customDockerImage.trim()
                                            val isWebUrl = rawInput.startsWith("http://") || rawInput.startsWith("https://")
                                            dockerPullStatus = if (isWebUrl) "Parsing URL…" else "Parsing image reference…"
                                            try {
                                                if (isWebUrl) {
                                                    // Web URL: stáhnout+extrahovat rootfs archive (HTTPS + whitelist)
                                                    dockerPullStatus = "Pulling rootfs from URL…"
                                                    RootfsManager.pullRootfsFromUrl(context, rawInput).collect { (progress, status) ->
                                                        dockerPullProgress = progress
                                                        dockerPullStatus = status
                                                        if (progress >= 100 && status.isNotEmpty() && File(status).exists()) {
                                                            selectedDockerDir = File(status).name
                                                        }
                                                    }
                                                    Toast.makeText(context, "Rootfs pulled from URL successfully!\nBoot from DOCKER HUB tab.", Toast.LENGTH_LONG).show()
                                                } else {
                                                    // Docker Hub image reference (image[:tag] or image@sha256:digest)
                                                    val ref = DockerImageRef.parse(rawInput)
                                                    dockerImageRef = ref
                                                    dockerPullStatus = "Pulling ${ref.fullName}:${ref.tag}…"
                                                    RootfsManager.pullDockerImage(context, ref).collect { (progress, status) ->
                                                        dockerPullProgress = progress
                                                        dockerPullStatus = status
                                                        if (progress >= 100 && status.isNotEmpty() && File(status).exists()) {
                                                            selectedDockerDir = File(status).name
                                                        }
                                                    }
                                                    Toast.makeText(context, "Docker image pulled successfully!\nBoot from DOCKER HUB tab.", Toast.LENGTH_LONG).show()
                                                }
                                                isDockerMode = true
                                                customDockerImage = ""
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                dockerPullProgress = 0
                                                dockerPullStatus = ""
                                                throw e
                                            } catch (e: Exception) {
                                                dockerPullProgress = 0
                                                dockerPullStatus = ""
                                                Toast.makeText(context, if (isWebUrl) "URL pull failed: ${e.message}" else "Docker pull failed: ${e.message}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                isPullingDocker = false
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF41)),
                                    modifier = Modifier.height(44.dp),
                                    enabled = !isPullingDocker
                                ) {
                                    Text(
                                        text = if (isPullingDocker) "PULLING…" else "PULL",
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }

                            if (isPullingDocker || dockerPullStatus.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .background(Color(0xFF1E2026), RoundedCornerShape(3.dp))
                                ) {
                                    val fraction = if (dockerPullProgress > 0) dockerPullProgress / 100f else 0f
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(fraction)
                                            .background(
                                                Brush.horizontalGradient(listOf(Color(0xFF00FF41), Color(0xFF00D2FF))),
                                                RoundedCornerShape(3.dp)
                                            )
                                    )
                                }
                                Text(
                                    text = dockerPullStatus.ifEmpty { "Preparing…" },
                                    fontSize = 10.sp,
                                    color = Color(0xFF00FF41),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // Mount /sdcard storage card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0x3300FF41)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xE60B0D13))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isDownloading) {
                                val nextState = !mountStorage
                                if (nextState && !hasStoragePermission) {
                                    requestAllFilesAccess(context)
                                    // Nerovnávat mountStorage dokud není oprávnění potvrzeno —
                                    // lifecycle observer (ON_RESUME) aktualizuje hasStoragePermission
                                    return@clickable
                                }
                                mountStorage = nextState
                                sharedPrefs.edit().putBoolean("mount_storage", nextState).apply()
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0x1A00FF41), CircleShape)
                                    .border(1.dp, Color(0x3300FF41), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Storage,
                                    contentDescription = "Storage",
                                    tint = Color(0xFF00FF41),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Mount Shared Storage",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                Text(
                                    text = "Map internal storage to /sdcard inside guest",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                if (mountStorage && !hasStoragePermission) {
                                    Text(
                                        text = "Files Access Required (Tap to Grant)",
                                        color = Color(0xFFFF3333),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        modifier = Modifier.clickable { requestAllFilesAccess(context) }
                                    )
                                }
                            }
                        }
                        androidx.compose.material3.Switch(
                            checked = mountStorage,
                            onCheckedChange = { checked ->
                                if (checked && !hasStoragePermission) {
                                    requestAllFilesAccess(context)
                                    // Nepovolit mount dokud uživatel nepotvrdí oprávnění
                                    return@Switch
                                }
                                mountStorage = checked
                                sharedPrefs.edit().putBoolean("mount_storage", checked).apply()
                            },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00FF41),
                                checkedTrackColor = Color(0x6600FF41),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color(0xFF1E2026)
                            )
                        )
                    }
                }

                // Background boot — cron automation (auto-start after reboot)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val nextState = !bootAutostart
                            bootAutostart = nextState
                            sharedPrefs.edit().putBoolean("boot_autostart", nextState).apply()
                        }
                        .padding(0.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF333333)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xEE0C0E14))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0x1A00FF41), CircleShape)
                                    .border(1.dp, Color(0x3300FF41), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Boot",
                                    tint = Color(0xFF00FF41),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Auto-start po restartu",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                Text(
                                    text = "Spustí proot na pozadí (cron) po bootu zařízení",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                        androidx.compose.material3.Switch(
                            checked = bootAutostart,
                            onCheckedChange = { checked ->
                                bootAutostart = checked
                                sharedPrefs.edit().putBoolean("boot_autostart", checked).apply()
                            },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00FF41),
                                checkedTrackColor = Color(0x6600FF41),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color(0xFF1E2026)
                            )
                        )
                    }
                }

                // Action Flow — isDownloading has HIGHEST priority
                if (isDownloading) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF00FF41)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xEE0C0E14))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Downloading",
                                    tint = Color(0xFF00FF41),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = statusText,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .background(Color(0xFF1E2026), RoundedCornerShape(3.dp))
                            ) {
                                val fraction = if (downloadProgress > 0) downloadProgress / 100f else 0f
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fraction)
                                        .background(
                                            Brush.horizontalGradient(listOf(Color(0xFF00FF41), Color(0xFF00D2FF))),
                                            RoundedCornerShape(3.dp)
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (downloadProgress >= 0) "$downloadProgress%" else "…",
                                color = Color(0xFF00FF41),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    downloadJob?.cancel()
                                    isDownloading = false
                                    statusText = ""
                                    downloadProgress = 0
                                    isExtracted = RootfsManager.isRootfsExtracted(context, selectedDistro)
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8F0011)),
                                modifier = Modifier.fillMaxWidth().height(42.dp)
                            ) {
                                Text("ABORT OPERATION", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                        }
                    }
                } else {
                    val canLaunchDocker = isDockerMode && selectedDockerDir != null
                    val canLaunchDistro = !isDockerMode && isExtracted
                    if (canLaunchDocker || canLaunchDistro) {
                        val activeBrandGradient = if (canLaunchDocker) {
                            Brush.horizontalGradient(listOf(Color(0xFF00FF41), Color(0xFF00D2FF)))
                        } else if (selectedDistro.id == "kali") {
                            Brush.horizontalGradient(listOf(Color(0xFF0052D4), Color(0xFF4364F7)))
                        } else {
                            Brush.horizontalGradient(listOf(Color(0xFF11998E), Color(0xFF38EF7D)))
                        }

                        Button(
                            onClick = {
                                val rootfsDirName = if (canLaunchDocker) {
                                    selectedDockerDir!!
                                } else {
                                    selectedDistro.rootfsDirName
                                }
                                val intent = Intent(context, TerminalActivity::class.java).apply {
                                    putExtra("rootfsDirName", rootfsDirName)
                                    putExtra("mountStorage", mountStorage)
                                    // Docker image: přeskočí bootstrap/entrypoint v ProotManager
                                    if (canLaunchDocker) {
                                        putExtra("isDockerImage", true)
                                    }
                                }
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .background(activeBrandGradient, RoundedCornerShape(10.dp))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Launch",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (canLaunchDocker) {
                                        "BOOT UP " + (dockerImageRef?.fullName ?: "DOCKER").uppercase()
                                    } else {
                                        "BOOT UP " + selectedDistro.name.uppercase()
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                if (isDockerMode) {
                                    // Switch to Docker input and prompt user to enter image
                                    isDockerMode = true
                                    Toast.makeText(context, "Enter Docker Hub image reference above", Toast.LENGTH_SHORT).show()
                                } else {
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
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .background(Brush.horizontalGradient(listOf(Color(0xFF008F11), Color(0xFF00FF41))), RoundedCornerShape(10.dp))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Install",
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isDockerMode) {
                                        "PULL DOCKER IMAGE"
                                    } else {
                                        "DEPLOY " + selectedDistro.name.uppercase()
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.Black,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Collapsible Advanced Guest Management Tools
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (isMoreMenuExpanded) Color(0xFF00FF41) else Color(0xFF1E2026)),
                        colors = CardDefaults.cardColors(containerColor = Color(0x990B0D13))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isMoreMenuExpanded = !isMoreMenuExpanded }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Settings",
                                        tint = if (isMoreMenuExpanded) Color(0xFF00FF41) else Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "ADVANCED GUEST MANAGEMENT",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isMoreMenuExpanded) Color.White else Color.Gray,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Icon(
                                    imageVector = if (isMoreMenuExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Expand",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (isMoreMenuExpanded) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp)
                                        .height(1.dp)
                                        .background(Color(0xFF1E2026))
                                )
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    val canManageDocker = isDockerMode && selectedDockerDir != null
                                    val canManageDistro = !isDockerMode && isExtracted
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        if (canManageDocker || canManageDistro) {
                                            Button(
                                                onClick = {
                                                    if (!hasStoragePermission) {
                                                        requestAllFilesAccess(context)
                                                    } else {
                                                        isMoreMenuExpanded = false
                                                        if (canManageDocker) {
                                                            com.linux_core.core.BackupService.startBackup(context, selectedDockerDir!!)
                                                        } else {
                                                            com.linux_core.core.BackupService.startBackup(context, selectedDistro.id)
                                                        }
                                                        Toast.makeText(context, "Backup started. Check notification for progress.", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161B22)),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(40.dp)
                                                    .border(1.dp, Color(0xFF30363D), RoundedCornerShape(6.dp))
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.Backup, contentDescription = "Backup", tint = Color.LightGray, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("BACKUP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.LightGray, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                                }
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                restorePicker.launch(arrayOf("*/*"))
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161B22)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(40.dp)
                                                .border(1.dp, Color(0xFF30363D), RoundedCornerShape(6.dp))
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.FolderOpen, contentDescription = "Restore", tint = Color.LightGray, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("RESTORE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.LightGray, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                            }
                                        }
                                    }

                                    if (canManageDocker || canManageDistro) {
                                        val isDocker = canManageDocker
                                        Button(
                                            onClick = {
                                                if (isDocker) {
                                                    // Remove Docker image
                                                    scope.launch {
                                                        try {
                                                            val dockerDir = File(context.filesDir, selectedDockerDir!!)
                                                            if (dockerDir.exists()) dockerDir.deleteRecursively()
                                                            selectedDockerDir = null
                                                            dockerImageRef = null
                                                            isDockerMode = false
                                                            Toast.makeText(context, "Docker image removed", Toast.LENGTH_SHORT).show()
                                                        } catch (e: Exception) {
                                                            Toast.makeText(context, "Remove failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                } else {
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
                                                }
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF3333)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(40.dp)
                                                .border(1.dp, Color(0x66FF3333), RoundedCornerShape(6.dp))
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    if (isDocker) Icons.Default.Cloud else Icons.Default.Refresh,
                                                    contentDescription = if (isDocker) "Remove" else "Reinstall",
                                                    tint = Color(0xFFFF5555),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    if (isDocker) "REMOVE DOCKER IMAGE" else "REINSTALL (DESTROY & REBUILD)",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFFF5555),
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isExtracted) {
                        // Custom 1x1 Script Launchers Card
                        var customCommandText by remember { mutableStateOf("") }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0x3300FF41)),
                            colors = CardDefaults.cardColors(containerColor = Color(0x660B0D13))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Android,
                                        contentDescription = "Android Shortcut",
                                        tint = Color(0xFF00FF41),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "1x1 WIDGET SHORTCUTS",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00FF41),
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Text(
                                    text = "Pin launch triggers directly to Android home screen",
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                androidx.compose.material3.OutlinedTextField(
                                    value = customCommandText,
                                    onValueChange = { customCommandText = it },
                                    placeholder = { Text("e.g. systemctl start nginx", color = Color.DarkGray, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
                                    singleLine = true,
                                    leadingIcon = { Text("$", color = Color(0xFF00FF41), fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp)) },
                                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF00FF41),
                                        unfocusedBorderColor = Color(0xFF1E2026),
                                        focusedContainerColor = Color(0xFF07080A),
                                        unfocusedContainerColor = Color(0xFF07080A)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val cmd = customCommandText.trim().ifEmpty { null }
                                            com.linux_core.core.ShortcutHelper.pinShortcut(context, "kali", cmd, mountStorage)
                                            Toast.makeText(context, "Requested Kali shortcut!", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161B22)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(6.dp))
                                    ) {
                                        Text("PIN KALI 1x1", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00FF41), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                    }

                                    Button(
                                        onClick = {
                                            val cmd = customCommandText.trim().ifEmpty { null }
                                            com.linux_core.core.ShortcutHelper.pinShortcut(context, "parrot", cmd, mountStorage)
                                            Toast.makeText(context, "Requested Parrot shortcut!", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161B22)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(6.dp))
                                    ) {
                                        Text("PIN PARROT 1x1", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00FF41), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                    }
                                }
                            }
                        }
            } // end if(isExtracted) widget
            } // end else
            } // end Column (scrollable)
            } // end if(currentTab == "home")
            if (currentTab == "editor") {
                com.linux_core.ui.editor.EditorTab()
            } else if (currentTab == "vpn_center") {
                VpnCenterScreen(modifier = Modifier.weight(1f))
            } else if (currentTab == "root") {
                com.linux_core.ui.RootBridgeTab(modifier = Modifier.weight(1f))
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
                        fontSize = 15.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
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
                                fontSize = 13.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
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
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                maxLines = 1
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "$dateStr | ${String.format("%.1f MB", sizeMb)}",
                                                color = Color.Gray,
                                                fontSize = 10.sp,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "RESTORE",
                                            color = Color(0xFF00FF41),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
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

        // Active Session Shortcut Button (Hamburger)
        if (activeSessionCount > 0) {
            IconButton(
                onClick = {
                    val intent = Intent(context, TerminalActivity::class.java)
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Active Terminal",
                    tint = Color(0xFF00FF41)
                )
            }
        }
    }
}
