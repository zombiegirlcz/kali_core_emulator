package cz.hackai.nethunter_ai_operator

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.hackai.nethunter_ai_operator.core.Distro
import cz.hackai.nethunter_ai_operator.core.RootfsManager
import cz.hackai.nethunter_ai_operator.ui.terminal.TerminalActivity
import cz.hackai.nethunter_ai_operator.ui.theme.NethunteraioperatorTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
fun MainScreen() {
    val context = LocalContext.current
    var selectedDistro by remember { mutableStateOf(RootfsManager.DISTROS[0]) }
    var isExtracted by remember(selectedDistro) { mutableStateOf(RootfsManager.isRootfsExtracted(context, selectedDistro)) }
    var downloadProgress by remember { mutableStateOf(0) }
    var isDownloading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { downloadJob?.cancel() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "NETHUNTER AI OPERATOR",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE50914), // Premium Crimson Red
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Select active guest environment",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Distro Selector Cards
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 40.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RootfsManager.DISTROS.forEach { distro ->
                val isSelected = (distro == selectedDistro)
                val cardBorder = if (isSelected) {
                    BorderStroke(2.dp, if (distro.id == "kali") Color(0xFFE50914) else Color(0xFF00FFCC))
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
                        containerColor = if (isSelected) Color(0xFF12141C) else Color(0xFF0B0D13)
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

        // Action Flow
        if (isExtracted) {
            Button(
                onClick = {
                    val intent = Intent(context, TerminalActivity::class.java).apply {
                        putExtra("rootfsDirName", selectedDistro.rootfsDirName)
                    }
                    context.startActivity(intent)
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedDistro.id == "kali") Color(0xFFE50914) else Color(0xFF00B386)
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
                    containerColor = Color(0xFF1E2026) // Sleek dark grey button
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = "REINSTALL " + selectedDistro.name.uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFFE50914) // Accented red text
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
                        color = if (selectedDistro.id == "kali") Color(0xFFE50914) else Color(0xFF00FFCC),
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
                        containerColor = if (selectedDistro.id == "kali") Color(0xFFE50914) else Color(0xFF00B386)
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
