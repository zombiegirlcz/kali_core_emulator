package com.linux_core.ui

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
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
import androidx.compose.material.icons.filled.List
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
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        // 1) Unix socket connect test — jediné spolehlivé ověření "daemon teď naslouchá".
        //    File.exists() nestačí (stale socket bez listeneru projde), /proc/PID selhává,
        //    protože root-owned proces je pro app (uid u0_a333) neviditelný.
        val sock = socketAlive(File(context.filesDir, SOCKET_FILE))
        if (sock) {
            // PID z pid file (čistě informativně; může chybět, pokud ho UI dřív smazalo)
            val pid = try {
                File(context.filesDir, PID_FILE).readText().trim().toIntOrNull()
            } catch (e: Exception) { null }
            return Pair(true, pid)
        }
        // 2) Fallback: pid file + ps přes su (root vidí root procesy)
        val pidFile = File(context.filesDir, PID_FILE)
        if (pidFile.exists()) {
            val pid = try { pidFile.readText().trim().toIntOrNull() } catch (e: Exception) { null }
            if (pid != null && isProcessAlive(pid)) {
                return Pair(true, pid)
            } else {
                pidFile.delete()
            }
        }
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "ps -A -o pid,cmd 2>/dev/null | grep '[s]u_daemon'"))
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

    /** Connect test na UNIX socket — connect selže na stale socketu (ECONNREFUSED),
     *  uspěje jen na živém naslouchajícím daemonu. */
    private fun socketAlive(socketFile: File): Boolean {
        if (!socketFile.exists()) return false
        return try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            s.close()
            true
        } catch (e: Exception) {
            false
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
                // NOTE: pkill -x (exact comm match), NOT -f — -f matchuje command
                // line a tento su-shell sám obsahuje "su_daemon" → self-kill.
                try {
                    Runtime.getRuntime().exec(
                        arrayOf("sh", "-c", "$suBin -c 'pkill -x su_daemon' 2>/dev/null; true")
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
                Log.i(TAG, "startDaemon: launcher=$launcherPath rootfs=$rootfs")
                if (launcherPath == null || rootfs == null) {
                    Log.e(TAG, "startDaemon aborted: launcher/rootfs missing (fail-closed) — toggle OFF a znovu ON nebo zkontroluj rootfs")
                    android.os.Handler(context.mainLooper).post { callback(false) }
                    return@Thread
                }
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
            // NOTE: pkill -x (exact comm), NOT -f — -f by zabil i vlastní su-shell.
            Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "$suBin -c 'pkill -x su_daemon' 2>/dev/null; true")
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

/**
 * Magisk module manager — install/remove Magisk modules from the Root Bridge UI.
 *
 * Runs through the real Magisk CLI (su):
 *   magisk --install-module <zip>
 *   magisk --remove-modules [-n]      (-n = keep module data)
 * `magisk` binary is probed at well-known locations, preferring /product/bin
 * (the user's device layout — AGENTS.md / launcher docs).
 */
object MagiskModuleManager {
    private const val TAG = "MagiskModuleManager"

    // Probe order: user's device has it under /product/bin (same dir as su).
    private val MAGISK_PATHS = listOf(
        "/product/bin/magisk",
        "/system/bin/magisk",
        "/system/xbin/magisk",
        "/sbin/magisk",
        "/data/adb/magisk/magisk",
        "/apex/com.android.runtime/bin/magisk"
    )

    data class MagiskModule(val id: String, val name: String, val version: String)
    data class ModuleZip(val path: String, val size: Long, val name: String)

    /** Locate the magisk CLI binary. */
    fun findMagisk(): String? {
        for (p in MAGISK_PATHS) {
            if (File(p).exists()) return p
        }
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("which", "magisk"))
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() == 0 && out.isNotBlank()) out else null
        } catch (e: Exception) {
            null
        }
    }

    /** su -c wrapper capturing stdout+stderr; returns (exitCode, output). */
    fun suExec(cmd: String): Pair<Int, String> {
        val (suOk, suPath) = RootBridgeManager.checkSuAvailable()
        val suBin = if (suOk && suPath != null) suPath else "su"
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$suBin -c '$cmd' 2>&1"))
            val out = proc.inputStream.bufferedReader().readText()
            val rc = proc.waitFor()
            rc to out.trim()
        } catch (e: Exception) {
            Log.e(TAG, "suExec failed: ${e.message}")
            -1 to "ERR: ${e.message}"
        }
    }

    /** List installed modules: `magisk --list-modules` and read module.prop for name/version. */
    fun listInstalled(): List<MagiskModule> {
        val magisk = findMagisk()
        // Module dir names ARE the module ids; comment/disabled modules have
        // a `disable` marker — include them (visible in the list).
        var ids: List<String> = emptyList()
        if (magisk != null) {
            val (rc, out) = suExec("$magisk --list-modules")
            if (rc == 0 && out.isNotBlank()) {
                ids = out.lines().map { it.trim() }.filter { it.isNotBlank() }
            }
        }
        // Fallback: ne všechny Magisk verze mají --list-modules — čteme module
        // root přímo (funguje i s KSU/APatch).
        if (ids.isEmpty()) {
            val (_, lsOut) = suExec("ls -1 /data/adb/modules 2>/dev/null")
            ids = lsOut.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && it != "lost+found" }
        }
        return ids.mapNotNull { id ->
                // module.prop is root-readable only — read via su
                val (_, prop) = suExec("cat /data/adb/modules/$id/module.prop")
                var name = id
                var version = ""
                prop.lineSequence().forEach { line ->
                    when {
                        line.startsWith("name=") -> name = line.substringAfter("name=")
                        line.startsWith("version=") -> version = line.substringAfter("version=")
                    }
                }
                MagiskModule(id, name, version)
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Candidate module ZIPs (not yet installed): /sdcard/Download, /sdcard,
     * the app files dir and /data/local/tmp. No root needed to scan sdcard.
     */
    fun findModuleZips(context: Context): List<ModuleZip> {
        val dirs = listOf(
            File("/sdcard/Download"),
            File("/sdcard"),
            File("/data/local/tmp"),
            context.getExternalFilesDir(null),
            context.filesDir
        )
        val seen = HashSet<String>()
        val zips = ArrayList<ModuleZip>()
        for (dir in dirs) {
            if (dir == null || !dir.isDirectory) continue
            (dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", true) } ?: emptyArray())
                .sortedBy { it.name }
                .forEach { f ->
                    if (seen.add(f.absolutePath)) {
                        zips += ModuleZip(f.absolutePath, f.length(), f.name)
                    }
                }
        }
        return zips
    }

    /** Install a module from a ZIP in background: magisk --install-module <zip>. */
    fun installModule(
        zipPath: String,
        onResult: (Boolean, String) -> Unit
    ) {
        Thread {
            val magisk = findMagisk()
            if (magisk == null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(false, "magisk CLI not found") }
                return@Thread
            }
            val (rc, out) = suExec("$magisk --install-module \"$zipPath\"")
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(rc == 0, out) }
        }.start()
    }

    /** Remove ALL modules (optionally keep data with -n): magisk --remove-modules [-n]. */
    fun removeAllModules(
        keepData: Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        Thread {
            val magisk = findMagisk()
            if (magisk == null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(false, "magisk CLI not found") }
                return@Thread
            }
            val flag = if (keepData) " -n" else ""
            val (rc, out) = suExec("$magisk --remove-modules$flag")
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(rc == 0, out) }
        }.start()
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
    var showModuleDialog by remember { mutableStateOf(false) }

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

        // Magisk Module Manager Card
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "\uD83E\uDDFE Magisk moduly",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Instalace ZIP / odebrání modulů\nmagisk --install-module | --remove-modules [-n]",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = "Modules",
                        tint = Color(0xFF00FF41),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { showModuleDialog = true },
                    enabled = suDetected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00AA33),
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "\uD83D\uDCC1 Spravovat moduly",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (!suDetected) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "⚠ vyžaduje root (su)",
                        color = Color(0xFFFF8888),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
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

    if (showModuleDialog) {
        MagiskModuleDialog(
            onDismiss = { showModuleDialog = false },
            onToast = { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        )
    }
}

