package com.linux_core.core.terminal

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Doručení sdíleného odkazu/textu do guest programu ze proměnné `SHARE`.
 *
 * Tři cesty, v tomhle pořadí (první, která je použitelná, vyhrává):
 *
 *  1. **běžící session v terminálu** (`NETHUNTER_SESSION_ID` žije v
 *     `TerminalService`) → příkaz se vepíše do té session a odentruje, takže
 *     uživatel vidí výstup live tam, kde `SHARE` nastavil.
 *  2. **attach session** (`boot --attach`, má `ctl` FIFO) → `boot -p <sid> -q`,
 *     tj. příkaz běží ve stejném PRoot kontejneru, výstup do `tmp/boot-<sid>.log`.
 *  3. **nová headless session** → `boot <distro> -- "cd … && …"`, výstup do
 *     `tmp/share-<ts>.log`.
 */
object ShareDelivery {
    private const val TAG = "ShareDelivery"

    /** Co se s odkazem stalo — volající z toho dělá toast. */
    data class Result(val ok: Boolean, val how: String, val detail: String = "")

    fun deliver(context: Context, target: ShareTarget, payload: String): Result {
        val line = buildCommandLine(target, payload)
        val sid = findSessionId(context.filesDir, target.pid)

        // 1) live session v appce
        val sessionId = target.sessionId
        if (sessionId != null && TerminalService.runInSession(sessionId, line)) {
            Log.i(TAG, "doručeno do session $sessionId (pid=${target.pid}, zdroj=${target.source})")
            return Result(true, "session", target.command)
        }

        // 2) attach session (boot -p)
        if (sid != null && isAttachSession(context.filesDir, sid)) {
            val rc = runBoot(context, listOf("-p", sid, "-q", "--", line), null)
            Log.i(TAG, "boot -p $sid rc=$rc")
            if (rc == 0) return Result(true, "attach", target.command)
        }

        // 3) nová headless session
        val distro = target.distro ?: activeDistro(context.filesDir)
        val args = mutableListOf<String>()
        if (distro.startsWith("docker/")) {
            args.add("docker")
            args.add(distro.removePrefix("docker/"))
        } else {
            args.add(distro)
        }
        args.add(line)
        val log = File(context.filesDir, "tmp/share-${System.currentTimeMillis() / 1000}.log")
        val rc = runBoot(context, args, log)
        Log.i(TAG, "headless boot $distro rc=$rc log=${log.absolutePath}")
        return if (rc == 0) {
            Result(true, "headless", log.name)
        } else {
            Result(false, "headless", "boot skončil s kódem $rc (log ${log.name})")
        }
    }

    /** `cd '<cwd>' && <SHARE> '<payload>'` — cwd i payload jsou shell-quoted. */
    internal fun buildCommandLine(target: ShareTarget, payload: String): String {
        val cmd = target.command.trim()
        return "cd ${shellQuote(target.cwd)} 2>/dev/null; $cmd ${shellQuote(payload)}"
    }

    /** POSIX single-quote escaping (jediný bezpečný způsob pro cizí vstup). */
    internal fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Session id z `nh/sessions/<sid>.pid`, jehož PID je předkem `pid`
     * (guest proces je potomek `boot`/proot procesu té session).
     */
    internal fun findSessionId(filesDir: File, pid: Int): String? {
        val dir = File(filesDir, "nh/sessions")
        val byPid = HashMap<Int, String>()
        dir.listFiles { f -> f.name.endsWith(".pid") }?.forEach { f ->
            f.readText().trim().toIntOrNull()?.let { byPid[it] = f.name.removeSuffix(".pid") }
        }
        if (byPid.isEmpty()) return null
        var cur = pid
        var hops = 0
        while (cur > 1 && hops < 64) {
            byPid[cur]?.let { return it }
            cur = parentPid(cur) ?: return null
            hops++
        }
        return null
    }

    /** PPID ze 4. pole `/proc/<pid>/stat` (comm v závorkách může mít mezery). */
    internal fun parentPid(pid: Int): Int? =
        try {
            val stat = File("/proc/$pid/stat").readText()
            val afterComm = stat.substringAfterLast(')').trim()
            afterComm.split(' ')[1].toIntOrNull()
        } catch (_: Exception) {
            null
        }

    /** Attach session má `attach=1` v `.info` a `ctl` FIFO v `ipc/sessions/<sid>`. */
    private fun isAttachSession(filesDir: File, sid: String): Boolean =
        try {
            File(filesDir, "ipc/sessions/$sid/ctl").exists() &&
                File(filesDir, "nh/sessions/$sid.info").readText().contains("attach=1")
        } catch (_: Exception) {
            false
        }

    /** Aktivní distro z markeru `nh/.active_distro` (stejný zdroj jako UI). */
    internal fun activeDistro(filesDir: File): String {
        val spec =
            try {
                File(filesDir, "nh/.active_distro").takeIf { it.exists() }?.readText()?.trim().orEmpty()
            } catch (_: Exception) {
                ""
            }
        return when {
            spec.startsWith("docker:") -> "docker/${spec.removePrefix("docker:")}"
            spec.isNotEmpty() -> spec
            File(filesDir, "nh/distro/parrot").exists() -> "parrot"
            else -> "kali"
        }
    }

    /**
     * Spustí hostitelský `boot` launcher. Env se dědí z procesu appky (Android
     * proměnné, které guest v módu D potřebuje); `NH_*` si boot dotáhne sám
     * z `nh/root_env` — proto se tu **nenastavuje** `NH_ENV_FROM_APP`.
     */
    private fun runBoot(context: Context, args: List<String>, log: File?): Int {
        val boot = File(context.filesDir, "usr/bin/boot")
        if (!boot.exists()) {
            Log.w(TAG, "boot launcher chybí: ${boot.absolutePath}")
            return -1
        }
        // setsid: potomek nesmí umřít se ShareReceiverActivity / zabitím appky
        // (headless `yt-dlp` běží minuty). Stejný trik jako `ashell adb start`.
        val setsid = File("/system/bin/setsid").takeIf { it.exists() }?.absolutePath
        return try {
            val argv = mutableListOf<String>()
            if (setsid != null) argv.add(setsid)
            argv.add("/system/bin/sh")
            argv.add(boot.absolutePath)
            argv.addAll(args)
            val pb = ProcessBuilder(argv)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            if (log != null) {
                log.parentFile?.mkdirs()
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
            }
            val p = pb.start()
            // `-q` i headless se odpojí samy; čekáme jen krátce na chybu startu.
            if (p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) p.exitValue() else 0
        } catch (e: Exception) {
            Log.e(TAG, "runBoot selhal: ${e.message}", e)
            -1
        }
    }
}
