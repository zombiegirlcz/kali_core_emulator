package com.linux_core.core.terminal

import android.util.Log
import java.io.File

/**
 * Cíl sdílení: program z guest proměnné `SHARE`, kterému se předá sdílený
 * odkaz/text z Android share sheetu (viz [ShareReceiverActivity]).
 *
 * V guestu stačí `export SHARE="yt-dlp -P ~/dl"` — sdílení z Chrome pak
 * spustí `yt-dlp -P ~/dl <url>` v tom distru (a pokud to jde, přímo v té
 * session, viz [ShareDelivery]).
 */
data class ShareTarget(
    /** PID guest procesu (PRoot nemá PID namespace → host PID == guest PID). */
    val pid: Int,
    /** Hodnota `SHARE` = příkaz (může mít argumenty, expanduje ho guest shell). */
    val command: String,
    /** Guest cwd, do kterého se před spuštěním `cd`ne. */
    val cwd: String,
    /** `NH_DISTRO` (kali|parrot|docker/<image>), pokud ho proces zná. */
    val distro: String?,
    /** `NETHUNTER_SESSION_ID` appky — identifikuje session v `TerminalService`. */
    val sessionId: String?,
    /** `hook` (prompt snapshot) nebo `environ` (sken /proc) — jen pro log/diagnostiku. */
    val source: String,
)

/**
 * Hledání [ShareTarget] ze dvou zdrojů:
 *
 *  1. **prompt snapshoty** `filesDir/ipc/share/<pid>` — guest zshrc/profile hook
 *     je zapíše, když se `SHARE` (nebo `$PWD` při nastavené `SHARE`) změní.
 *     Nutné proto, že `/proc/<pid>/environ` je **snapshot z `execve()`**: `export`
 *     v už běžícím shellu se do něj nikdy nedostane. Hook funguje v módu D
 *     (`ipc` je bindnuté jako `/run/host_ipc`), v I/M módu zápis tiše selže.
 *  2. **sken `/proc/<pid>/environ`** — fallback pro `SHARE=… program` (proměnná
 *     předaná při exec) a pro izolované módy bez `/run/host_ipc`.
 *
 * Když kandidátů víc, vyhrává nejnovější (mtime snapshotu / nejvyšší PID).
 */
class ShareTargetResolver(private val filesDir: File) {
    companion object {
        private const val TAG = "ShareTarget"
        private const val MAX_PROC_SCAN = 4096

        /** Parsuje `KEY=VALUE` řádky prompt snapshotu. */
        fun parseSnapshot(text: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (line in text.lineSequence()) {
                val i = line.indexOf('=')
                if (i <= 0) continue
                out[line.substring(0, i)] = line.substring(i + 1)
            }
            return out
        }

        /** Parsuje NUL-separovaný obsah `/proc/<pid>/environ`. */
        fun parseEnviron(raw: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (entry in raw.split('\u0000')) {
                if (entry.isEmpty()) continue
                val i = entry.indexOf('=')
                if (i <= 0) continue
                out[entry.substring(0, i)] = entry.substring(i + 1)
            }
            return out
        }

        /**
         * Guest cesta z hostitelského cwd: odřízne prefix rootfs.
         * `…/files/nh/distro/kali/root/dl` + rootfs `…/nh/distro/kali` → `/root/dl`.
         * Když cwd pod rootfs neleží (bind mount), vrátí `/root`.
         */
        fun guestCwd(hostCwd: String?, rootfs: String?): String {
            if (hostCwd.isNullOrEmpty()) return "/root"
            if (!rootfs.isNullOrEmpty() && hostCwd.startsWith(rootfs)) {
                val rel = hostCwd.removePrefix(rootfs)
                return if (rel.isEmpty()) "/" else rel
            }
            return "/root"
        }
    }

    /** Vrátí nejvhodnější cíl, nebo null, když žádný guest proces `SHARE` nemá. */
    fun resolve(): ShareTarget? = fromSnapshots() ?: fromProcEnviron()

    // ── 1. prompt snapshoty (ipc/share/<pid>) ────────────────────────────
    private fun fromSnapshots(): ShareTarget? {
        val dir = File(filesDir, "nh/share")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return null
        for (f in files) {
            val pid = f.name.toIntOrNull()
            if (pid == null || !File("/proc/$pid").exists()) {
                // Mrtvý/neplatný záznam — hook po sobě neuklidí (shell může spadnout).
                f.delete()
                continue
            }
            val kv =
                try {
                    parseSnapshot(f.readText())
                } catch (e: Exception) {
                    Log.w(TAG, "snapshot $f nečitelný: ${e.message}")
                    continue
                }
            val cmd = kv["cmd"]?.trim().orEmpty()
            if (cmd.isEmpty()) continue
            return ShareTarget(
                pid = pid,
                command = cmd,
                cwd = kv["cwd"]?.takeIf { it.isNotEmpty() } ?: "/root",
                distro = kv["distro"]?.takeIf { it.isNotEmpty() },
                sessionId = kv["session"]?.takeIf { it.isNotEmpty() },
                source = "hook",
            )
        }
        return null
    }

    // ── 2. sken /proc/<pid>/environ ──────────────────────────────────────
    private fun fromProcEnviron(): ShareTarget? {
        val pids =
            File("/proc").list()
                ?.mapNotNull { it.toIntOrNull() }
                ?.sortedDescending()
                ?.take(MAX_PROC_SCAN)
                ?: return null
        for (pid in pids) {
            val env =
                try {
                    val f = File("/proc/$pid/environ")
                    if (!f.canRead()) continue
                    parseEnviron(f.readText())
                } catch (_: Exception) {
                    // Cizí UID (EACCES) nebo proces zanikl mezi listem a čtením.
                    continue
                }
            val cmd = env["SHARE"]?.trim().orEmpty()
            if (cmd.isEmpty()) continue
            val distro = env["NH_DISTRO"]
            val hostCwd =
                try {
                    File("/proc/$pid/cwd").canonicalPath
                } catch (_: Exception) {
                    null
                }
            return ShareTarget(
                pid = pid,
                command = cmd,
                cwd = guestCwd(hostCwd, distro?.let { rootfsPath(it) }),
                distro = distro,
                sessionId = env["NETHUNTER_SESSION_ID"],
                source = "environ",
            )
        }
        return null
    }

    /** Host cesta rootfs daného distra (`kali` → `…/files/nh/distro/kali`). */
    fun rootfsPath(distro: String): String =
        File(filesDir, "nh/distro/$distro").absolutePath
}
