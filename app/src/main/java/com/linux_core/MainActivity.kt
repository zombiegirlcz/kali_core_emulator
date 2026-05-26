package com.linux_core

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linux_core.core.Distro
import com.linux_core.core.RootfsManager
import com.linux_core.ui.terminal.TerminalActivity
import com.linux_core.ui.theme.NethunteraioperatorTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

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
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                modifier = Modifier.padding(bottom = 24.dp).clickable(enabled = !isDownloading) { mountStorage = !mountStorage }
            ) {
                androidx.compose.material3.Switch(
                    checked = mountStorage,
                    onCheckedChange = { mountStorage = it },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00FF41),
                        checkedTrackColor = Color(0x8800FF41)
                    )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Mount /sdcard Storage",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
            }

            // Action Flow
            if (isExtracted) {
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

                // Premium Reinstall button
                Button(
                    onClick = {
                        downloadJob = scope.launch {
                            isDownloading = true
                            try {
                                statusText = "Reinstalling: Deleting old files…"
                                RootfsManager.deleteRootfs(context, selectedDistro)
                                isExtracted = false
                                downloadProgress = 0

                                statusText = "Reinstalling: Downloading rootfs…"
                                RootfsManager.downloadRootfs(context, selectedDistro).collect { progress ->
                                    downloadProgress = progress
                                }

                                downloadProgress = 0
                                statusText = "Reinstalling: Extracting filesystem…"
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
                                    "Reinstall failed: " + (e.message ?: "Unknown error"),
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                isDownloading = false
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xCC1E2026) // Sleek dark grey button, slightly transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = "REINSTALL " + selectedDistro.name.uppercase(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF00FF41) // Accented green text
                    )
                }
            } else {
                if (isDownloading) {
                    Text(
                        text = statusText,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    if (downloadProgress in 1..99) {
                        Text(
                            text = "$downloadProgress%",
                            color = Color(0xFF00FF41),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else if (downloadProgress == -1) {
                        Text(
                            text = "Processing…",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            downloadJob?.cancel()
                            isDownloading = false
                            statusText = ""
                            downloadProgress = 0
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF12141C)
                        )
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                } else {
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
            }
        }
    }
}