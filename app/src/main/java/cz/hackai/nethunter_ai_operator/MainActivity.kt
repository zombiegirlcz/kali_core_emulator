package cz.hackai.nethunter_ai_operator

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
                    color = MaterialTheme.colorScheme.background
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
    var isExtracted by remember { mutableStateOf(RootfsManager.isRootfsExtracted(context)) }
    var downloadProgress by remember { mutableStateOf(0) }
    var isDownloading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    // Cancel download job on composition disposal (e.g., Activity recreation)
    DisposableEffect(Unit) {
        onDispose { downloadJob?.cancel() }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isExtracted) {
            Button(onClick = {
                context.startActivity(Intent(context, TerminalActivity::class.java))
            }) {
                Text(context.getString(R.string.launch_button))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    scope.launch {
                        try {
                            statusText = "Deleting rootfs…"
                            RootfsManager.deleteRootfs(context)
                            isExtracted = false
                            downloadProgress = 0
                            statusText = ""
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.error_prefix) + (e.message
                                    ?: "Unknown error"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(context.getString(R.string.redownload_button))
            }
        } else {
            if (isDownloading) {
                Text(statusText)
                if (downloadProgress in 1..99) {
                    Text("$downloadProgress%")
                } else if (downloadProgress == -1) {
                    Text(context.getString(R.string.downloading_indeterminate))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Button(onClick = {
                        downloadJob?.cancel()
                        isDownloading = false
                        statusText = ""
                        downloadProgress = 0
                    }) {
                        Text(context.getString(R.string.cancel_button))
                    }
                }
            } else {
                Button(onClick = {
                    downloadJob = scope.launch {
                        isDownloading = true
                        try {
                            // Step 1: Download rootfs (no-op if already downloaded)
                            statusText = context.getString(R.string.downloading_status)
                            RootfsManager.downloadRootfs(context).collect { progress ->
                                downloadProgress = progress
                            }

                            // Step 2: Extract rootfs
                            downloadProgress = 0
                            statusText = context.getString(R.string.extracting_status)
                            RootfsManager.extractRootfs(context).collect { progress ->
                                downloadProgress = progress
                            }

                            // Step 3: Mark as extracted -> Launch button appears
                            isExtracted = true
                            statusText = ""
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Coroutine cancelled by user — clean up silently
                            downloadProgress = 0
                            statusText = ""
                            throw e // Re-throw to propagate cancellation
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.error_prefix) + (e.message
                                    ?: "Unknown error"),
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            isDownloading = false
                        }
                    }
                }) {
                    Text(context.getString(R.string.download_button))
                }
            }
        }
    }
}
