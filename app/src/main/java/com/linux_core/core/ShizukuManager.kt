package com.linux_core.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

data class ShizukuStatus(
    val running: Boolean,
    val pid: Int? = null,
    val uid: Int? = null,
    val port: Int? = null,
    val mode: String = "none",   // "existing" (Shizuku app), "self" (our server via su/adb), "none"
    val suAvailable: Boolean = false,
    val shizukuApkPath: String? = null,
    val adbAvailable: Boolean = false
)

object ShizukuManager {
    private const val TAG = "ShizukuManager"

    // Asset paths
    private const val ASSET_SERVER = "shizuku/libshizuku.so"
    private const val ASSET_RISH_DEX = "shizuku/rish_shizuku.dex"

    // FilesDir paths
    private const val SERVER_BIN = "shizuku-server"
    private const val RISH_DEX = "rish_shizuku.dex"
    private const val PID_FILE = "shizuku.pid"

    // Shizuku manager package
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    /**
     * Deploy the native server binary from assets to filesDir.
     */
    private fun deployServer(context: Context): File? {
        val target = File(context.filesDir, SERVER_BIN)
        if (target.exists() && target.length() > 0L) {
            target.setExecutable(true, false)
            return target
        }
        return try {
            context.assets.open(ASSET_SERVER).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, false)
            target.setReadable(true, false)
            Log.i(TAG, "Deployed Shizuku server binary (${target.length()} bytes)")
            target
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy Shizuku server: ${e.message}")
            null
        }
    }

    /**
     * Deploy rish dex to filesDir for exec().
     */
    private fun deployDex(context: Context): File? {
        val target = File(context.filesDir, RISH_DEX)
        if (target.exists() && target.length() > 0L) {
            target.setReadable(true, false)
            return target
        }
        return try {
            context.assets.open(ASSET_RISH_DEX).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, false)
            Log.i(TAG, "Deployed rish dex (${target.length()} bytes)")
            target
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy rish dex: ${e.message}")
            null
        }
    }

    /**
     * Deploy rish script and dex into PRoot guest.
     */
    @JvmStatic
    fun deployRish(context: Context, rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin")
        if (!binDir.exists()) binDir.mkdirs()

        val rishScript = File(binDir, "shizuku")
        try {
            context.assets.open("shizuku/rish.sh").use { input ->
                rishScript.outputStream().use { output -> input.copyTo(output) }
            }
            rishScript.setExecutable(true, false)
            rishScript.setReadable(true, false)
            Log.i(TAG, "Deployed rish wrapper to ${rishScript.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy rish script: ${e.message}")
        }

        val rishDex = File(binDir, "rish_shizuku.dex")
        try {
            context.assets.open(ASSET_RISH_DEX).use { input ->
                rishDex.outputStream().use { output -> input.copyTo(output) }
            }
            rishDex.setReadable(true, false)
            Log.i(TAG, "Deployed rish dex to ${rishDex.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy rish dex: ${e.message}")
        }
    }

    /**
     * Check the current Shizuku status — server running, su available,
     * Shizuku APK installed, ADB available.
     */
    @JvmStatic
    fun status(context: Context): ShizukuStatus {
        var running = false
        var pid: Int? = null
        var mode = "none"

        // 1. Check via PID file (our own server)
        val pidFile = File(context.filesDir, PID_FILE)
        if (pidFile.exists()) {
            val storedPid = try { pidFile.readText().trim().toInt() } catch (e: Exception) { null }
            if (storedPid != null && processExists(storedPid)) {
                running = true; pid = storedPid; mode = "self"
            } else {
                pidFile.delete()
            }
        }

        // 2. Check existing Shizuku server (Shizuku app or other)
        if (!running) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                    "ps -ef 2>/dev/null | grep -i '[s]hizuku' | head -5"))
                val output = proc.inputStream.bufferedReader().readText()
                if (output.isNotBlank()) {
                    // Parse PID — format depends on Android version
                    val m = Regex("""(root|shell|system)\s+(\d+)""").find(output)
                    if (m != null) {
                        running = true; pid = m.groupValues[2].toIntOrNull(); mode = "existing"
                    }
                }
            } catch (e: Exception) { /* ignore */ }
        }

        // 3. Check su availability
        val suAvailable = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor() == 0 && out.contains("uid=0")
        } catch (e: Exception) { false }

        // 4. Check Shizuku APK
        val shizukuApkPath = try {
            val ai = context.packageManager.getApplicationInfo(SHIZUKU_PKG, 0)
            ai.sourceDir
        } catch (e: PackageManager.NameNotFoundException) { null }

        // 5. Check ADB debugging — adbd running (via getprop, works without special permissions)
        val adbAvailable = try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", "init.svc.adbd"))
            p.inputStream.bufferedReader().readText().trim() == "running"
        } catch (e: Exception) { false }

        return ShizukuStatus(
            running = running,
            pid = pid,
            mode = mode,
            suAvailable = suAvailable,
            shizukuApkPath = shizukuApkPath,
            adbAvailable = adbAvailable
        )
    }

    private fun processExists(pid: Int): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ls /proc/$pid/status 2>/dev/null"))
            p.inputStream.bufferedReader().readText().isNotEmpty()
        } catch (e: Exception) { false }
    }

    /**
     * Execute a command through Shizuku (via rish shell).
     * Falls back to plain su -c if Shizuku server is not running but su is available.
     */
    @JvmStatic
    fun exec(context: Context, command: String): String {
        val st = status(context)
        if (st.running && st.mode != "none") {
            // Use rish
            return try {
                val dexFile = deployDex(context) ?: return """{"error":"dex deploy failed","exit_code":-1}"""
                val pb = ProcessBuilder(
                    "/system/bin/app_process",
                    "-Djava.class.path=${dexFile.absolutePath}",
                    "/system/bin",
                    "--nice-name=shizuku-exec",
                    "rikka.shizuku.shell.ShizukuShellLoader",
                    "-c", command
                )
                pb.environment()["RISH_APPLICATION_ID"] = context.packageName
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val exitCode = proc.waitFor()
                """{"stdout":"${output.escapeJson()}","exit_code":$exitCode}"""
            } catch (e: Exception) {
                """{"error":"${e.message}","exit_code":-1}"""
            }
        } else if (st.suAvailable) {
            // Fallback to su
            return try {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val output = proc.inputStream.bufferedReader().readText()
                val exitCode = proc.waitFor()
                """{"stdout":"${output.escapeJson()}","exit_code":$exitCode}"""
            } catch (e: Exception) {
                """{"error":"${e.message}","exit_code":-1}"""
            }
        }
        return """{"error":"No privileged execution method available","exit_code":-1}"""
    }

    /**
     * Start the Shizuku server.
     *
     * Strategy (in order):
     * 1. Already running → return true
     * 2. su available + Shizuku APK → start server via su with --apk
     * 3. su available + no Shizuku APK → start via su (limited mode, use raw su for commands)
     * 4. ADB available → start via ADB shell
     * 5. Otherwise → return false (caller shows pairing UI)
     */
    @JvmStatic
    fun startServer(context: Context): Boolean {
        val st = status(context)
        if (st.running) {
            Log.i(TAG, "Shizuku server already running (mode=${st.mode})")
            return true
        }

        // Strategy 2: su + Shizuku APK
        if (st.suAvailable && st.shizukuApkPath != null) {
            Log.i(TAG, "Attempting to start Shizuku server via su (with Shizuku APK)")
            return startWithSu(context, st.shizukuApkPath)
        }

        // Strategy 3: su available, no Shizuku APK
        // We can't start the full server without server classes.
        // But su itself provides privilege escalation — return true to indicate
        // commands can be run via su fallback in exec().
        if (st.suAvailable) {
            Log.i(TAG, "su available without Shizuku APK — commands will use su fallback")
            return true
        }

        // Strategy 4: ADB
        if (st.adbAvailable) {
            Log.i(TAG, "Attempting to start Shizuku server via ADB")
            return startWithAdb(context, st.shizukuApkPath)
        }

        Log.w(TAG, "No startup method available (no su, no Shizuku, no ADB)")
        return false
    }

    /**
     * Start the Shizuku server via su.
     * The native PIE binary is started as root, pointing to the Shizuku APK
     * for Java server classes.
     */
    private fun startWithSu(context: Context, apkPath: String): Boolean {
        return try {
            val serverBin = deployServer(context) ?: return false
            val cmd = "${serverBin.absolutePath} --apk=$apkPath"
            Log.i(TAG, "Starting Shizuku server: su -c \"$cmd\"")

            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            // Drain stdout to avoid pipe deadlock if server produces output
            @Suppress("BlockingMethodInNonBlockingContext")
            val out = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()

            if (exitCode == 0) {
                // Save PID for management
                Thread.sleep(1500)
                val newStatus = status(context)
                if (newStatus.running) {
                    if (newStatus.pid != null) {
                        File(context.filesDir, PID_FILE).writeText(newStatus.pid.toString())
                    }
                    Log.i(TAG, "Shizuku server started successfully via su")
                    return true
                }
            }

            Log.w(TAG, "Shizuku server start via su returned exit=$exitCode")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Shizuku server via su: ${e.message}")
            false
        }
    }

    /**
     * Start Shizuku server via ADB shell (wireless debugging).
     */
    private fun startWithAdb(context: Context, apkPath: String?): Boolean {
        // ADB pairing requires the full libadb.so protocol over TCP (wireless debugging).
        // Not yet implemented — Shizuku app or su is the recommended path.
        // TODO: implement ADB pairing via libadb.so for Android 11+ wireless debugging
        Log.w(TAG, "ADB available but self-pairing not yet implemented — " +
                "use Shizuku app or root for privileged access")
        return false
    }

    /**
     * Check if Shizuku app is installed and can be used for --apk.
     */
    @JvmStatic
    fun getShizukuApkPath(context: Context): String? {
        return try {
            val ai = context.packageManager.getApplicationInfo(SHIZUKU_PKG, 0)
            ai.sourceDir
        } catch (e: PackageManager.NameNotFoundException) { null }
    }

    /**
     * Stop the Shizuku server (only works for self-managed server).
     */
    @JvmStatic
    fun stopServer(context: Context): Boolean {
        val st = status(context)
        if (!st.running) return true

        if (st.mode == "self" && st.pid != null) {
            return try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill ${st.pid} 2>/dev/null"))
                File(context.filesDir, PID_FILE).delete()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop: ${e.message}")
                false
            }
        }

        Log.w(TAG, "Cannot stop external Shizuku server")
        return false
    }
}

/**
 * Escape a string for safe inclusion in a JSON string value.
 */
private fun String.escapeJson(): String {
    return this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
