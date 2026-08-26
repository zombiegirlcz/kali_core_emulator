package com.linux_core.core

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.jvm.Volatile

/**
 * Headless background boot of the PRoot guest — used for cron automation.
 *
 * Starts a terminal session with NO view (TerminalView = null): the proot
 * container boots, the guest boot script starts the cron daemon and then keeps
 * the container alive. TerminalService runs as a foreground service with
 * START_STICKY, so the app survives in the background and cron keeps executing
 * its jobs.
 */
object BackgroundBoot {
    private const val TAG = "BackgroundBoot"

    @Volatile private var launching = false

    fun start(context: Context) {
        // Skip duplicates: a cron session is already active, or a launch is
        // in flight (MY_PACKAGE_REPLACED while running, or a race with a
        // START_STICKY restart). `backgroundBootSessionId` resets when the
        // session is removed (dead), allowing a legitimate relaunch.
        if (TerminalService.backgroundBootSessionId != null || launching) {
            Log.i(TAG, "Background cron already active or launching — skipping duplicate")
            return
        }
        launching = true
        Thread {
            try {
                // Ensure layout migration has run before scanning for rootfs
                RootfsManager.ensureMigrated(context)
                val rootfsDir = detectActiveRootfsDir(context)
                if (rootfsDir == null) {
                    Log.w(TAG, "No rootfs found — skipping background boot")
                    return@Thread
                }

                // Deploy the guest boot (cron) script
                val bootScript = File(rootfsDir, "root/.nh_boot.sh")
                bootScript.parentFile?.mkdirs()
                bootScript.writeText(buildBootScript())
                bootScript.setExecutable(true, false)

                // Build the proot config (normal container boot + custom command).
                val config = ProotManager.setupProotEnvironment(
                    context = context,
                    rootfsDirName = rootfsDir.relativeTo(context.filesDir).path,
                    mountStorage = false,
                    customCommand = "bash /root/.nh_boot.sh",
                    hasRoot = false,
                    isDockerImage = false
                )

                // Headless session: view = null, output goes to the session buffer.
                val created = TerminalService.createSession(context, config, null) { err ->
                    Log.e(TAG, "Headless cron session error: $err")
                }
                TerminalService.sessionIds[created]?.let { TerminalService.backgroundBootSessionId = it }
                TerminalService.backgroundBootReloads = 0
                TerminalService.backgroundBootStartedAt = System.currentTimeMillis()
                Log.i(TAG, "Background boot started (cron session in ${rootfsDir.name})")
            } catch (e: Exception) {
                Log.e(TAG, "Background boot failed: ${e.message}")
            } finally {
                launching = false
            }
        }.start()
    }

    private fun detectActiveRootfsDir(context: Context): File? {
        val filesDir = context.filesDir ?: return null

        // New layout: check nh/distro/kali and nh/distro/parrot first
        val nhDistroDir = File(filesDir, RootfsManager.NH_DISTRO_DIR)
        if (nhDistroDir.isDirectory) {
            val nhCandidates = nhDistroDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            val nhRootfs = nhCandidates.filter { dir ->
                File(dir, "etc/passwd").exists() && File(dir, "usr/bin").isDirectory
            }.maxByOrNull { it.lastModified() }
            if (nhRootfs != null) return nhRootfs
        }

        // Legacy fallback: old layout directories
        val candidates = filesDir.listFiles()?.filter { it.isDirectory } ?: return null
        return candidates.filter { dir ->
            dir.name.endsWith("-arm64") ||
                (File(dir, "etc/passwd").exists() && File(dir, "usr/bin").isDirectory)
        }.maxByOrNull { it.lastModified() }
    }

    private fun buildBootScript(): String = buildString {
        appendLine("#!/bin/bash")
        appendLine("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        appendLine("export HOME=/root")
        appendLine("LOG=/var/log/nethunter-boot.log")
        appendLine("echo \"[nh-boot] \$(date) starting\" >> \$LOG 2>/dev/null || true")
        appendLine("")
        appendLine("# Start the cron daemon (Kali/Debian: /usr/sbin/cron, others: crond)")
        appendLine("if command -v cron >/dev/null 2>&1; then")
        appendLine("    echo \"[nh-boot] starting cron\" >> \$LOG 2>/dev/null || true")
        appendLine("    cron 2>>\$LOG || true")
        appendLine("elif command -v crond >/dev/null 2>&1; then")
        appendLine("    echo \"[nh-boot] starting crond\" >> \$LOG 2>/dev/null || true")
        appendLine("    crond 2>>\$LOG || true")
        appendLine("else")
        appendLine("    echo \"[nh-boot] WARNING: cron not installed (apt install cron)\" >> \$LOG 2>/dev/null || true")
        appendLine("fi")
        appendLine("")
        appendLine("# Keep the proot session alive so cron keeps running in the background.")
        appendLine("while true; do sleep 60; done")
    }
}
