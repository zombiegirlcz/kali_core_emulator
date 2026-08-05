package com.linux_core.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
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
import java.io.File

object RootBridgeManager {
    private const val TAG = "RootBridgeManager"
    private const val PREFS_NAME = "root_settings"
    private const val PID_FILE = "ipc/su_daemon.pid"
    private const val SOCKET_FILE = "ipc/magisk_daemon.sock"

    private val SU_PATHS = listOf(
        "/product/bin/su",
        "/system/xbin/su",
        "/system/bin/su",
        "/data/adb/ksu/bin/su",
        "/apex/com.android.runtime/bin/su",
        "/system/sd/xbin/su",
        "/vendor/bin/su",
        "/sbin/su"
    )

    /**
     * Locate the active PRoot distro launcher script (launcher-<distro>.sh).
     * su_daemon RE-ENTERS this launcher under real root so every sudo/su
     * command stays confined to the guest rootfs by PRoot.
     */
    fun detectActiveLauncher(context: Context): String? {
        val filesDir = context.filesDir ?: return null
        val candidates = filesDir.listFiles()?.filter {
            it.isFile && it.name.startsWith("launcher-") && it.name.endsWith(".sh")
        } ?: return null
        // Prefer the most recently generated launcher (matches the active rootfs).
        return candidates.maxByOrNull { it.lastModified() }?.absolutePath
    }

    /**
     * Find the PRoot guest rootfs inside the app files dir. A rootfs has
     * etc/passwd and usr/bin; prefer the most recently modified candidate.
     * Passed to su_daemon so the automatic ownership fix knows where to chown.
     */
    fun detectGuestRootfs(context: Context): String? {
        val filesDir = context.filesDir
        val candidates = filesDir.listFiles()?.filter { it.isDirectory } ?: return null
        val rootfs = candidates.filter { dir ->
            dir.name.endsWith("-arm64") ||
                (File(dir, "etc/passwd").exists() && File(dir, "usr/bin").isDirectory)
        }
        return rootfs.maxByOrNull { it.lastModified() }?.absolutePath
    }

