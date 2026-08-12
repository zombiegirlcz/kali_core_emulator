package com.linux_core.core

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * UsbGadgetManager — ACTIVATION / DEACTIVATION of the prepared USB gadget [g2].
 *
 * The Magisk module `custom_usb_g2_setup` prepares the g2 config in configfs
 * (structure, strings, config c.1 with RNDIS + HID + mass_storage symlinks)
 * at boot. This manager ONLY binds/unbinds g2 to/from a UDC — it never
 * reconfigures g2 and NEVER touches g1 (the system gadget, MTP/ADB).
 *
 * The kernel allows only ONE gadget per UDC: if g1 holds the only UDC of the
 * device and the user wants g2, the caller must first release g1. That is the
 * ONLY situation where the manager would touch g1 — and it is deliberately
 * NOT done here: activate() fails with a clear message instead.
 *
 * Endpoints exposed by LocalApiServer:
 *   GET  /usbg2/status   → JSON status (g1 + g2 + UDCs)
 *   POST /usbg2/start    → bind g2 to a free UDC   (body ignored)
 *   POST /usbg2/stop     → unbind g2               (body ignored)
 */
class UsbGadgetManager(context: Context) {

    companion object {
        private const val TAG = "UsbGadgetManager"
        private val CONFIGFS_CANDIDATES = listOf(
            "/config/usb_gadget",
            "/sys/kernel/config/usb_gadget",
        )
        private const val SU_PATH = "/system/bin/su"
    }

    private val appContext = context.applicationContext

    // ── low-level root shell ───────────────────────────────────────────────
    /** Runs `su -c <script>` (Magisk). Returns (exitCode, combined output). */
    private fun rootShell(script: String, timeoutMs: Long = 8000L): Pair<Int, String> {
        val cmd = listOf(SU_PATH, "-c", script)
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = StringBuilder()
            Thread {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) out.append(line).append('\n')
                    }
                } catch (e: Exception) { /* proc died */ }
            }.also { it.isDaemon = true }.start()
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroy()
                return -1 to "timeout after ${timeoutMs}ms: $out"
            }
            proc.exitValue() to out.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "rootShell failed: ${e.message}")
            -1 to "root shell error: ${e.message}"
        }
    }

    /** Shell-safe single-quote escape for a value embedded in su script. */
    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Configfs gadget root (e.g. /config/usb_gadget), probed read-only. */
    fun configfsRoot(): String? {
        for (c in CONFIGFS_CANDIDATES) {
            if (java.io.File(c).isDirectory) return c
        }
        return null
    }

    fun isPrepared(): Boolean {
        val root = configfsRoot() ?: return false
        return java.io.File(root, "g2").isDirectory
    }

    fun isActive(): Boolean = udcOf("g2")?.isNotBlank() == true

    /** Currently bound UDC of gadget `name` (g1|g2) — read-only. */
    fun udcOf(name: String): String? {
        val root = configfsRoot() ?: return null
        val f = java.io.File(root, "$name/UDC")
        if (!f.canRead()) return null
        return runCatching { f.readText().trim() }.getOrNull()
    }

    /** List of UDC controllers known to the kernel (/sys/class/udc). */
    fun udcList(): List<String> {
        val d = java.io.File("/sys/class/udc")
        return if (d.isDirectory) d.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            else emptyList()
    }

    // ── operations ─────────────────────────────────────────────────────────
    /**
     * Bind g2 to a UDC. Policy:
     *   1. g2 already bound → success (idempotent).
     *   2. Prefer a UDC NOT used by g1; else the first UDC when g1 is free;
     *      else fail with an explicit message (g1 holds the only UDC).
     * Never writes to g1.
     */
    fun activate(): Result<String> {
        val root = configfsRoot() ?: return Result.failure(
            IllegalStateException("configfs usb_gadget not found (probed ${CONFIGFS_CANDIDATES})"))
        if (!isPrepared()) return Result.failure(
            IllegalStateException("g2 not prepared — flash the 'custom_usb_g2_setup' Magisk module and reboot"))

        val g2Udc = udcOf("g2")
        if (!g2Udc.isNullOrBlank()) return Result.success("g2 already active on UDC $g2Udc")

        val udcs = udcList()
        if (udcs.isEmpty()) return Result.failure(IllegalStateException("no UDC controllers in /sys/class/udc"))
        val g1Udc = udcOf("g1")?.takeIf { it.isNotBlank() }

        val target = when {
            g1Udc == null -> udcs.first()                       // g1 not bound → first free
            else -> udcs.firstOrNull { it != g1Udc }            // prefer a different UDC
        }
        if (target == null) {
            return Result.failure(IllegalStateException(
                "g1 holds the only UDC ($g1Udc). Deactivate g1 first (e.g. switch USB mode / mount storage off), then retry. g1 is never touched by this manager."))
        }

        val (rc, out) = rootShell("echo ${q(target)} > ${q(root)}/g2/UDC && cat ${q(root)}/g2/UDC")
        if (rc != 0) return Result.failure(IllegalStateException("binding g2 to $target failed (rc=$rc): $out"))
        Log.i(TAG, "g2 bound to $target")
        return Result.success("g2 activated on UDC $target")
    }

    /** Unbind g2 (writes empty UDC). Idempotent; never touches g1. */
    fun deactivate(): Result<String> {
        val root = configfsRoot() ?: return Result.failure(
            IllegalStateException("configfs usb_gadget not found"))
        val g2Udc = udcOf("g2")
        if (g2Udc.isNullOrBlank()) return Result.success("g2 not active")
        val (rc, out) = rootShell("echo '' > ${q(root)}/g2/UDC")
        if (rc != 0) return Result.failure(IllegalStateException("unbinding g2 failed (rc=$rc): $out"))
        Log.i(TAG, "g2 released from UDC $g2Udc")
        return Result.success("g2 deactivated (released $g2Udc)")
    }

    // ── status ────────────────────────────────────────────────────────────
    fun statusJson(): String {
        val sb = StringBuilder("{")
        val root = configfsRoot()
        sb.append("\"configfs\":${if (root != null) "\"$root\"" else "null"},")
        sb.append("\"prepared\":${isPrepared()},")
        sb.append("\"g1_udc\":${jsonStr(udcOf("g1"))},")
        sb.append("\"g2_udc\":${jsonStr(udcOf("g2"))},")
        val udcs = udcList()
        sb.append("\"udcs\":[")
        sb.append(udcs.joinToString(",") { "\"$it\"" })
        sb.append("]}")
        return sb.toString()
    }

    private fun jsonStr(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}