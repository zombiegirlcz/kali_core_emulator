package com.linux_core.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

data class ShizukuStatus(
    val running: Boolean,
    val pid: Int? = null,
    val uid: Int? = null,
    val port: Int? = null,
    val mode: String = "none",   // "existing" (Shizuku app), "self" (our server via su/adb), "none"
    val suAvailable: Boolean = false,
    val shizukuApkPath: String? = null,
    val adbAvailable: Boolean = false,
    val adbWirelessPaired: Boolean = false
)

/**
 * Režim privilege eskalace pro `nh shi start --root|--shell`.
 *
 * - [ROOT]  — `su 0 -c <cmd>`; plná root práva (uid 0)
 * - [SHELL] — `su 2000 -c <cmd>`; práva shell UID (stejná jako Shizuku/adb)
 * - [NONE]  — bez `su`; příkaz jde přes Shizuku server (rish; vyžaduje ShizukuProvider)
 */
enum class ShizukuMode(val uid: Int?) {
    ROOT(0),
    SHELL(2000),
    NONE(null),
    ;

    fun describe(): String =
        when (this) {
            ROOT -> "root (uid 0)"
            SHELL -> "shell (uid 2000)"
            NONE -> "none (Shizuku server)"
        }
}

/**
 * Jak nastartovat bundlovaný Shizuku server pro režim `--none` (non-root cesta).
 *
 * - [ALREADY_RUNNING] — už běží, nic nedělat
 * - [VIA_SU]          — root k dispozici: nakopírovat do /data/local/tmp a spustit pod shell UID
 * - [VIA_ADB]         — bez rootu: použít adb (wireless debugging) k přenosu + spuštění
 * - [UNAVAILABLE]     — ani su, ani adb; eskalace není možná
 */
enum class ServerStartPlan {
    ALREADY_RUNNING,
    VIA_SU,
    VIA_ADB,
    UNAVAILABLE,
}

object ShizukuManager {
    private const val TAG = "ShizukuManager"

    // Asset paths
    // Cesty v APK (od 2026-09-18): .so -> assets/usr/lib, skripty/dex -> assets/usr/bin
    private const val ASSET_SERVER = "usr/lib/libshizuku.so"
    private const val ASSET_RISH_DEX = "usr/bin/rish_shizuku.dex"
    private const val ASSET_APK = "usr/lib/shizuku.apk"

    // FilesDir paths
    private const val SERVER_BIN = "shizuku-server"
    private const val RISH_DEX = "rish_shizuku.dex"
    private const val BUNDLED_APK = "shizuku.apk"
    private const val PID_FILE = "shizuku.pid"

    // Shizuku manager package
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    // Kandidáti na su binárku (Magisk ji drží v /product/bin jako symlink na magisk).
    private val SU_PATHS =
        listOf(
            "/product/bin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
            "/sbin/su",
        )

    /**
     * Sestaví argv pro `su <uid> -c <cmd>` v daném režimu.
     *
     * Vrací seznam bez shell escapování — volající použije [ProcessBuilder],
     * který argv předá bez interpretace shell metaznaků (žádná injection).
     *
     * [NONE] vrací prázdný seznam (volající místo toho použije Shizuku/rish cestu).
     */
    @JvmStatic
    fun buildSuArgv(
        suBin: String,
        mode: ShizukuMode,
        command: String,
    ): List<String> =
        when (mode) {
            ShizukuMode.ROOT -> listOf(suBin, "0", "-c", command)
            ShizukuMode.SHELL -> listOf(suBin, "2000", "-c", command)
            ShizukuMode.NONE -> emptyList()
        }

    /** Mapuje CLI přepínač (`--root`, `root`, `--shell`, `shell`) na režim; neznámý → null. */
    @JvmStatic
    fun parseMode(arg: String): ShizukuMode? =
        when (arg.trim().lowercase()) {
            "--root", "root", "-r" -> ShizukuMode.ROOT
            "--shell", "shell", "-s" -> ShizukuMode.SHELL
            "--none", "none", "--server", "server" -> ShizukuMode.NONE
            else -> null
        }

