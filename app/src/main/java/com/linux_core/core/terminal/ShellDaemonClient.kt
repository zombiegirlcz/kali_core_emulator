package com.linux_core.core.terminal

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
 * **Spuštění**: NIKDY přes `su` ani z appky. Daemon musí běžet pod uid 2000,
 * což app UID (10323) nedokáže spawnout. Spouští ho **guest** příkazem
 * `ashell adb start`, který si cestu i token zjistí sám přes `adb shell`:
 *
 *     NativeDir=$(adb shell dumpsys package com.linux_core \
 *                 | grep -m1 nativeLibraryDir | sed 's/.*=//')
 *     adb shell nohup "$NativeDir/arm64/libshelldaemon.so" --port=13341 --token=…
 *
 * Aplikace jen detekuje, že daemon běží (TCP probe), a posílá mu příkazy.
 *
 * **Komunikace**: TCP 127.0.0.1:[PORT], binární protokol (length-prefixed
 * blob) definovaný v `app/src/main/cpp/shell_daemon.c`.
 */
data class ShellDaemonStatus(
    val running: Boolean,
    val port: Int = ShellDaemonClient.PORT,
    val pid: Int? = null,
)

object ShellDaemonClient {
    private const val TAG = "ShellDaemonClient"
    const val PORT = 13341

    /**
     * Token žije v `filesDir/shell_daemon.token` — `nativeLibraryDir` je
     * read-only (system:system 755), appka tam zapsat NEMŮŽE (EACCES).
     * `adb shell` (uid 2000) filesDir nepřečte, proto se token vydává
     * přes `GET /shelldaemon/info` na 127.0.0.1:1337 (kam se uid 2000 dovolá).
     */
    private const val TOKEN_FILE_NAME = "shell_daemon.token"

    /** Název v jniLibs — Android ho extrahuje do nativeLibraryDir pod stejným jménem. */
    private const val SO_NAME = "libshelldaemon.so"

    // Protokol konstanty (musí odpovídat shell_daemon.c)
    private const val SH_MAGIC = 0x53484C4C  // "SHLL"
    private const val SH_MODE_EXEC = 0
    private const val SH_MODE_INSTALL = 2
    private const val SH_MODE_ATTACH = 1
    private const val SH_MODE_STOP = 3

    /**
     * Absolutní cesta k extrahované binárce v `nativeLibraryDir`.
     * Tudy ji spustí guest přes `adb shell` pod uid 2000.
     */
    @JvmStatic
    fun binaryPath(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, SO_NAME)

    /**
     * Token file v `filesDir/shell_daemon.token` (tam appka zapisovat umí).
     * Pro `adb shell` (uid 2000) ho vydává `GET /shelldaemon/info`.
     */
    @JvmStatic
    fun tokenFile(context: Context): File =
        File(context.filesDir, TOKEN_FILE_NAME)

    /**
     * Vrátí (případně vygeneruje) perzistentní token pro autentizaci daemona.
     * Zapisuje se do `filesDir/shell_daemon.token` (nativeLibraryDir je read-only).
     * Guest ho získá přes `GET /shelldaemon/info` (loopback, uid 2000).
     * Idempotentní.
     */
    @JvmStatic
    fun ensureToken(context: Context): String {
        val f = tokenFile(context)
        if (f.exists() && f.length() > 0L) {
            val t = f.readText().trim()
            if (t.isNotEmpty() && t != "placeholder") return t
        }
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        try {
            f.writeText(hex)
            f.setReadable(true, true)
        } catch (e: Exception) {
            Log.w(TAG, "ensureToken: nelze zapsat ${f.absolutePath}: ${e.message}")
        }
        return hex
    }

    /**
     * @deprecated Nahrazeno `ensureToken` — binarka se nekam nekopiruje,
     *  lezi v nativeLibraryDir z jniLibs. Ponecháno pro volajici.
     */
    @JvmStatic
    @Deprecated("Use ensureToken")
    fun deployBinary(context: Context): File? {
        val bin = binaryPath(context)
        if (!bin.exists()) {
            Log.w(TAG, "deployBinary: ${bin.absolutePath} neexistuje (extractNativeLibs?)")
        }
        ensureToken(context)
        return bin
    }

