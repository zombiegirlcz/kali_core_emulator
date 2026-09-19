package com.linux_core.core

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Klient k [shell_daemon] — persistentnímu shell-UID (2000) daemonu.
 *
 * Analogie Shizuku: daemon se jednou spustí (`nh shi start --none` z guestu
 * přes `adb shell`, nebo na root zařízení `su 2000 -c` z aplikace), pak běží
 * do rebootu a aplikace i PRoot guest přes něj posílají příkazy, které
 * vykonává pod uid 2000 = stejná práva jako adb (`pm`, `settings`, `dumpsys`,
 * `cmd package install`, …). Žádný `su` ani `adb` per-command.
 *
 * Komunikace: TCP 127.0.0.1:[PORT], binární protokol (length-prefixed blob)
 * definovaný v `app/src/main/cpp/shell_daemon.c`.
 */
data class ShellDaemonStatus(
    val running: Boolean,
    val port: Int = ShellDaemonClient.PORT,
)

object ShellDaemonClient {
    private const val TAG = "ShellDaemonClient"
    const val PORT = 13341
    private const val TOKEN_FILE = "shell_daemon.token"
    private const val ASSET_BIN = "shell_daemon"
    private const val BIN_NAME = "shell_daemon"

    /**
     * Vrátí (případně vygeneruje) perzistentní token pro autentizaci daemona.
     * Soubor leží ve filesDir → v guestu je vidět jako `/mnt/app/shell_daemon.token`
     * (bind `$FILES_DIR → /mnt/app` z `boot` skriptu), takže ho `nh shi start
     * --none` umí přečíst a předat daemonu jako `--token=<hex>`.
     */
    @JvmStatic
    fun ensureToken(context: Context): String {
        val f = File(context.filesDir, TOKEN_FILE)
        if (f.exists() && f.length() > 0L) {
            val t = f.readText().trim()
            if (t.isNotEmpty()) return t
        }
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        f.writeText(hex)
        f.setReadable(true, true)
        return hex
    }

    /** Nasaď `shell_daemon` z assets do filesDir (hash-gated, idempotentní). */
    @JvmStatic
    fun deployBinary(context: Context): File? {
        val target = File(context.filesDir, BIN_NAME)
        var redeploy = !target.exists() || target.length() == 0L
        if (!redeploy) {
            try {
                val sz = context.assets.open(ASSET_BIN).use { it.available().toLong() }
                if (target.length() != sz) redeploy = true
            } catch (_: Exception) {
                redeploy = true
            }
        }
        if (redeploy) {
            try {
                context.assets.open(ASSET_BIN).use { i ->
                    target.outputStream().use { o -> i.copyTo(o) }
                }
                target.setExecutable(true, false)
                target.setReadable(true, false)
                Log.i(TAG, "Deployed shell_daemon (${target.length()} B)")
            } catch (e: Exception) {
                Log.e(TAG, "deploy shell_daemon failed: ${e.message}")
                return null
            }
        }
        return target
    }

    /** Zkus TCP connect na daemon (žádný token zatím, jen existence). */
    @JvmStatic
    fun status(): ShellDaemonStatus =
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", PORT), 800)
            }
            ShellDaemonStatus(running = true)
        } catch (_: Exception) {
            ShellDaemonStatus(running = false)
        }

    /**
     * Spusť daemon, pokud neběží. Dvě cesty:
     *
     *  1. **Root zařízení** (`su` k dispozici): `su 2000 -c "/data/local/tmp/
     *     shell_daemon … &"`. `su 2000` spustí proces pod uid 2000, ne pod
     *     rootem — root je jen umožňovač spuštění (app UID samo nedokáže
     *     spawnout shell-UID proces), vlastní příkazy běží jako standardní
     *     shell/adb. **Toto NENÍ su fallback pro exekuci** — spouští se
     *     jen jednou persistentní daemon.
     *
     *  2. **Non-root**: daemon musí nastartovat `nh shi start --none`
     *     z guestu přes `adb shell` (guest má vlastní `/usr/bin/adb`).
     *     Aplikace pak k běžícímu daemonu jen připojí TCP.
     */
    @JvmStatic
    fun startDaemon(context: Context): Boolean {
        if (status().running) return true
        val bin = deployBinary(context) ?: return false
        val token = ensureToken(context)
        val su = ShizukuManager.suPath() ?: run {
            Log.w(TAG, "startDaemon: su nedostupne — spust 'nh shi start --none' z guestu (adb)")
            return false
        }
        val tmp = "/data/local/tmp"
        return try {
            ProcessBuilder(su, "-c", "cp ${bin.absolutePath} $tmp/shell_daemon && chmod 755 $tmp/shell_daemon")
                .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
            val startCmd = "$tmp/shell_daemon --port=$PORT --token=$token > $tmp/shell_daemon.log 2>&1 &"
            ProcessBuilder(su, "2000", "-c", startCmd)
                .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
            Thread.sleep(800)
            val ok = status().running
            Log.i(TAG, "startDaemon via 'su 2000 -c': ok=$ok")
            if (!ok) {
                val log = File("$tmp/shell_daemon.log")
                    .let { if (it.exists()) it.readText().take(800) else "(zadny log)" }
                Log.w(TAG, "daemon nenabehl, log: $log")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "startDaemon failed: ${e.message}")
            false
        }
    }

    /** Zastav daemon (pkill pod uid 2000). Best-effort. */
    @JvmStatic
    fun stopDaemon(context: Context): Boolean {
        val su = ShizukuManager.suPath() ?: return false
        return try {
            ProcessBuilder(su, "2000", "-c", "pkill -x shell_daemon 2>/dev/null || true")
                .redirectErrorStream(true).start().waitFor(3, TimeUnit.SECONDS)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Spustí příkaz skrz daemon → běží pod uid 2000.
     * Vrací JSON `{stdout, stderr, exit_code, mode}` nebo `{error, exit_code:-1}`.
     */
    @JvmStatic
    fun exec(context: Context, command: String, cwd: String = ""): String {
        if (!status().running) {
            return """{"error":"shell_daemon not running","exit_code":-1}"""
        }
        val token = ensureToken(context)
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", PORT), 2500)
                s.soTimeout = 120_000
                val out = DataOutputStream(s.getOutputStream())
                writeBlob(out, token.toByteArray(Charsets.UTF_8))
                writeBlob(out, command.toByteArray(Charsets.UTF_8))
                writeBlob(out, cwd.toByteArray(Charsets.UTF_8))
                out.flush()

                val inp = DataInputStream(s.getInputStream())
                val code = inp.readInt()
                val stdout = readBlob(inp)
                val stderr = readBlob(inp)
                JSONObject().apply {
                    put("stdout", stdout)
                    put("stderr", stderr)
                    put("exit_code", code)
                    put("mode", "shell_daemon")
                }.toString()
            }
        } catch (e: Exception) {
            val m = (e.message ?: "exec failed")
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            """{"error":"$m","exit_code":-1}"""
        }
    }

    private fun writeBlob(out: DataOutputStream, data: ByteArray) {
        out.writeInt(data.size)
        if (data.isNotEmpty()) out.write(data)
    }

    private fun readBlob(inp: DataInputStream): String {
        val n = inp.readInt()
        if (n <= 0) return ""
        val buf = ByteArray(n)
        inp.readFully(buf)
        return buf.toString(Charsets.UTF_8)
    }
}