    /** Najde první spustitelnou `su` binárku; null když žádná. */
    private fun findSu(): String? =
        SU_PATHS.firstOrNull { File(it).exists() && File(it).canExecute() }

    /** Veřejný přístup k nalezené `su` binárce (pro /shizuku/status); null když žádná. */
    @JvmStatic
    fun suPath(): String? = findSu()

    // ─── Startovací strategie Shizuku serveru (non-root cesta) ───────────────

    /**
     * Rozhodne, jak nastartovat server. Čistá funkce — testovatelná bez zařízení.
     * Pořadí priorit: už běží → su (spolehlivější) → adb → nedostupné.
     */
    @JvmStatic
    fun planServerStart(
        running: Boolean,
        suAvailable: Boolean,
        adbAvailable: Boolean,
    ): ServerStartPlan =
        when {
            running -> ServerStartPlan.ALREADY_RUNNING
            suAvailable -> ServerStartPlan.VIA_SU
            adbAvailable -> ServerStartPlan.VIA_ADB
            else -> ServerStartPlan.UNAVAILABLE
        }

    /**
     * Příkazy, které jako root nakopírují server + APK z app filesDir do
     * [tmpDir] (`/data/local/tmp`), kde k nim má shell UID přístup a je
     * spustitelný (app filesDir je pro shell UID `rwx------`).
     */
    @JvmStatic
    fun buildSuServerStartCommands(
        filesDir: String,
        tmpDir: String = "/data/local/tmp",
    ): List<String> =
        listOf(
            "cp $filesDir/shizuku-server $tmpDir/shizuku-server",
            "cp $filesDir/shizuku.apk $tmpDir/shizuku.apk",
            // Starter cte .so z <apk_dir>/lib/<abi>/ (viz strings libshizuku.so:
            // "%s/lib/%s"). Bez nich app_process child umre:
            //   UnsatisfiedLinkError: dlopen failed: library
            //   "/data/local/tmp/lib/arm64/librish.so" not found
            //   at rikka.shizuku.server.ShizukuService.<init>
            "mkdir -p $tmpDir/lib/arm64",
            "cp $filesDir/usr/lib/librish.so $tmpDir/lib/arm64/librish.so",
            "cp $filesDir/usr/lib/libadb.so $tmpDir/lib/arm64/libadb.so",
            "cp $filesDir/usr/lib/libshizuku.so $tmpDir/lib/arm64/libshizuku.so",
            "chmod 755 $tmpDir/shizuku-server",
            "chmod 644 $tmpDir/shizuku.apk",
            "chmod 644 $tmpDir/lib/arm64/librish.so $tmpDir/lib/arm64/libadb.so $tmpDir/lib/arm64/libshizuku.so",
        )

    /**
     * Příkaz spouštějící server s odkazem na APK (starter `--apk=` z něj čte
     * Java třídy serveru). Volající přidá `su 2000 -c` a přesměrování na log.
     */
    @JvmStatic
    fun buildSuServerStartCommand(tmpDir: String = "/data/local/tmp"): String =
        "$tmpDir/shizuku-server --apk=$tmpDir/shizuku.apk"

    /** Je zapnuté bezdrátové ladění? (adbd běží) */
    private fun isAdbAvailable(): Boolean =
        try {
            Runtime.getRuntime().exec(arrayOf("getprop", "init.svc.adbd"))
                .inputStream.bufferedReader().readText().trim() == "running"
        } catch (_: Exception) {
            false
        }

    /**
     * Vrací true, pokud zařízení má funkční `su` (Magisk apod.).
     * Zkouší jen spuštění, ne root shell — bezpečné i pro non-root zařízení.
     */
    @JvmStatic
    fun isSuAvailable(): Boolean =
        try {
            val su = findSu() ?: return false
            val p = ProcessBuilder(su, "-c", "id").redirectErrorStream(true).start()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            p.exitValue() == 0
        } catch (_: Exception) {
            false
        }