    /** Zkus TCP connect na daemon + ověření PID file. */
    @JvmStatic
    fun status(): ShellDaemonStatus =
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", PORT), 800)
            }
            // TCP connect uspěl, ale ověříme i PID file.
            // Pokud PID file chybí, jde pravděpodobně o zombie listener
            // nebo starý socket — daemon reálně neběží.
            val pid = readDaemonPid()
            if (pid == null) {
                ShellDaemonStatus(running = false, port = PORT, pid = null)
            } else {
                ShellDaemonStatus(running = true, port = PORT, pid = pid)
            }
        } catch (_: Exception) {
            // Connect selhal → daemon určitě neběží.
            ShellDaemonStatus(running = false, port = PORT, pid = null)
        }

    /**
     * PID beziho daemona (pro `ashell adb status`); fallback `null`.
     * App ho cte z /proc, ale bez rootu vidi jen sve potomky — proto spise
     * zkousime /data/local/tmp/shelldaemon.pid, kam ho daemon zapise.
     */
    private fun readDaemonPid(): Int? = try {
        val f = File("/data/local/tmp/shelldaemon.pid")
        if (f.exists()) f.readText().trim().toIntOrNull() else null
    } catch (_: Exception) {
        null
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
        ensureToken(context)
        Log.i(
            TAG,
            "startDaemon: app UID nemuze spawnout uid 2000 — spust z guestu: 'ashell adb start' " +
                "(bin=${binaryPath(context).absolutePath})"
        )
        return false
    }

    /**
     * Zastaví daemon pres jeho vlastni socket (SH_MODE_STOP) — bez adb.
     * App sice uid 2000 nespawne, ale bezicimu daemonu muze poslat STOP
     * pozadavek; daemon se pak ukonci sam (worker posle parentovi SIGTERM,
     * parent uklidi PID file). Funguje i kdyz wireless debugging neni
     * pripojeny (presne ten pripad, kdy stary `adb shell pkill` selhal).
     *
     * @return true kdyz daemon po pozadavku uz nebezi.
     */
    @JvmStatic
    fun stopDaemon(context: Context): Boolean {
        if (!status().running) {
            // Neni co zastavovat, ale uklid osirely PID file po sobe.
            try { File("/data/local/tmp/shelldaemon.pid").delete() } catch (_: Exception) {}
            return true
        }
        val token = ensureToken(context)
        val sent = try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", PORT), 2500)
                s.soTimeout = 5000
                val out = DataOutputStream(s.getOutputStream())
                out.writeInt(SH_MAGIC)
                out.writeByte(SH_MODE_STOP)
                writeBlob(out, token.toByteArray(Charsets.UTF_8))
                out.flush()
                // Precti potvrzeni (int32 + 2 bloby) — ne kriticke, jen aby
                // worker stihl poslat SIGTERM parentovi.
                try {
                    val inp = DataInputStream(s.getInputStream())
                    inp.readInt(); readBlob(inp); readBlob(inp)
                } catch (_: Exception) { }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "stopDaemon: STOP pozadavek selhal: ${e.message}")
            false
        }
        // Dej daemonu chvili na uklid, pak over, ze uz nebezi.
        if (sent) {
            repeat(20) {
                if (!status().running) return true
                try { Thread.sleep(100) } catch (_: InterruptedException) { return !status().running }
            }
        }
        // Fallback pro stary daemon bez SH_MODE_STOP (mode=3 propadne do
        // handle_exec a socket se resetuje) i pri token mismatch: posli
        // daemonu pres SH_MODE_EXEC prikaz `kill <pid>`. Worker, ktery spojeni
        // obsluhuje, i jeho command child bezi pod STEJNYM uid jako daemon
        // (uid 2000), takze smi poslat SIGTERM/SIGKILL svemu parentovi.
        // Zadny adb ani root — plne non-root, funguje i bez wireless debug.
        val pid = readDaemonPid()
        if (pid != null) {
            try {
                exec(context, "kill $pid 2>/dev/null; sleep 1; kill -9 $pid 2>/dev/null; true")
            } catch (e: Exception) {
                Log.w(TAG, "stopDaemon: EXEC kill fallback selhal: ${e.message}")
            }
            repeat(20) {
                if (!status().running) {
                    try { File("/data/local/tmp/shelldaemon.pid").delete() } catch (_: Exception) {}
                    return true
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { }
            }
        }
        // Posledni moznost: aspon uklid osirely PID file, aby status nelhal.
        try { File("/data/local/tmp/shelldaemon.pid").delete() } catch (_: Exception) {}
        return !status().running
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

    /**
     * Streamovany install APK pres shell_daemon (jako `adb install`).
     *
     * Daemon otevre `cmd package install -S <size> <args>` a preposle APK.
     * Odpovida se JSON `{stdout, stderr, exit_code, mode:"install"}`.
     *
     * @param apkFile lokalni cesta k APK (host filesystem, vidi ji appka)
     * @param args pm flagy (napr. "-r -g"); bez "install"
     */
    @JvmStatic
    fun install(context: Context, apkFile: File, args: String = ""): String {
        if (!status().running) {
            return """{"error":"shell_daemon not running (spust 'ashell adb start')","exit_code":-1}"""
        }
        if (!apkFile.exists()) {
            return """{"error":"APK neexistuje: ${apkFile.absolutePath}","exit_code":-1}"""
        }
        val token = ensureToken(context)
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", PORT), 2500)
                s.soTimeout = 600_000  // install muze trvat (velke APK)
                val out = DataOutputStream(s.getOutputStream())
                out.writeInt(SH_MAGIC)
                out.writeByte(SH_MODE_INSTALL)
                writeBlob(out, token.toByteArray(Charsets.UTF_8))
                writeBlob(out, args.toByteArray(Charsets.UTF_8))
                out.writeLong(apkFile.length())
                // stream APK po blokech
                apkFile.inputStream().use { ins ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
                out.flush()

                val inp = DataInputStream(s.getInputStream())
                val code = inp.readInt()
                val stdout = readBlob(inp)
                val stderr = readBlob(inp)
                JSONObject().apply {
                    put("stdout", stdout)
                    put("stderr", stderr)
                    put("exit_code", code)
                    put("mode", "install")
                }.toString()
            }
        } catch (e: Exception) {
            val m = (e.message ?: "install failed")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            "{\"error\":\"$m\",\"exit_code\":-1}"
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