    fun checkSuAvailable(): Pair<Boolean, String?> {
        for (path in SU_PATHS) {
            val file = File(path)
            if (file.exists()) {
                return Pair(true, path)
            }
        }
        // Fallback: check which su
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() == 0 && out.isNotBlank()) {
                Pair(true, out)
            } else {
                Pair(false, null)
            }
        } catch (e: Exception) {
            Pair(false, null)
        }
    }

    fun isDaemonRunning(context: Context): Pair<Boolean, Int?> {
        val pidFile = File(context.filesDir, PID_FILE)
        if (pidFile.exists()) {
            val pid = try { pidFile.readText().trim().toInt() } catch (e: Exception) { null }
            if (pid != null && isProcessAlive(pid)) {
                return Pair(true, pid)
            } else {
                pidFile.delete()
            }
        }
        // Socket exists = daemon is (or was) listening
        if (File(context.filesDir, SOCKET_FILE).exists()) {
            return Pair(true, null)
        }
        // Check ps
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -ef 2>/dev/null | grep '[s]u_daemon'"))
            val out = proc.inputStream.bufferedReader().readText()
            if (out.isNotBlank()) {
                val m = Regex("""\b(\d+)\b""").find(out)
                val pid = m?.groupValues?.get(1)?.toIntOrNull()
                Pair(true, pid)
            } else {
                Pair(false, null)
            }
        } catch (e: Exception) {
            Pair(false, null)
        }
    }

    private fun isProcessAlive(pid: Int): Boolean {
        return try {
            File("/proc/$pid").exists()
        } catch (e: Exception) { false }
    }

    fun startDaemon(context: Context, callback: (Boolean) -> Unit) {
        Thread {
            try {
                val prefs = context.getSharedPreferences("root_settings", Context.MODE_PRIVATE)
                val (suOk, suPath) = checkSuAvailable()
                val suBin = if (suOk && suPath != null) suPath else "su"

                val ipcDir = File(context.filesDir, "ipc")
                if (!ipcDir.exists()) ipcDir.mkdirs()

                val daemonBin = File(context.filesDir, "su_daemon")
                if (!daemonBin.exists()) {
                    // Try to deploy from assets
                    context.assets.open("su_daemon").use { input ->
                        daemonBin.outputStream().use { output -> input.copyTo(output) }
                    }
                    daemonBin.setExecutable(true, false)
                }

                val sockFile = File(ipcDir, "magisk_daemon.sock")
                val logFile = File(ipcDir, "su_daemon.log")
                val pidFile = File(context.filesDir, PID_FILE)

                // Kill stale daemon instances first (previous runs may linger)
                try {
                    Runtime.getRuntime().exec(
                        arrayOf("sh", "-c", "$suBin -c 'pkill -f su_daemon' 2>/dev/null; true")
                    ).waitFor()
                } catch (e: Exception) { /* ignore */ }

                // Remove stale socket & pid files so the new daemon binds fresh
                sockFile.delete()
                pidFile.delete()

                // Start daemon via su.
                // argv[2] = guest PRoot launcher path (su_daemon RE-ENTERS this
                // launcher under real root so commands run INSIDE the guest sandbox)
                // argv[3] = guest rootfs dir (host path) for the ownership fix
                // argv[4]/[5] = app uid/gid — real-root commands create root-owned
                //   files; the auto-fix rewrites them back to this uid/gid
                // argv[6] = auto-fix on/off
                val launcherPath = detectActiveLauncher(context)
                if (launcherPath == null) {
                    Log.e(TAG, "No launcher-*.sh found — su_daemon will refuse to run (fail closed)")
                }
                val launcherArg = launcherPath?.let { " '$it'" } ?: ""
                val rootfs = detectGuestRootfs(context)
                val rootfsArg = rootfs?.let { " '$it'" } ?: ""
                val appUid = android.os.Process.myUid()
                val autoFix = if (prefs.getBoolean("auto_fix_permissions", true)) "1" else "0"
                val cmd = "$suBin -c '${daemonBin.absolutePath} ${sockFile.absolutePath}$launcherArg$rootfsArg $appUid $appUid $autoFix > ${logFile.absolutePath} 2>&1 &'"
                Log.i(TAG, "Starting su_daemon via $suBin (launcher=$launcherPath, rootfs=$rootfs, uid=$appUid, autoFix=$autoFix): $cmd")

                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                proc.waitFor()
                Thread.sleep(1200)

                val (running, pid) = isDaemonRunning(context)
                if (running && pid != null) {
                    pidFile.writeText(pid.toString())
                }

                Log.i(TAG, "startDaemon result: running=$running pid=$pid")
                if (!running) {
                    val log = if (logFile.exists()) logFile.readText().take(2000) else "(no log)"
                    Log.e(TAG, "su_daemon log: $log")
                }

                android.os.Handler(context.mainLooper).post { callback(running) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start su_daemon: ${e.message}")
                android.os.Handler(context.mainLooper).post { callback(false) }
            }
        }.start()
    }

    fun stopDaemon(context: Context): Boolean {
        return try {
            val (suOk, suPath) = checkSuAvailable()
            val suBin = if (suOk && suPath != null) suPath else "su"
            // Kill ALL su_daemon instances (not just the recorded pid)
            Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "$suBin -c 'pkill -f su_daemon' 2>/dev/null; true")
            ).waitFor()
            File(context.filesDir, PID_FILE).delete()
            File(context.filesDir, SOCKET_FILE).delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop su_daemon: ${e.message}")
            false
        }
    }
}

