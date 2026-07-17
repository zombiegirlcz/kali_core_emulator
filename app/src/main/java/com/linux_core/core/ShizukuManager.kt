package com.linux_core.core

import android.content.Context
import android.util.Log
import java.io.File

data class ShizukuStatus(
    val running: Boolean,
    val pid: Int? = null,
    val uid: Int? = null,
    val port: Int? = null,
    val mode: String = "unknown" // "existing", "self", "none"
)

object ShizukuManager {
    private const val TAG = "ShizukuManager"

    // Asset paths
    private const val ASSET_SERVER = "shizuku/libshizuku.so"
    private const val ASSET_RISH_SCRIPT = "shizuku/rish.sh"
    private const val ASSET_RISH_DEX = "shizuku/rish_shizuku.dex"

    // FilesDir paths
    private const val SERVER_BIN = "shizuku-server"
    private const val RISH_DEX = "rish_shizuku.dex"
    private const val PID_FILE = "shizuku.pid"

    /**
     * Deploy Shizuku server binary from assets to filesDir.
     */
    @JvmStatic
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
     * Deploy rish script and dex to the given rootfs directory (for PRoot).
     */
    @JvmStatic
    fun deployRish(context: Context, rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin")
        if (!binDir.exists()) binDir.mkdirs()

        // Deploy rish script as 'shizuku' command
        val rishScript = File(binDir, "shizuku")
        try {
            context.assets.open(ASSET_RISH_SCRIPT).use { input ->
                rishScript.outputStream().use { output -> input.copyTo(output) }
            }
            rishScript.setExecutable(true, false)
            rishScript.setReadable(true, false)
            Log.i(TAG, "Deployed rish wrapper to ${rishScript.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy rish script: ${e.message}")
        }

        // Deploy rish dex alongside the script (BASEDIR logic in rish.sh expects it there)
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
     * Check if Shizuku server is running (either our own or an existing one).
     */
    @JvmStatic
    fun status(context: Context): ShizukuStatus {
        // Check via PID file (our own server)
        val pidFile = File(context.filesDir, PID_FILE)
        if (pidFile.exists()) {
            val pid = try { pidFile.readText().trim().toInt() } catch (e: Exception) { null }
            if (pid != null) {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ls /proc/$pid/status 2>/dev/null"))
                    val output = proc.inputStream.bufferedReader().readText()
                    if (output.isNotEmpty()) {
                        return ShizukuStatus(running = true, pid = pid, mode = "self")
                    }
                } catch (e: Exception) { /* ignore */ }
            }
            // Stale PID file
            pidFile.delete()
        }

        // Also check if an existing Shizuku server is running (from Shizuku app)
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "ps -ef 2>/dev/null | grep -i 'shizuku' | grep -v grep | head -5"))
            val output = proc.inputStream.bufferedReader().readText()
            if (output.isNotBlank()) {
                // Try to extract PID from ps output
                val pid = Regex("\\s+(root|shell)\\s+(\\d+)").find(output)?.groupValues?.get(2)?.toIntOrNull()
                return ShizukuStatus(running = true, pid = pid, mode = "existing")
            }
        } catch (e: Exception) { /* ignore */ }

        return ShizukuStatus(running = false, mode = "none")
    }

    /**
     * Execute a command through Shizuku (via rish).
     */
    @JvmStatic
    fun exec(context: Context, command: String): String {
        val status = status(context)
        if (!status.running) {
            return "{\"error\":\"Shizuku server not running\",\"exit_code\":-1}"
        }

        return try {
            val dexFile = File(context.filesDir, RISH_DEX)
            if (!dexFile.exists()) {
                context.assets.open(ASSET_RISH_DEX).use { input ->
                    dexFile.outputStream().use { output -> input.copyTo(output) }
                }
                dexFile.setReadable(true, false)
            }

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

            """{"stdout":${output.replace("\\", "\\\\").replace("\"", "\\\"")},"exit_code":$exitCode}"""
        } catch (e: Exception) {
            """{"error":"${e.message}","exit_code":-1}"""
        }
    }

    /**
     * Attempt to start the Shizuku server.
     * Currently relies on an existing Shizuku server.
     * Self-hosted ADB pairing will be added in a future iteration.
     */
    @JvmStatic
    fun startServer(context: Context): Boolean {
        val currentStatus = status(context)
        if (currentStatus.running) {
            Log.i(TAG, "Shizuku server already running")
            return true
        }

        Log.w(TAG, "No Shizuku server found. User needs to start Shizuku app first or use ADB pairing")
        return false
    }

    /**
     * Attempt to stop the Shizuku server (only works for self-managed server).
     */
    @JvmStatic
    fun stopServer(context: Context): Boolean {
        val currentStatus = status(context)
        if (!currentStatus.running) return true

        if (currentStatus.mode == "self" && currentStatus.pid != null) {
            return try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill ${currentStatus.pid} 2>/dev/null"))
                val pidFile = File(context.filesDir, PID_FILE)
                pidFile.delete()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop Shizuku server: ${e.message}")
                false
            }
        }

        Log.w(TAG, "Cannot stop external Shizuku server")
        return false
    }
}
