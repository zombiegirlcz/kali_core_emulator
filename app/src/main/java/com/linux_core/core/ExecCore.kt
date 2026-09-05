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
 * Obsah přesunut 1:1 z LocalApiServer.kt (DESTRUCTIVE_PATTERNS,
 * ashell config cluster, hostShellEnv) + hostExec/guestExec (těla bývalých
 * handleShell/handleProotExec). Chování musí zůstat identické.
 *
 * Allowlist (SHELL_ALLOWLIST) byl ZRUŠEN 2026-08-26 — agent i guest mohou
 * spouštět libovolné příkazy; bezpečnostní síť zůstává DESTRUCTIVE_PATTERNS
 * + ashell blocklist (host) + distro validace (guest).
 */
object ExecCore {

    private const val TAG = "ExecCore"

    // Cesty k su binárce (root). Bind cross-app adresáře vyžaduje root.
    private val SU_PATHS = listOf(
        "/product/bin/su",
        "/system/xbin/su",
        "/system/bin/su",
        "/data/adb/ksu/bin/su",
        "/apex/com.android.runtime/bin/su",
        "/sbin/su",
        "/data/adb/magisk/su"
    )

    private fun findSu(ctx: Context): String? {
        for (path in SU_PATHS) {
            val f = File(path)
            if (f.exists() && f.canExecute()) return path
        }
        return null
    }

    // Destructive patterns — checked across the entire command string, so they
    // catch attempts like `python3 -c "import os; os.system('rm -rf /')"`.
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
     *
     * @param agentMode true = voláno z AI assistantu přes Binder bridge.
     *   Agent dostává VLASTNÍ čisté prostředí (defaultAshellConfig), nikoliv
     *   uživatelův ashell.conf, který může obsahovat elf_loader env
     *   (ROOTFS/LD_LIBRARY_PATH), jenž agenta mate ("cesty jsou rozbité").
     *   Blocklist + destructive guards platí pro obě cesty.
     */
    fun hostExec(ctx: Context, command: String, agentMode: Boolean = false): String {
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

        // Agent používá čisté defaultní env (žádné elf_loader z ashell.conf
        // uživatele); interaktivní host si nechá uživatelův config.
        val prefix = if (agentMode) {
            parseAshellConfig(defaultAshellConfig()).envLines
                .joinToString("\n") { expandAshellLine(it, ctx) }
        } else {
            cfg.envLines.joinToString("\n") { expandAshellLine(it, ctx) }
        }
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
        val (bootSub, bootImage) = if (distroLower.startsWith("docker/")) {
            "docker" to distroLower.substringAfter("docker/")
        } else {
            distroLower to null
        }
        val distroRoot = File(ctx.filesDir, "nh/distro/$distroLower")
        if (!distroRoot.exists() || !distroRoot.isDirectory) {
            return errJson("Distro '$distro' not found under nh/distro/. Use listDistros() to see installed distros.")
        }

        // ── 1. Destructive patterns guard (allowlist zrušen 2026-08-26) ──
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

        // Jen cross-app bind (bind_aiapp) — agentův guest dřív žádné extra mouny
        // neměl, takže neměníme jeho chování. Vyžaduje root -> guest pod su.
        val rootPrefs = ctx.getSharedPreferences("root_settings", Context.MODE_PRIVATE)
        val extraMounts = buildString {
            if (rootPrefs.getBoolean("bind_aiapp", false)) append(" -b /data/user/0/com.kali.aiassistant:/mnt/aiapp")
        }
        // Root jen pokud je zapnutý cross-app bind A zároveň dostupné su — jinak
        // zůstává guest v app sandboxu (bezpečnější, bez SELinux rizika na rootfs).
        val su = if (rootPrefs.getBoolean("bind_aiapp", false)) findSu(ctx) else null

        return try {
            val startTime = System.currentTimeMillis()
            // Příkaz i wrapper píšeme do souborů — vyhneme se quoting problému a
            // pod su funguje i příkaz s mezerami/uvozovkami. NH_EXTRA_MOUNTS se
            // předá přes export uvnitř wrapperu (čte ho boot skript).
            val cmdFile = File(ctx.cacheDir, "aiexec_cmd_${System.currentTimeMillis()}.sh").apply {
                writeText(command)
                setExecutable(true)
            }
            val wrapper = File(ctx.cacheDir, "aiexec_wrap_${System.currentTimeMillis()}.sh").apply {
                writeText(
                    "#!/system/bin/sh\n" +
                    "export NH_EXTRA_MOUNTS='$extraMounts'\n" +
                    "exec sh ${bootScript.absolutePath} $bootSub" +
                    (if (bootImage != null) " $bootImage" else "") +
                    " -- sh -c 'sh ${cmdFile.absolutePath}'\n"
                )
                setExecutable(true)
            }
            try {
                val pb = if (su != null) {
                    ProcessBuilder(su, "-c", "sh ${wrapper.absolutePath}")
                } else {
                    ProcessBuilder("sh", wrapper.absolutePath)
                }
                pb.directory(ctx.filesDir)
                pb.redirectErrorStream(false)
                val process = pb.start()

                // Read stdout and stderr concurrently to prevent pipe deadlock
                var output = ""
                val stdoutThread = Thread {
                    output = process.inputStream.bufferedReader().readText()
                }
                stdoutThread.isDaemon = true
                stdoutThread.start()
                val error = process.errorStream.bufferedReader().readText()

                val completed = process.waitFor(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                val durationMs = System.currentTimeMillis() - startTime

                if (!completed) {
                    process.destroyForcibly()
                    stdoutThread.join(2000)
                    "{\"exit_code\":-1,\"stdout\":${JSONObject.quote(output)},\"stderr\":${JSONObject.quote("Command timed out after ${timeoutMs}ms")},\"duration_ms\":$durationMs,\"timed_out\":true}"
                } else {
                    stdoutThread.join(2000)
                    val exitCode = process.exitValue()
                    // Filter boot diagnostic lines from stdout
                    val cleanOutput = output.lines()
                        .dropWhile { it.startsWith("[*]") || it.startsWith("[boot]") || it.isBlank() }
                        .joinToString("\n")
                    "{\"exit_code\":$exitCode,\"stdout\":${JSONObject.quote(cleanOutput)},\"stderr\":${JSONObject.quote(error)},\"duration_ms\":$durationMs}"
                }
            } finally {
                cmdFile.delete()
                wrapper.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "guestExec error: ${e.message}", e)
            errJson(e.message ?: "internal error")
        }
    }

    /** Vrátí JSON pole nainstalovaných distrí (podadresáře nh/distro/, docker jako "docker/<image>"). */
    fun listDistros(ctx: Context): String {
        val distroDir = File(ctx.filesDir, "nh/distro")
        val ids = mutableListOf<String>()
        distroDir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                when (f.name) {
                    "docker" -> f.listFiles()?.forEach { if (it.isDirectory) ids.add("docker/${it.name}") }
                    "backup" -> { /* přeskočit */ }
                    else -> ids.add(f.name)
                }
            }
        }
        return "[" + ids.joinToString(",") { JSONObject.quote(it) } + "]"
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

