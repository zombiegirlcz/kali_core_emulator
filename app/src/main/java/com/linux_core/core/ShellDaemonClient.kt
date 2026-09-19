package com.linux_core.core

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import org.json.JSONObject

/**
 * Klient k `shell_daemon` — persistentnímu shell-UID (2000) daemonu.
 *
 * **Deploy (vzor `libshizuku.so` z AOSP/NDK)**: daemon se neveze jako asset
 * v `assets/`, ale jako spustitelný ELF v `jniLibs/arm64-v8a/libshelldaemon.so`.
 * Android ho při instalaci extrahuje do `applicationInfo.nativeLibraryDir`
 * (díky `useLegacyPackaging=true` je soubor reálně na disku, ne jen v APK).
 * Cesta se publikuje do `filesDir/shelldaemon.path` → guest ji vidí jako
 * `/mnt/app/shelldaemon.path` (bind `$FILES_DIR → /mnt/app` z `boot` skriptu).
 *
 * **Spuštění**: NIKDY přes `su`. Daemon musí běžet pod uid 2000, což app UID
 * (10323) nedokáže spawnout. Spouští ho proto **guest** příkazem
 * `ashell adb start` → `adb shell nohup <nativeLibraryDir>/libshelldaemon.so …`.
 * Aplikace jen detekuje, že daemon běží (TCP probe), a posílá mu příkazy.
 *
 * **Komunikace**: TCP 127.0.0.1:[PORT], binární protokol (length-prefixed
 * blob) definovaný v `app/src/main/cpp/shell_daemon.c`.
 */
data class ShellDaemonStatus(
    val running: Boolean,
    val port: Int = ShellDaemonClient.PORT,
)

object ShellDaemonClient {
    private const val TAG = "ShellDaemonClient"
    const val PORT = 13341

    /** Token pro autentizaci daemona (guest ho čte z `/mnt/app/shell_daemon.token`). */
    private const val TOKEN_FILE = "shell_daemon.token"

    /** Publikovaná cesta k extrahované binárce (guest čte z `/mnt/app/shelldaemon.path`). */
    private const val PATH_FILE = "shelldaemon.path"

    /** Název v jniLibs — Android ho extrahuje do nativeLibraryDir pod stejným jménem. */
    private const val SO_NAME = "libshelldaemon.so"

    // Protokol konstanty (musí odpovídat shell_daemon.c)
    private const val SH_MAGIC = 0x53484C4C  // "SHLL"
    private const val SH_MODE_EXEC = 0
    private const val SH_MODE_ATTACH = 1

    /**
     * Absolutní cesta k extrahované binárce v `nativeLibraryDir`.
     * Tudy ji spustí guest přes `adb shell` pod uid 2000.
     */
    @JvmStatic
    fun binaryPath(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, SO_NAME)

    /**
     * Publikuj cestu k binárce do `filesDir/shelldaemon.path`, aby ji guest
     * (přes bind `/mnt/app`) našel bez hardcoded cesty do `/data/app/...`.
     * Volá se při deployi i před startem; idempotentní.
     */
    @JvmStatic
    fun publishBinaryPath(context: Context): String {
        val bin = binaryPath(context)
        val p = bin.absolutePath
        try {
            File(context.filesDir, PATH_FILE).writeText(p)
        } catch (e: Exception) {
            Log.w(TAG, "publishBinaryPath failed: ${e.message}")
        }
        return p
    }

    /**
     * Vrátí (případně vygeneruje) perzistentní token pro autentizaci daemona.
     * Soubor leží ve filesDir → v guestu je vidět jako `/mnt/app/shell_daemon.token`,
     * takže ho `ashell adb start` umí přečíst a předat daemonu jako `--token=<hex>`.
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

    /**
     * @deprecated Starý deploy z assets byl nahrazen cestou jniLibs.
     *  Ponecháno kvůli volajícím (ProotManager.deployShellDaemon) — nyní jen
     *  publikuje cestu k extrahované `.so` a vygeneruje token.
     */
    @JvmStatic
    @Deprecated("Use publishBinaryPath + ensureToken")
    fun deployBinary(context: Context): File? {
        val bin = binaryPath(context)
        if (!bin.exists()) {
            Log.w(TAG, "deployBinary: ${bin.absolutePath} neexistuje (extractNativeLibs?)")
        }
        publishBinaryPath(context)
        return bin
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
     * Aplikace **neumí** daemona spustit pod uid 2000 (app UID nesmí měnit uid
     * dítěte; `su` je zakázané). Start tedy dělá guest přes `adb shell`:
     *
     *     ashell adb start
     *
     * Tahle funkce jen publikuje cestu k binárce (aby ji guest našel) a vrátí
     * `false`, když daemon neběží — UI podle toho zobrazí instrukci.
     *
     * @return true jen když daemon už běžel.
     */
    @JvmStatic
    fun startDaemon(context: Context): Boolean {
        if (status().running) return true
        publishBinaryPath(context)
        ensureToken(context)
        Log.i(
            TAG,
            "startDaemon: app UID nemuze spawnout uid 2000 — spust z guestu: 'ashell adb start' " +
                "(bin=${binaryPath(context).absolutePath})"
        )
        return false
    }

    /**
     * Zastavení daemona z appky není možné (běží pod uid 2000). Guest použije
     * `ashell adb stop` (adb shell pkill). Best-effort: publikuj cestu + token.
     */
    @JvmStatic
    fun stopDaemon(context: Context): Boolean {
        publishBinaryPath(context)
        return false
    }

    /**
     * Spustí příkaz skrz daemon → běží pod uid 2000.
     * Vrací JSON `{stdout, stderr, exit_code, mode}` nebo `{error, exit_code:-1}`.
     */
    @JvmStatic
    fun exec(context: Context, command: String, cwd: String = ""): String {
        if (!status().running) {
            return """{"error":"shell_daemon not running (spust 'ashell adb start')","exit_code":-1}"""
        }
        val token = ensureToken(context)
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", PORT), 2500)
                s.soTimeout = 120_000
                val out = DataOutputStream(s.getOutputStream())
                // Protokol: magic (4B) + mode (1B) + token + cmd + cwd
                out.writeInt(SH_MAGIC)
                out.writeByte(SH_MODE_EXEC)
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