    /**
     * Spustí příkaz pod `su <uid> -c` a vrátí JSON ve stejném tvaru jako
     * [exec] (`{stdout, exit_code}` / `{error, exit_code}`).
     *
     * [ShizukuMode.NONE] deleguje na [exec] (Shizuku/rish cesta).
     */
    @JvmStatic
    fun execAs(
        context: Context,
        mode: ShizukuMode,
        command: String,
    ): String {
        if (mode == ShizukuMode.NONE) return exec(context, command)
        val su = findSu() ?: return """{"error":"su binary not found","exit_code":-1}"""
        return try {
            val argv = buildSuArgv(su, mode, command)
            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            """{"stdout":"${output.escapeJson()}","exit_code":$exitCode,"mode":"${mode.name.lowercase()}"}"""
        } catch (e: Exception) {
            """{"error":"${(e.message ?: "exec failed").escapeJson()}","exit_code":-1}"""
        }
    }

    /**
     * Deploy the native server binary from assets to filesDir.
     * Safe to call multiple times — only deploys if missing, zero-length, or asset size changed.
     */
    private fun deployServer(context: Context): File? {
        val target = File(context.filesDir, SERVER_BIN)
        var shouldDeploy = !target.exists() || target.length() == 0L
        if (!shouldDeploy) {
            // Check if asset is newer (different size)
            try {
                val assetSize = context.assets.open(ASSET_SERVER).use { it.available().toLong() }
                if (target.length() != assetSize) shouldDeploy = true
            } catch (e: Exception) {
                shouldDeploy = true
            }
        }
        if (!shouldDeploy) {
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
     * Ensure the Shizuku server binary is deployed (without starting it).
     * Call early (e.g. from status() or app startup) so the binary is ready.
     */
    @JvmStatic
    fun ensureServerDeployed(context: Context): Boolean {
        return deployServer(context) != null
    }

    /**
     * Deploy rish dex to filesDir for exec().
     */
    private fun deployDex(context: Context): File? {
        val target = File(context.filesDir, RISH_DEX)
        if (target.exists() && target.length() > 0L) {
            target.setReadable(true, false)
            return target
        }
        return try {
            context.assets.open(ASSET_RISH_DEX).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, false)
            Log.i(TAG, "Deployed rish dex (${target.length()} bytes)")
            target
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy rish dex: ${e.message}")
            null
        }
    }

    /**
     * Deploy rish script and dex into PRoot guest.
     */
    @JvmStatic
    fun deployRish(context: Context, rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin")
        if (!binDir.exists()) binDir.mkdirs()

        val rishScript = File(binDir, "shizuku")
        try {
            context.assets.open("usr/bin/rish.sh").use { input ->
                rishScript.outputStream().use { output -> input.copyTo(output) }
            }
            rishScript.setExecutable(true, false)
            rishScript.setReadable(true, false)
            Log.i(TAG, "Deployed rish wrapper to ${rishScript.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy rish script: ${e.message}")
        }

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
     * Check the current Shizuku status — server running,
     * Shizuku APK installed, ADB available.
     */
    @JvmStatic
    fun status(context: Context): ShizukuStatus {
        // Ensure server binary is deployed on first status check
        ensureServerDeployed(context)

        var running = false
        var pid: Int? = null
        var mode = "none"

        // 1. Check via PID file (our own server)
        val pidFile = File(context.filesDir, PID_FILE)
        if (pidFile.exists()) {
            val storedPid = try { pidFile.readText().trim().toInt() } catch (e: Exception) { null }
            if (storedPid != null && processExists(storedPid)) {
                running = true; pid = storedPid; mode = "self"
            } else {
                pidFile.delete()
            }
        }

        // 2. Check existing Shizuku server (Shizuku app or other)
        if (!running) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                    "ps -ef 2>/dev/null | grep -i '[s]hizuku' | head -5"))
                val output = proc.inputStream.bufferedReader().readText()
                if (output.isNotBlank()) {
                    // Parse PID — format depends on Android version
                    val m = Regex("""(root|shell|system)\s+(\d+)""").find(output)
                    if (m != null) {
                        running = true; pid = m.groupValues[2].toIntOrNull(); mode = "existing"
                    }
                }
            } catch (e: Exception) { /* ignore */ }
        }

        // 3. su availability is disabled/removed completely
        val suAvailable = false

        // 4. Check Shizuku APK
        val shizukuApkPath = try {
            val ai = context.packageManager.getApplicationInfo(SHIZUKU_PKG, 0)
            ai.sourceDir
        } catch (e: PackageManager.NameNotFoundException) { null }

        // 5. Check ADB debugging — adbd running (via getprop, works without special permissions)
        val adbAvailable = try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", "init.svc.adbd"))
            p.inputStream.bufferedReader().readText().trim() == "running"
        } catch (e: Exception) { false }

        // 6. Check wireless debugging paired status (Android 11+)
        val adbWirelessPaired = if (adbAvailable) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.wireless.paired"))
                p.inputStream.bufferedReader().readText().trim() == "true"
            } catch (e: Exception) { false }
        } else false

        return ShizukuStatus(
            running = running,
            pid = pid,
            mode = mode,
            suAvailable = suAvailable,
            shizukuApkPath = shizukuApkPath,
            adbAvailable = adbAvailable,
            adbWirelessPaired = adbWirelessPaired
        )
    }

    private fun processExists(pid: Int): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ls /proc/$pid/status 2>/dev/null"))
            p.inputStream.bufferedReader().readText().isNotEmpty()
        } catch (e: Exception) { false }
    }

    /**
     * Execute a command through Shizuku (via rish shell).
     */
    @JvmStatic
    fun exec(context: Context, command: String): String {
        val st = status(context)
        if (st.running && st.mode != "none") {
            // Use rish
            return try {
                val dexFile = deployDex(context) ?: return """{"error":"dex deploy failed","exit_code":-1}"""
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
                """{"stdout":"${output.escapeJson()}","exit_code":$exitCode}"""
            } catch (e: Exception) {
                """{"error":"${e.message}","exit_code":-1}"""
            }
        }
        return """{"error":"No privileged execution method available","exit_code":-1}"""
    }

    /**
     * Start the Shizuku server.
     *
     * Strategy (in order):
     * 1. Already running → return true
     * 2. ADB available → start via ADB shell
     * 3. Otherwise → return false (caller shows pairing UI)
     */
    @JvmStatic
    fun startServer(context: Context): Boolean {
        val st = status(context)
        if (st.running) {
            Log.i(TAG, "Shizuku server already running (mode=${st.mode})")
            return true
        }

        // Strategy 2: ADB
        if (st.adbAvailable) {
            Log.i(TAG, "Attempting to start Shizuku server via ADB")
            return startWithAdb(context, st.shizukuApkPath)
        }

        Log.w(TAG, "No startup method available (no Shizuku, no ADB)")
        return false
    }

    /**
     * Start Shizuku server on a background thread.
     * Returns immediately; result is delivered via the callback.
     */
    @JvmStatic
    fun startServerAsync(context: Context, callback: ((Boolean) -> Unit)? = null) {
        Thread {
            val result = startServer(context)
            android.os.Handler(context.mainLooper).post {
                callback?.invoke(result)
            }
        }.start()
    }



    /**
     * Start Shizuku server via ADB (requires wireless debugging paired with a computer).
     *
     * This method:
     * 1. Checks if adbd is running (wireless debugging enabled)
     * 2. Shows the exact ADB command to run on the paired computer
     * 3. Returns true if adbd is running (server CAN be started via ADB)
     *
     * Note: The app CANNOT execute ADB commands on the host computer.
     * The user MUST run the shown command on their computer.
     * If Termux with android-tools is installed, we could try that.
     */
    private fun startWithAdb(context: Context, apkPath: String?): Boolean {
        val serverBin = deployServer(context) ?: return false
        val bundledApk = deployBundledApk(context) ?: return false
        val su = suPath()
        val adbAvailable = isAdbAvailable()

        val plan = planServerStart(running = false, suAvailable = su != null, adbAvailable = adbAvailable)
        Log.i(
            TAG,
            "startServer: plan=$plan su=$su adb=$adbAvailable " +
                "server=$serverBin apk=$bundledApk",
        )

        return when (plan) {
            ServerStartPlan.ALREADY_RUNNING -> true
            ServerStartPlan.UNAVAILABLE -> {
                Log.w(TAG, "Nelze nastartovat Shizuku server: chybi su i adb")
                false
            }
            ServerStartPlan.VIA_SU -> startViaSu(context, su!!)
            ServerStartPlan.VIA_ADB -> startViaAdb(context)
        }
    }

    /**
     * Root cesta: nakopíruje server + APK do /data/local/tmp (přes `su -c cp`)
     * a spustí server pod **shell UID** (`su 2000 -c`) — stejná práva jako
     * Shizuku spuštěný přes adb, ale bez nutnosti wireless debug párování.
     *
     * Ověření běhu: `su 2000 -c pidof shizuku_server` (app UID by proces
     * neviděl, protože běží pod uid 2000).
     */
    private fun startViaSu(context: Context, su: String): Boolean {
        val tmpDir = "/data/local/tmp"
        return try {
            // 1. Nasadit server + APK do tmpDir (shell UID tam čte i spouští)
            for (cmd in buildSuServerStartCommands(context.filesDir.absolutePath, tmpDir)) {
                val p = ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start()
                if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.destroy()
            }

            // 2. Spustit server pod shell UID (non-root, shoduje se s filozofií Shizuku)
            val startCmd = buildSuServerStartCommand(tmpDir) + " > $tmpDir/shizuku.log 2>&1 &"
            ProcessBuilder(su, "2000", "-c", startCmd)
                .redirectErrorStream(true).start()
                .waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(2500)

            // 3. Ověřit běh (pidof pod su, app UID by shell proces neviděl)
            val pidOut = ProcessBuilder(su, "2000", "-c", "pidof shizuku_server 2>/dev/null || true")
                .redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText().trim()
            val pid = pidOut.split(" ").firstNotNullOfOrNull { it.toIntOrNull() }
            if (pid != null) {
                File(context.filesDir, PID_FILE).writeText(pid.toString())
                Log.i(TAG, "Shizuku server spusten pres su (shell uid, pid=$pid)")
                true
            } else {
                val log = File("$tmpDir/shizuku.log").let { if (it.exists()) it.readText().take(1000) else "(zadny log)" }
                Log.w(TAG, "Shizuku server nenabehl. Log: $log")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "startViaSu selhalo: ${e.message}")
            false
        }
    }

    /**
     * Non-root cesta: server musí spustit `adb shell` (shell UID). App kontext
     * `adb` binárku nemá — zkusíme ji najít v guest rootfs (Kali/Parrot ji mají
     * v `/usr/bin/adb`) a přes ni spustit `shizuku-server` na hostu.
     *
     * Když `adb` není k dispozici, vrátíme false s jasným logem; uživatel musí
     * zapnout bezdrátové ladění a spustit server ručně.
     */
    private fun startViaAdb(context: Context): Boolean {
        val adb = findAdbBinary(context)
        if (adb == null) {
            Log.w(TAG, "startViaAdb: adb binarka nenalezena (app kontext ani guest rootfs)")
            return false
        }

        val tmpDir = "/data/local/tmp"
        val server = "$tmpDir/shizuku-server"
        val apk = "$tmpDir/shizuku.apk"
        return try {
            // adb push z app filesDir (přes /sdcard by to bylo pomalé) není možný
            // — app UID nemůže psát do /data/local/tmp. Použijeme host cestu
            // z app filesDir přes `adb shell` + `run-as`, což je jediná cesta bez rootu.
            val pkg = context.packageName
            val files = context.filesDir.absolutePath
            val push = "run-as $pkg cat $files/shizuku-server > $server && " +
                "run-as $pkg cat $files/shizuku.apk > $apk && " +
                "chmod 755 $server && chmod 644 $apk"
            val pushProc = ProcessBuilder(adb, "shell", push).redirectErrorStream(true).start()
            val pushOut = pushProc.inputStream.bufferedReader().readText()
            if (!pushProc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || pushProc.exitValue() != 0) {
                Log.w(TAG, "startViaAdb: push selhal (rc=${pushProc.exitValue()}): $pushOut")
                return false
            }

            val startCmd = buildSuServerStartCommand(tmpDir) + " > $tmpDir/shizuku.log 2>&1 &"
            ProcessBuilder(adb, "shell", startCmd).redirectErrorStream(true).start()
                .waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(2500)

            val pidOut = ProcessBuilder(adb, "shell", "pidof shizuku_server 2>/dev/null || true")
                .redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText().trim()
            val pid = pidOut.split(" ").firstNotNullOfOrNull { it.toIntOrNull() }
            if (pid != null) {
                File(context.filesDir, PID_FILE).writeText(pid.toString())
                Log.i(TAG, "Shizuku server spusten pres adb (shell uid, pid=$pid)")
                true
            } else {
                val log = ProcessBuilder(adb, "shell", "cat $tmpDir/shizuku.log 2>/dev/null || true")
                    .redirectErrorStream(true).start()
                    .inputStream.bufferedReader().readText().take(1000)
                Log.w(TAG, "startViaAdb: server nenabehl. Log: $log")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "startViaAdb selhalo: ${e.message}")
            false
        }
    }

    /** Najde adb binárku: app kontext (`/system/bin`) nebo guest rootfs (Kali/Parrot). */
    private fun findAdbBinary(context: Context): String? {
        val direct =
            listOf(
                "/system/bin/adb",
                "/system/xbin/adb",
                "/data/local/tmp/adb",
                File(context.filesDir, "usr/bin/adb").absolutePath,
            ).firstOrNull { File(it).exists() && File(it).canExecute() }
        if (direct != null) return direct

        val distroRoot = File(context.filesDir, "nh/distro")
        return distroRoot.listFiles()?.asSequence()
            ?.flatMap { d ->
                sequenceOf(File(d, "usr/bin/adb"), File(d, "bin/adb"))
            }
            ?.firstOrNull { it.exists() && it.canExecute() }
            ?.absolutePath
    }

    /**
     * Deploy bundled Shizuku APK from assets to filesDir.
     */
    private fun deployBundledApk(context: Context): File? {
        val target = File(context.filesDir, "shizuku.apk")
        var shouldDeploy = !target.exists() || target.length() == 0L
        if (!shouldDeploy) {
            try {
                val assetSize = context.assets.open(ASSET_APK).use { it.available().toLong() }
                if (target.length() != assetSize) shouldDeploy = true
            } catch (e: Exception) {
                shouldDeploy = true
            }
        }
        if (!shouldDeploy) return target
        return try {
            context.assets.open(ASSET_APK).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, false)
            Log.i(TAG, "Deployed bundled Shizuku APK (${target.length()} bytes)")
            target
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy bundled APK: ${e.message}")
            null
        }
    }

    /**
     * Get the best available Shizuku APK path:
     * 1. System-installed Shizuku app (if available)
     * 2. Our bundled APK (deployed to filesDir)
     */
    @JvmStatic
    fun getShizukuApkPath(context: Context): String? {
        // 1. Try system Shizuku app
        val systemPath = try {
            val ai = context.packageManager.getApplicationInfo(SHIZUKU_PKG, 0)
            ai.sourceDir
        } catch (e: PackageManager.NameNotFoundException) { null }
        if (systemPath != null) return systemPath

        // 2. Fallback to bundled APK
        val bundled = deployBundledApk(context)
        return bundled?.absolutePath
    }

    /**
     * Check if Shizuku app is installed and can be used for --apk.
     * @deprecated Use getShizukuApkPath() which includes bundled fallback
     */
    @JvmStatic
    @Deprecated("Use getShizukuApkPath()")
    fun getShizukuApkPathLegacy(context: Context): String? {
        return try {
            val ai = context.packageManager.getApplicationInfo(SHIZUKU_PKG, 0)
            ai.sourceDir
        } catch (e: PackageManager.NameNotFoundException) { null }
    }

    /**
     * Stop the Shizuku server (only works for self-managed server).
     */
    @JvmStatic
    fun stopServer(context: Context): Boolean {
        val st = status(context)
        if (!st.running) return true

        if (st.mode == "self" && st.pid != null) {
            return try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill ${st.pid} 2>/dev/null"))
                File(context.filesDir, PID_FILE).delete()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop: ${e.message}")
                false
            }
        }

        Log.w(TAG, "Cannot stop external Shizuku server")
        return false
    }
}

/**
 * Escape a string for safe inclusion in a JSON string value.
 */
private fun String.escapeJson(): String {
    return this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
