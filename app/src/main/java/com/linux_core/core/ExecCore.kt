package com.linux_core.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Výsledek parsování ashell.conf (čistý datový typ, testovatelný bez Androidu). */
data class AshellConfigParserResult(val envLines: List<String>, val blocked: Set<String>)

/**
 * Jediné místo s exec business logikou — volá ho LocalApiServer (HTTP, guest cesta)
 * i CoreBridgeService (Binder, app-to-app cesta). Žádná duplikace guardů.
 *
 * Obsah přesunut 1:1 z LocalApiServer.kt (SHELL_ALLOWLIST, DESTRUCTIVE_PATTERNS,
 * ashell config cluster, hostShellEnv) + hostExec/guestExec (těla bývalých
 * handleShell/handleProotExec). Chování musí zůstat identické.
 */
object ExecCore {

    private const val TAG = "ExecCore"

    // ── Přesunuto z LocalApiServer (allowlist guest API /proot/exec) ─────

    val SHELL_ALLOWLIST = setOf(
        // Diagnostic
        "ls", "cat", "echo", "printf", "pwd", "whoami", "id", "uname",
        "ps", "df", "du", "free", "uptime", "date", "which", "find",
        "grep", "egrep", "fgrep", "rg", "head", "tail", "wc", "sort", "cut",
        "tr", "sed", "awk", "xargs", "tee", "basename", "dirname",
        "readlink", "realpath", "stat", "file",
        // Network
        "ping", "ping6", "curl", "wget", "netstat", "ss", "ip", "ifconfig",
        "nslookup", "dig", "host", "traceroute", "tracepath", "mtr", "nc", "ncat",
        // Filesystem (safe subset — rm is allowed but guarded by DESTRUCTIVE_PATTERNS)
        "touch", "chmod", "chown", "ln", "mkdir", "rmdir",
        "tar", "gzip", "gunzip", "bzip2", "xz", "unzip", "zip",
        "cp", "mv", "rm",
        // Package management
        "apt", "apt-get", "dpkg", "pip", "pip3", "npm",
        // System / Android
        "env", "printenv", "getprop", "dmesg", "logcat",
        // Interpreters (inline code can still be dangerous — DESTRUCTIVE_PATTERNS also scans
        // inside the argument string so e.g. 'rm -rf /' inside a python -c is caught)
        "python", "python3", "perl",
        "bash", "sh", "zsh",
        // Project CLI
        "nh", "nethunter", "vpn-cli", "zkill",
        // Editors / pagers
        "nano", "less", "more", "vim", "vi",
        // Utility
        "free", "w", "who", "users", "last",
        "diff", "cmp", "patch",
        "clear", "reset", "history",
        "test", "expr",
        "sleep", "timeout",
        "dd"
    )

    // Destructive patterns — checked across the entire command string *after*
    // the allowlist gate, so they catch attempts like `python3 -c "import os; os.system('rm -rf /')"`.
    val DESTRUCTIVE_PATTERNS = listOf(
        "rm -rf /", "rm -rf /*", "rm -rf *", "rm -rf .", "rm -rf ~",
        "rm -fr /", "rm -fr /*", "rm -fr *", "rm -fr .", "rm -fr ~",
        "mkfs.", "mkfs ", "mkswap",
        "dd if=/dev/zero", "dd if=/dev/random", "dd if=/dev/urandom",
        ">/dev/sda", ">/dev/sdb", ">/dev/sdc", ">/dev/sdd",
        ">/dev/mem", ">/dev/kmem", ">/dev/port",
        "fdisk", "parted", "cfdisk",
        "reboot", "shutdown", "poweroff", "halt", "init 0", "init 6",
        "> /proc/", ">/proc/",
        ":(){ :|:& };:",  // fork bomb
        "chmod -R 0 /", "chown -R 0 /",
        "mv /", "mv /*",
        "cat /dev/sda", "cat /dev/sdb", "cat /dev/mem"
    )

    // ── Host shell (HTTP /shell i Binder hostShell) ──────────────────────