    // ── ELF direct exec (EXPERIMENTÁLNÍ, Binder elfExec) ─────────────────

    /**
     * Přímé spuštění glibc binárky z rootfs na Android hostu přes `elf`
     * wrapper (elf_loader --ownall) — bypass PRoot cold-startu.
     *
     * Guardy: STEJNÉ jako guest exec (allowlist prvního slova + destructive
     * patterns) — jde o reálný filesystem, žádný sandbox!
     *
     * Wrapper se hledá na kandidátních cestách (/system/bin/elf z Magisk
     * modulu, files/usr/bin/elf z manuálního deploymu), ROOTFS na prvním
     * existujícím files/nh/distro/{kali,parrot}. Wrapper si sám řeší
     * LD_LIBRARY_PATH pořadí (bionic první, glibc za nimi) i resolve()
     * jména binárky v rootfs.
     */
    fun elfExec(ctx: Context, command: String, timeoutMs: Long): String {
        val command = command.trim()
        if (command.isEmpty()) return errJson("Command cannot be empty")
        if (command.length > 2048) return errJson("Command too long (max 2048 chars)")

        // ── 1. Destructive patterns guard (reálný FS!, allowlist zrušen) ─
        val commandLower = command.lowercase()
        for (pattern in DESTRUCTIVE_PATTERNS) {
            if (commandLower.contains(pattern.lowercase())) {
                return errJson("Command blocked for security reasons")
            }
        }

        // ── 3. Najdi wrapper a rootfs ─────────────────────────────────
        val wrapper = listOf("/system/bin/elf", File(ctx.filesDir, "usr/bin/elf").absolutePath)
            .firstOrNull { File(it).let { f -> f.exists() && f.canExecute() } }
            ?: return errJson(
                "ELF wrapper not found (tried /system/bin/elf, files/usr/bin/elf). " +
                "Install the parrot_elf_loader Magisk module first."
            )
        val rootfs = listOf("kali", "parrot")
            .map { File(ctx.filesDir, "nh/distro/$it") }
            .firstOrNull { it.isDirectory }
            ?.absolutePath
            ?: return errJson("No rootfs found under files/nh/distro/{kali,parrot}")

        // ── 4. Execute: sh -c "$wrapper $command" s ROOTFS env ─────────
        return try {
            val startTime = System.currentTimeMillis()
            val pb = ProcessBuilder("sh", "-c", "$wrapper $command")
            pb.directory(ctx.filesDir)
            pb.redirectErrorStream(false)
            pb.environment().apply {
                put("ROOTFS", rootfs)
            }
            val process = pb.start()

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
                "{\"exit_code\":$exitCode,\"stdout\":${JSONObject.quote(output)},\"stderr\":${JSONObject.quote(error)},\"duration_ms\":$durationMs}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "elfExec error: ${e.message}", e)
            errJson(e.message ?: "internal error")
        }
    }

    private fun errJson(msg: String): String =
        "{\"error\":${JSONObject.quote(msg)}}"
}
