package com.linux_core.ui.docker

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linux_core.core.RootfsManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DockerPullScreen(
    onImageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var customImage by remember { mutableStateOf("") }
    var isPulling by remember { mutableStateOf(false) }
    var pullProgress by remember { mutableStateOf(0) }
    var pullStatus by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Scan installed docker images on open
    var installedImages by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val filesDir = context.filesDir
        val dockerDir = java.io.File(filesDir, "nh/distro/docker")
        installedImages = if (dockerDir.isDirectory) {
            dockerDir.listFiles { f -> f.isDirectory }?.map { "nh/distro/docker/${it.name}" } ?: emptyList()
        } else emptyList()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.85f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x3300FF41)),
        colors = CardDefaults.cardColors(containerColor = Color(0xF20B0D13))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header + close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DOCKER / ROOTFS IMAGES",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00FF41),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Row {
                    IconButton(onClick = {
                        val filesDir = context.filesDir
                        val dockerDir = java.io.File(filesDir, "nh/distro/docker")
                        installedImages = if (dockerDir.isDirectory) {
                            dockerDir.listFiles { f -> f.isDirectory }?.map { "nh/distro/docker/${it.name}" } ?: emptyList()
                        } else emptyList()
                    }) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = Color.Gray)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = Color.Gray)
                    }
                }
            }

            // Installed images list
            Text(
                text = "INSTALLED IMAGES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.LightGray,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            if (installedImages.isEmpty()) {
                Text(
                    text = "No installed images yet — pull one below.",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    installedImages.forEach { dir ->
                        val isSelected = dir == onImageSelected.toString()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0x2600FF41) else Color(0xFF0D0E12))
                                .border(1.dp, if (isSelected) Color(0xFF00FF41) else Color(0xFF1E2026), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable { onImageSelected(dir) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isSelected) "\u25CF" else "\u25CB",
                                color = if (isSelected) Color(0xFF00FF41) else Color.Gray,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = dir.substringAfterLast("/"),
                                fontSize = 11.sp,
                                color = Color.White,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Pull form
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
                text = "Enter a Docker Hub image reference (e.g. kali/security, myuser/app:v1.0, alpine@sha256:digest) or an https:// URL to a rootfs archive (.tar.gz / .tar.xz / .tar.bz2 / .tar)",
                fontSize = 10.sp,
                color = Color.Gray,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customImage,
                    onValueChange = { customImage = it },
                    placeholder = { Text("kali/security:latest | https://…rootfs.tar.xz", color = Color.DarkGray, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00FF41),
                        unfocusedBorderColor = Color(0xFF1E2026),
                        focusedContainerColor = Color(0xFF07080A),
                        unfocusedContainerColor = Color(0xFF07080A)
                    ),
                    modifier = Modifier.weight(1f),
                    enabled = !isPulling
                )

                Button(
                    onClick = {
                        if (customImage.isBlank()) {
                            Toast.makeText(context, "Enter a Docker image reference", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            isPulling = true
                            errorMessage = null
                            pullProgress = 0
                            val rawInput = customImage.trim()
                            val isWebUrl = rawInput.startsWith("http://") || rawInput.startsWith("https://")
                            val cleanFilePath = rawInput.removePrefix("file://")
                            val localFile = java.io.File(cleanFilePath)
                            val isLocalFile = rawInput.startsWith("file://") ||
                                (rawInput.startsWith("/") && localFile.exists() && localFile.isFile)

                            try {
                                val resultFile = when {
                                    isLocalFile -> {
                                        pullStatus = "Importing local archive…"
                                        var lastFile: File? = null
                                        RootfsManager.importLocalRootfsFile(context, localFile).collect { (p, status) ->
                                            pullProgress = p
                                            pullStatus = status
                                            if (p >= 100 && status.isNotEmpty() && java.io.File(status).exists()) {
                                                lastFile = java.io.File(status)
                                            }
                                        }
                                        lastFile
                                    }
                                    isWebUrl -> {
                                        pullStatus = "Pulling rootfs from URL…"
                                        var lastFile: File? = null
                                        RootfsManager.pullRootfsFromUrl(context, rawInput).collect { (p, status) ->
                                            pullProgress = p
                                            pullStatus = status
                                            if (p >= 100 && status.isNotEmpty() && java.io.File(status).exists()) {
                                                lastFile = java.io.File(status)
                                            }
                                        }
                                        lastFile
                                    }
                                    else -> {
                                        val ref = com.linux_core.core.DockerImageRef.parse(rawInput)
                                        pullStatus = "Pulling ${ref.fullName}:${ref.tag}…"
                                        var lastFile: File? = null
                                        RootfsManager.pullDockerImage(context, ref).collect { (p, status) ->
                                            pullProgress = p
                                            pullStatus = status
                                            if (p >= 100 && status.isNotEmpty() && java.io.File(status).exists()) {
                                                lastFile = java.io.File(status)
                                            }
                                        }
                                        lastFile
                                    }
                                }

                                if (resultFile != null) {
                                    onImageSelected(resultFile.absolutePath)
                                    Toast.makeText(context, "Image ready! You can boot it from DOCKER HUB tab.", Toast.LENGTH_LONG).show()
                                } else {
                                    errorMessage = "Pull finished but result path missing"
                                }
                            } catch (e: Exception) {
                                errorMessage = e.message ?: "Pull failed"
                            } finally {
                                isPulling = false
                                pullProgress = 0
                                pullStatus = ""
                                // Refresh installed list
                                val filesDir = context.filesDir
                                val dockerDir = java.io.File(filesDir, "nh/distro/docker")
                                installedImages = if (dockerDir.isDirectory) {
                                    dockerDir.listFiles { f -> f.isDirectory }?.map { "nh/distro/docker/${it.name}" } ?: emptyList()
                                } else emptyList()
                            }
                        }
                    },
                    enabled = !isPulling,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF41))
                ) {
                    if (isPulling) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                    } else {
                        Text("PULL", color = Color.Black, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }

            if (pullProgress in 1..99) {
                Text(
                    text = "$pullProgress% — $pullStatus",
                    fontSize = 10.sp,
                    color = Color.Yellow,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }

            errorMessage?.let { msg ->
                Text(
                    text = "ERROR: $msg",
                    fontSize = 10.sp,
                    color = Color.Red,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }
}