    /**
     * Blocklist gate (ashell.conf) + destructive patterns + env prefix + Runtime.exec.
     * Vrací JSON identický tvaru odpovědi /shell: {exit_code, stdout, stderr}
     * nebo {error:"..."} pro odmítnutí (HTTP handler odvodí status kód).
     */
    fun hostExec(ctx: Context, command: String): String {
        if (command.isEmpty()) return errJson("Command cannot be empty")
        if (command.length > 1024) return errJson("Command too long (max 1024 chars)")

        // ── 1. Blocklist gate (z ashell.conf — editace přes `ashell -e`) ──
        val cmdName = command
            .trim()
            .substringBefore(" ")
            .substringBefore("\t")
            .substringAfterLast("/")
        val cfg = loadAshellConfig(ctx)
        if (cmdName in cfg.blocked) return errJson("Command '$cmdName' is blocked by ashell.conf")

        // ── 2. Destructive patterns guard ───────────────────────────────
        val commandLower = command.lowercase()
        for (pattern in DESTRUCTIVE_PATTERNS) {
            if (commandLower.contains(pattern.lowercase())) {
                return errJson("Command blocked for security reasons")
            }
        }

        // Aplikuj env prefix z configu pred uzivatelsky prikaz
        // (export/unset/... jako .zshrc; exit code nese uzivatelsky prikaz,
        // protoze je posledni ve skriptu)
        val prefix = cfg.envLines.joinToString("\n") { expandAshellLine(it, ctx) }
        val fullCommand = if (prefix.isBlank()) command else "$prefix\n$command"

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCommand), hostShellEnv(ctx))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            "{\"exit_code\":$exitCode,\"stdout\":${JSONObject.quote(output)},\"stderr\":${JSONObject.quote(error)}}"
        } catch (e: Exception) {
            Log.e(TAG, "hostExec error: ${e.message}", e)
            errJson(e.message ?: "internal error")
        }
    }

    // ── Guest exec (HTTP /proot/exec i Binder prootExec) ─────────────────

    /**
     * Allowlist gate + destructive patterns + boot script one-shot exec v PRootu.
     * Vrací JSON identický tvaru odpovědi /proot/exec:
     * {exit_code, stdout, stderr, duration_ms[, timed_out]} nebo {error:"..."}.
     */
    fun guestExec(ctx: Context, distro: String, command: String, timeoutMs: Long): String {
        val command = command.trim()
        if (command.isEmpty()) return errJson("Command cannot be empty")
        if (command.length > 2048) return errJson("Command too long (max 2048 chars)")
        val distroLower = distro.trim().lowercase()
        if (distroLower !in listOf("kali", "parrot")) {
            return errJson("Invalid distro: '$distro'. Use 'kali' or 'parrot'.")
        }

        // ── 1. Allowlist gate ────────────────────────────────────────────
        val cmdName = command
            .substringBefore(" ")
            .substringBefore("\t")
            .substringAfterLast("/")
        if (cmdName !in SHELL_ALLOWLIST) {
            return errJson("Command '$cmdName' is not in the allowed commands list")
        }

        // ── 2. Destructive patterns guard ───────────────────────────────
        val commandLower = command.lowercase()
        for (pattern in DESTRUCTIVE_PATTERNS) {
            if (commandLower.contains(pattern.lowercase())) {
                return errJson("Command blocked for security reasons")
            }
        }

        // ── 3. Execute via boot script ─────────────────────────────────
        val bootScript = File(ctx.filesDir, "usr/bin/boot")
        if (!bootScript.exists() || !bootScript.canExecute()) {
            return errJson("Boot script not found. Please open a terminal session first to initialize PRoot.")
        }

        return try {
            val startTime = System.currentTimeMillis()
            val pb = ProcessBuilder("sh", bootScript.absolutePath, distroLower, "--", "sh", "-c", command)
            pb.directory(ctx.filesDir)
            pb.redirectErrorStream(false)
            val process = pb.start()

            // Read stdout and stderr concurrently to prevent pipe deadlock
            val stdoutReader = process.inputStream.bufferedReader()
            val stderrReader = process.errorStream.bufferedReader()

            val output = stdoutReader.readText()
            val error = stderrReader.readText()

            val completed = process.waitFor(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            val durationMs = System.currentTimeMillis() - startTime

            if (!completed) {
                process.destroyForcibly()
                "{\"exit_code\":-1,\"stdout\":${JSONObject.quote(output)},\"stderr\":${JSONObject.quote("Command timed out after ${timeoutMs}ms")},\"duration_ms\":$durationMs,\"timed_out\":true}"
            } else {
                val exitCode = process.exitValue()
                // Filter boot diagnostic lines from stdout
                val cleanOutput = output.lines()
                    .dropWhile { it.startsWith("[*]") || it.startsWith("[boot]") || it.isBlank() }
                    .joinToString("\n")
                "{\"exit_code\":$exitCode,\"stdout\":${JSONObject.quote(cleanOutput)},\"stderr\":${JSONObject.quote(error)},\"duration_ms\":$durationMs}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "guestExec error: ${e.message}", e)
            errJson(e.message ?: "internal error")
        }
    }

    // ── ashell config cluster — přesunuto z LocalApiServer ───────────────

    internal fun ashellConfigFile(ctx: Context): File = File(ctx.filesDir, "ashell.conf")

    /** Výchozí obsah configu — zrcadlí hostShellEnv() + bezpečnostní unset. */
    internal fun defaultAshellConfig(): String = listOf(
        "# ashell.conf — konfigurace host shellu (ashell)",
        "#",
        "# Aplikuje se před KAŽDÝM příkazem `ashell -c '...'` a při startu",
        "# interaktivního host shellu.",
        "#",
        "# Syntaxe:",
        "#   # ...            komentář",
        "#   block <cmd>      zakáže spuštění <cmd> přes /shell API (ashell -c)",
        "#   cokoliv jiného   sh řádek vykonaný před příkazem",
        "#                    (export FOO=bar, unset LD_LIBRARY_PATH, ...)",
        "#",
        "# Placeholder \${FILES_DIR} se expanduje na filesDir aplikace",
        "# (/data/data/com.linux_core/files) — config zůstává přenositelný.",
        "",
        "export HOME=\${FILES_DIR}",
        "export USER=app",
        "export PATH=\${FILES_DIR}/usr/bin:/system/bin:/system/xbin:/vendor/bin:\${FILES_DIR}",
        "export PREFIX=\${FILES_DIR}/usr",
        "export TERM=xterm-256color",
        "export ANDROID_DATA=/data",
        "export ANDROID_ROOT=/system",
        "unset LD_LIBRARY_PATH",
        "",
        "# Výchozí blokace (ukázka syntaxe; DESTRUCTIVE_PATTERNS blokují navíc)",
        "block reboot",
        "block shutdown",
        "block poweroff",
        ""
    ).joinToString("\n")

    /** Čistý parser ashell.conf (bez Contextu) — volatelný i z JVM unit testů. */
    fun parseAshellConfig(text: String): AshellConfigParserResult {
        val env = mutableListOf<String>()
        val blocked = mutableSetOf<String>()
        text.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> {}
                line == "block" -> {}  // blok bez jména — ignoruj (nezařazovat do env)
                line.startsWith("block ") || line.startsWith("block\t") ->
                    line.substring(5).trim().split(Regex("\\s+")).firstOrNull()?.let { if (it.isNotEmpty()) blocked.add(it) }
                else -> env.add(raw)
            }
        }
        return AshellConfigParserResult(env, blocked)
    }

    /** Načtený config: sh řádky pro env prefix + set blokovaných příkazů. */
    internal class AshellConfig(val envLines: List<String>, val blocked: Set<String>)

    internal fun loadAshellConfig(ctx: Context): AshellConfig {
        val f = ashellConfigFile(ctx)
        if (!f.exists()) {
            try { f.writeText(defaultAshellConfig()) } catch (_: Exception) {}
        }
        val parsed = try { parseAshellConfig(f.readText()) } catch (_: Exception) { parseAshellConfig("") }
        return AshellConfig(parsed.envLines, parsed.blocked)
    }

    /** Expanduje placeholder \${FILES_DIR} na reálnou cestu filesDir. */
    internal fun expandAshellLine(line: String, ctx: Context): String =
        line.replace("\${FILES_DIR}", ctx.filesDir.absolutePath)

    /**
     * Env pro host shell (Runtime.exec envp): usr/bin na začátku PATH
     * (GNU sed/nano/rsync/rg přebíjejí toybox), HOME/PREFIX na filesDir.
     */
    fun hostShellEnv(ctx: Context): Array<String> {
        val filesDir = ctx.filesDir
        val hostPrefixBin = File(filesDir, "usr/bin").absolutePath
        val hostPrefixLib = File(filesDir, "usr/lib").absolutePath
        val basePath = "/system/bin:/system/xbin:/vendor/bin"
        val fullPath = "$hostPrefixBin:$basePath:${filesDir.absolutePath}"
        return arrayOf(
            "HOME=${filesDir.absolutePath}",
            "USER=app",
            "PATH=$fullPath",
            "PREFIX=${File(filesDir, "usr").absolutePath}",
            "TERM=xterm-256color",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system"
        )
    }

    private fun errJson(msg: String): String =
        "{\"error\":${JSONObject.quote(msg)}}"
}