/**
 * Dialog spravující Magisk moduly: seznam instalovaných modulů + ZIPy
 * k instalaci (z /sdcard/Download, /sdcard, filesDir, /data/local/tmp).
 * Instalace i odebrání běží na pozadí přes magisk CLI (su).
 */
@Composable
fun MagiskModuleDialog(
    onDismiss: () -> Unit,
    onToast: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var magiskPath by remember { mutableStateOf(MagiskModuleManager.findMagisk()) }
    var installed by remember { mutableStateOf<List<MagiskModuleManager.MagiskModule>>(emptyList()) }
    var zips by remember { mutableStateOf<List<MagiskModuleManager.ModuleZip>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var keepData by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }          // operace běží na pozadí
    var lastOutput by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        scope.launch {
            val inst = withContext(Dispatchers.IO) { MagiskModuleManager.listInstalled() }
            val zipsList = withContext(Dispatchers.IO) { MagiskModuleManager.findModuleZips(context) }
            magiskPath = MagiskModuleManager.findMagisk()
            installed = inst
            zips = zipsList
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun installZip(zip: MagiskModuleManager.ModuleZip) {
        if (busy) return
        busy = true
        lastOutput = null
        scope.launch {
            MagiskModuleManager.installModule(zip.path) { ok, out ->
                lastOutput = (if (ok) "✅ INSTALL OK" else "❌ INSTALL FAILED") + "\n" + out
                busy = false
                if (ok) onToast("Modul nainstalován: ${zip.name}")
                refresh()
            }
        }
    }

    fun removeAll() {
        if (busy) return
        busy = true
        lastOutput = null
        scope.launch {
            MagiskModuleManager.removeAllModules(keepData) { ok, out ->
                lastOutput = (if (ok) "✅ REMOVE OK" else "❌ REMOVE FAILED") + "\n" + out
                busy = false
                if (ok) onToast(if (keepData) "Moduly odebrány (data zachována -n)" else "Moduly odebrány")
                refresh()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Color(0xFF141414),
        title = {
            Text(
                text = "\uD83D\uDCC1 Magisk moduly",
                color = Color(0xFF00FF41),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // magisk path + refresh bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = magiskPath ?: "magisk CLI nenalezen",
                        color = if (magiskPath != null) Color(0xFF88FF88) else Color(0xFFFF8888),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { refresh() },
                        enabled = !loading && !busy
                    ) {
                        Text("⟳ Obnovit", color = Color(0xFF00FF41), fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF2A2A2A))
                Spacer(modifier = Modifier.height(8.dp))

                // Instalované moduly
                Text(
                    text = "✅ INSTALOVANÉ (${installed.size})",
                    color = Color(0xFF88FF88),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF00FF41)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("načítám…", color = Color.Gray, fontSize = 11.sp)
                    }
                } else if (installed.isEmpty()) {
                    Text("— žádné inst. moduly —", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                } else {
                    installed.forEach { m ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(m.name, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text(
                                    "${m.id} · v${m.version}",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = Color(0xFF2A2A2A))
                Spacer(modifier = Modifier.height(6.dp))

                // Odebrat vše (--remove-modules [-n])
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = keepData,
                        onCheckedChange = { keepData = it },
                        enabled = !busy,
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF00FF41),
                            checkmarkColor = Color.Black,
                            uncheckedColor = Color.Gray
                        )
                    )
                    Text(
                        text = "-n zachovat data",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(
                    onClick = { removeAll() },
                    enabled = !busy && !loading && magiskPath != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF5252),
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🗑 Odebrat VŠECHNY moduly", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = Color(0xFF2A2A2A))
                Spacer(modifier = Modifier.height(8.dp))

                // ZIPy k instalaci
                Text(
                    text = "\uD83D\uDCC1 K INSTALACI (${zips.size})",
                    color = Color(0xFFFFB000),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (zips.isEmpty()) {
                    Text("— žádné ZIPy (Download / sdcard / filesDir) —", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                } else {
                    zips.forEach { z ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(z.name, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text(
                                    "${z.size / 1024} KB · ${z.path}",
                                    color = Color.Gray,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Button(
                                onClick = { installZip(z) },
                                enabled = !busy && magiskPath != null,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00AA33),
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (busy) "…" else "INSTALOVAT",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // Busy + výstup operace
                if (busy) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF00FF41)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("probíhá na pozadí…", color = Color(0xFF00FF41), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                lastOutput?.let { out ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = out,
                            color = Color(0xFFBBBBBB),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) {
                Text("Zavřít", color = Color(0xFF00FF41))
            }
        }
    )
}