@Composable
fun RootBridgeTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("root_settings", Context.MODE_PRIVATE) }

    var suDetected by remember { mutableStateOf(false) }
    var suPath by remember { mutableStateOf<String?>(null) }
    var daemonRunning by remember { mutableStateOf(false) }
    var daemonPid by remember { mutableStateOf<Int?>(null) }
    var isStarting by remember { mutableStateOf(false) }

    // Bind mount toggles
    var bindSystem by remember { mutableStateOf(prefs.getBoolean("bind_system", true)) }
    var bindVendor by remember { mutableStateOf(prefs.getBoolean("bind_vendor", false)) }
    var bindTmp by remember { mutableStateOf(prefs.getBoolean("bind_tmp", false)) }
    var bindUsb by remember { mutableStateOf(prefs.getBoolean("bind_usb", true)) }

    // Auto-fix ownership after sudo commands (layer 1)
    var autoFixPermissions by remember { mutableStateOf(prefs.getBoolean("auto_fix_permissions", true)) }

    fun refreshStatus() {
        val (suOk, path) = RootBridgeManager.checkSuAvailable()
        suDetected = suOk
        suPath = path

        val (running, pid) = RootBridgeManager.isDaemonRunning(context)
        daemonRunning = running
        daemonPid = pid
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(scrollState)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = "Root Bridge",
                tint = Color(0xFF00FF41),
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "⚡ ROOT BRIDGE // MAGISK",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00FF41),
                fontFamily = FontFamily.Monospace
            )
        }

        // Su Status Banner / Warning
        if (!suDetected) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFFFF5252)),
                colors = CardDefaults.cardColors(containerColor = Color(0x228F0011))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Magisk su nebylo nalezeno — root funkce nedostupné",
                        color = Color(0xFFFF8888),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF00FF41)),
                colors = CardDefaults.cardColors(containerColor = Color(0x1500FF41))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Found",
                        tint = Color(0xFF00FF41),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Detekováno: ${suPath ?: "/product/bin/su"} ✓",
                        color = Color(0xFF88FF88),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Magisk Daemon Controls Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF333333)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Magisk démon",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (daemonRunning) "● BĚŽÍ • PID ${daemonPid ?: "?"}" else "○ Vypnuto",
                            color = if (daemonRunning) Color(0xFF00FF41) else Color.Gray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Switch(
                        checked = daemonRunning,
                        enabled = suDetected && !isStarting,
                        onCheckedChange = { enable ->
                            if (enable) {
                                isStarting = true
                                RootBridgeManager.startDaemon(context) { ok ->
                                    isStarting = false
                                    refreshStatus()
                                }
                            } else {
                                RootBridgeManager.stopDaemon(context)
                                refreshStatus()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color(0xFF00FF41),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF222222)
                        )
                    )
                }

                // Auto-fix permissions toggle (layer 1): po každém příkazu spuštěném
                // přes daemon se vlastnictví rootem vytvořených souborů přepíše zpět
                // na UID aplikace (host-side, mimo PRoot — bindy se přeskočí).
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF2A2A2A))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-fix vlastnictví",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Po sudo příkazu přepíše root-owned soubory zpět na UID aplikace\nManuálně: nh fix permission <cesta>",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = autoFixPermissions,
                        enabled = true,
                        onCheckedChange = { checked ->
                            autoFixPermissions = checked
                            prefs.edit().putBoolean("auto_fix_permissions", checked).apply()
                            // Projeví se při příštím startu daemona.
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color(0xFF00FF41),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF222222)
                        )
                    )
                }
            }
        }

        // PRoot Bind Mounts Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF333333)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "PRoot bind mounty (projeví se při příštím startu)",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Checkbox items under /mnt/
                val items = listOf(
                    Triple("System", "/system → /mnt/system", "bind_system" to bindSystem),
                    Triple("Vendor", "/vendor → /mnt/vendor", "bind_vendor" to bindVendor),
                    Triple("Local TMP", "/data/local/tmp → /mnt/tmp", "bind_tmp" to bindTmp),
                    Triple("USB Devices", "/dev/bus/usb → /mnt/usb", "bind_usb" to bindUsb)
                )

                items.forEach { (label, mountPath, statePair) ->
                    val (prefKey, stateValue) = statePair
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = stateValue,
                            onCheckedChange = { checked ->
                                prefs.edit().putBoolean(prefKey, checked).apply()
                                when (prefKey) {
                                    "bind_system" -> bindSystem = checked
                                    "bind_vendor" -> bindVendor = checked
                                    "bind_tmp" -> bindTmp = checked
                                    "bind_usb" -> bindUsb = checked
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF00FF41),
                                checkmarkColor = Color.Black,
                                uncheckedColor = Color.Gray
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = label,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = mountPath,
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = Color(0xFFFFB000),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "⚠ Všechny hostitelské mounty jsou pod /mnt/",
                        color = Color(0xFFFFB000),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
