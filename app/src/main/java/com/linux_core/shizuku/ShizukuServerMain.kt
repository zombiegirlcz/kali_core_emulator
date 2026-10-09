package com.linux_core.shizuku

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IShizukuServiceConnection
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Standalone `app_process` entry point — Shizuku-protokol-kompatibilní
 * privilegovaný server běžící pod uid 2000 (shell), startovaný stejným
 * `adb shell "nohup ... &"` mechanismem jako `shell_daemon`
 * (viz `nh shizuku start`, AGENTS.md §9b pro analogii).
 *
 * Wire-kompatibilita: AIDL v `app/src/main/aidl/moe/shizuku/server/` je
 * vendorovaná 1:1 z `RikkaApps/Shizuku-API` (transakční kódy jsou
 * deterministické z .aidl textu → appky používající `rikka.shizuku:api`
 * fungují beze zásahu).
 *
 * UserService hostování (`bindUserService`) → `UserServiceManager`.
 *
 * ZÁMĚRNĚ NEIMPLEMENTOVÁNO (dokumentovaný gap, ne bug):
 *  - raw transact-relay (BINDER_TRANSACTION_transact=1) — mechanismus
 *    neověřitelný z dostupných zdrojů.
 *
 * Permission grant/revoke je čistě CLI (`nh shizuku grant/revoke/list`),
 * ŽÁDNÁ UI/notifikace — na přání uživatele.
 */
object ShizukuServerMain {

    private const val TAG = "ShizukuServer"
    const val SERVER_VERSION = 13
    const val SERVER_PATCH_VERSION = 6
    private const val PID_FILE = "/data/local/tmp/shizuku_server.pid"

    @JvmStatic
    fun main(args: Array<String>) {
        var token: String? = null
        for (a in args) {
            if (a.startsWith("--token=")) token = a.substringAfter("--token=")
        }
        if (token.isNullOrEmpty()) {
            System.err.println("[-] chybí --token")
            return
        }
        ShizukuHttpClient.token = token

        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()

        try {
            File(PID_FILE).writeText(Process.myPid().toString())
        } catch (t: Throwable) {
            Log.w(TAG, "Nelze zapsat $PID_FILE: ${t.message}")
        }

        val service = ShizukuServiceImpl()
        Log.i(TAG, "Shizuku-compat server startuje (uid=${Process.myUid()}, pid=${Process.myPid()})")

        BinderDistributor(service.asBinder()).start()

        Looper.loop()
    }
}

/**
 * Originální Shizuku doručuje binder klientům sám (při startu a při startu
 * procesu klienta). Bez toho klient nikdy nedostane binder a `pingBinder()`
 * je false. Tady: každé 2 s projdi /proc (uid 2000 má readproc), a když
 * hlavní proces povoleného balíčku běží s novým PID, pošli mu binder přes
 * jeho `<pkg>.shizuku` provider. Doručujeme jen BĚŽÍCÍM procesům —
 * getContentProviderExternal by jinak appku sám spustil.
 */
private class BinderDistributor(private val binder: IBinder) : Thread("shizuku-binder-dist") {
    private val delivered = HashMap<String, Int>()
    private var granted: Set<String> = emptySet()
    private var lastGrantRefresh = 0L

    init { isDaemon = true }

    override fun run() {
        while (true) {
            try {
                tick()
            } catch (t: Throwable) {
                Log.w(TAG, "tick selhal: ${t.message}")
            }
            try {
                sleep(TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (lastGrantRefresh == 0L || now - lastGrantRefresh >= GRANT_REFRESH_MS) {
            ShizukuHttpClient.grantedPackages()?.let { granted = it }
            lastGrantRefresh = now
        }
        delivered.keys.retainAll(granted + OWN_PKG)
        val running = mainProcesses(granted + OWN_PKG)
        deliverToOwnApp(running[OWN_PKG])
        if (granted.isEmpty()) return
        for (pkg in granted) {
            val pid = running[pkg]
            if (pid == null) {
                delivered.remove(pkg)
                continue
            }
            if (delivered[pkg] == pid) continue
            // Zapamatuj si PID i při selhání (appka bez provideru by jinak
            // dostávala pokus každé 2 s); nový start appky = nový pokus.
            delivered[pkg] = pid
            val ok = try {
                BinderDelivery.sendBinderToPackage(CALLING_PKG, pkg, binder)
            } catch (t: Throwable) {
                Log.w(TAG, "doručení do $pkg selhalo: ${t.message}")
                false
            }
            Log.i(TAG, "binder → $pkg (pid=$pid): ${if (ok) "OK" else "selhalo"}")
        }
    }

    /** Vlastní appce (stav serveru v UI) — přes náš provider, ne `<pkg>.shizuku`. */
    private fun deliverToOwnApp(pid: Int?) {
        if (pid == null) {
            delivered.remove(OWN_PKG)
            return
        }
        if (delivered[OWN_PKG] == pid) return
        delivered[OWN_PKG] = pid
        val extras = android.os.Bundle().apply {
            putParcelable(UserServiceBinderProvider.EXTRA_BINDER, moe.shizuku.api.BinderContainer(binder))
            putInt(UserServiceBinderProvider.ARG_PID, Process.myPid())
        }
        val ok = try {
            BinderDelivery.callProvider(
                CALLING_PKG, UserServiceBinderProvider.AUTHORITY, UserServiceBinderProvider.METHOD_SEND_SERVER, extras
            ) != null
        } catch (t: Throwable) {
            false
        }
        Log.i(TAG, "binder → $OWN_PKG (pid=$pid): ${if (ok) "OK" else "selhalo"}")
    }

    /** balíček → PID hlavního procesu (argv0 == balíček, bez `:sub` procesů). */
    private fun mainProcesses(wanted: Set<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        val dirs = File("/proc").list() ?: return out
        for (d in dirs) {
            val pid = d.toIntOrNull() ?: continue
            val name = try {
                val b = File("/proc/$pid/cmdline").readBytes()
                val end = b.indexOf(0.toByte()).let { if (it < 0) b.size else it }
                String(b, 0, end)
            } catch (_: Throwable) {
                continue
            }
            if (name in wanted) out[name] = pid
        }
        return out
    }

    companion object {
        private const val TAG = "ShizukuBinderDist"
        private const val TICK_MS = 2000L
        private const val GRANT_REFRESH_MS = 5000L
        // AttributionSource balíček musí patřit volajícímu uid (2000).
        private const val CALLING_PKG = "com.android.shell"
        private const val OWN_PKG = "com.linux_core"
    }
}

private class ShizukuServiceImpl : IShizukuService.Stub() {

    /** uid → IShizukuApplication (pro budoucí dispatch, dnes nevoláno bez UI toku). */
    private val attachedApps = ConcurrentHashMap<Int, IShizukuApplication>()
    private val flagsStore = ConcurrentHashMap<Int, Int>()
    private val uidToPackage = ConcurrentHashMap<Int, String>()
    /** uid → `shizuku:attach-api-version` klienta (peekUserService návratový kód). */
    private val apiVersions = ConcurrentHashMap<Int, Int>()
    private val userServices = UserServiceManager()

    /** Výstup je `package:<balíček> uid:<N>` → bereme jen první token. */
    private fun callerPackage(): String {
        val uid = Binder.getCallingUid()
        uidToPackage[uid]?.let { return it }
        return try {
            val p = ProcessBuilder("cmd", "package", "list", "packages", "--uid", uid.toString())
                .redirectErrorStream(true).start()
            val line = p.inputStream.bufferedReader().readLine() ?: ""
            p.waitFor()
            val pkg = line.trim().removePrefix("package:").substringBefore(' ').trim()
            if (pkg.isEmpty()) {
                "uid:$uid"
            } else {
                uidToPackage[uid] = pkg
                pkg
            }
        } catch (t: Throwable) {
            "uid:$uid"
        }
    }

    /** Každé privilegované volání: binder se může dostat i k appce bez grantu. */
    private fun enforceGranted(op: String) {
        val pkg = callerPackage()
        if (!ShizukuHttpClient.isGranted(pkg)) {
            Log.w(TAG_SVC, "$op zamítnuto: $pkg nemá grant")
            throw SecurityException("Shizuku: $pkg nemá oprávnění (nh shizuku grant $pkg)")
        }
    }

    override fun getVersion(): Int = ShizukuServerMain.SERVER_VERSION

    override fun getUid(): Int = Process.myUid()

    override fun checkPermission(permission: String?): Int {
        val iam = HiddenApis.getActivityManagerProxy() ?: return -1
        return HiddenApis.checkPermission(iam, permission ?: return -1, Binder.getCallingPid(), Binder.getCallingUid())
    }

    override fun newProcess(cmd: Array<out String>?, env: Array<out String>?, dir: String?): IRemoteProcess {
        enforceGranted("newProcess")
        val command = (cmd ?: arrayOf("sh")).toList()
        val pb = ProcessBuilder(command)
        if (!dir.isNullOrEmpty()) pb.directory(File(dir))
        env?.forEach { kv ->
            val idx = kv.indexOf('=')
            if (idx > 0) pb.environment()[kv.substring(0, idx)] = kv.substring(idx + 1)
        }
        pb.redirectErrorStream(false)
        val process: java.lang.Process = pb.start()
        return RemoteProcessImpl(process)
    }

    override fun getSELinuxContext(): String = try {
        File("/proc/self/attr/current").readText().trim().trimEnd('\u0000')
    } catch (t: Throwable) {
        "unknown"
    }

    override fun getSystemProperty(name: String?, defaultValue: String?): String =
        HiddenApis.getSystemProperty(name ?: "", defaultValue ?: "")

    override fun setSystemProperty(name: String?, value: String?) {
        enforceGranted("setSystemProperty")
        if (name != null && value != null) HiddenApis.setSystemProperty(name, value)
    }

    override fun addUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int {
        val uid = Binder.getCallingUid()
        enforceGranted("addUserService")
        if (conn == null || args == null) throw NullPointerException("connection/options is null")
        return userServices.addUserService(conn, args, uid, apiVersions[uid] ?: ShizukuServerMain.SERVER_VERSION)
    }

    override fun removeUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int {
        val uid = Binder.getCallingUid()
        if (args == null) throw NullPointerException("options is null")
        return userServices.removeUserService(conn, args, uid)
    }

    override fun requestPermission(requestCode: Int) {
        val pkg = callerPackage()
        val granted = ShizukuHttpClient.isGranted(pkg)
        val app = attachedApps[Binder.getCallingUid()]
        val reply = Bundle().apply {
            putBoolean("shizuku:request-permission-reply-allowed", granted)
            putBoolean("shizuku:request-permission-reply-is-onetime", false)
        }
        try {
            app?.dispatchRequestPermissionResult(requestCode, reply)
        } catch (t: Throwable) {
            Log.w(TAG_SVC, "dispatchRequestPermissionResult failed: ${t.message}")
        }
    }

    override fun checkSelfPermission(): Boolean = ShizukuHttpClient.isGranted(callerPackage())

    override fun shouldShowRequestPermissionRationale(): Boolean = false

    override fun attachApplication(application: IShizukuApplication?, args: Bundle?) {
        if (application == null) return
        val uid = Binder.getCallingUid()
        attachedApps[uid] = application
        args?.getInt("shizuku:attach-api-version", -1)?.takeIf { it > 0 }?.let { apiVersions[uid] = it }
        val pkg = args?.getString("shizuku:attach-package-name") ?: callerPackage()
        val granted = ShizukuHttpClient.isGranted(pkg)
        val reply = Bundle().apply {
            putInt("shizuku:attach-reply-version", ShizukuServerMain.SERVER_VERSION)
            putInt("shizuku:attach-reply-patch-version", ShizukuServerMain.SERVER_PATCH_VERSION)
            putInt("shizuku:attach-reply-uid", Process.myUid())
            putString("shizuku:attach-reply-secontext", getSELinuxContext())
            putBoolean("shizuku:attach-reply-permission-granted", granted)
            putBoolean("shizuku:attach-reply-should-show-request-permission-rationale", false)
        }
        try {
            application.bindApplication(reply)
        } catch (t: Throwable) {
            Log.w(TAG_SVC, "bindApplication callback failed: ${t.message}")
        }
    }

    override fun exit() {
        Log.i(TAG_SVC, "exit() požádáno klientem")
        Process.killProcess(Process.myPid())
    }

    /** Originální tok (starter → server s tokenem); náš starter jde přes UserServiceBinderProvider. */
    override fun attachUserService(binder: android.os.IBinder?, options: Bundle?) {
        if (binder == null || options == null) throw NullPointerException("binder/options is null")
        userServices.attachUserService(binder, options)
    }

    override fun dispatchPackageChanged(intent: android.content.Intent?) {
        // no-op — nemáme registrovaný BroadcastReceiver, appky to nepotřebují pro základní funkce
    }

    override fun isHidden(uid: Int): Boolean = false

    override fun dispatchPermissionConfirmationResult(requestUid: Int, requestPid: Int, requestCode: Int, data: Bundle?) {
        // Sui-only v originále, u nás no-op (nemáme systémovou notifikaci)
    }

    override fun getFlagsForUid(uid: Int, mask: Int): Int = flagsStore[uid]?.and(mask) ?: 0

    override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
        val current = flagsStore[uid] ?: 0
        flagsStore[uid] = (current and mask.inv()) or (value and mask)
    }

    companion object {
        private const val TAG_SVC = "ShizukuServiceImpl"
    }
}

/** IRemoteProcess impl — surové FD z `java.lang.Process` streamů přes reflexi na `fd` pole. */
private class RemoteProcessImpl(private val process: java.lang.Process) : IRemoteProcess.Stub() {

    private fun extractFd(stream: Any): FileDescriptor? = try {
        val f = stream.javaClass.getDeclaredField("fd")
        f.isAccessible = true
        f.get(stream) as? FileDescriptor
    } catch (t: Throwable) {
        null
    }

    private fun wrap(stream: Any): ParcelFileDescriptor? {
        val fd = extractFd(stream) ?: return null
        return try {
            ParcelFileDescriptor.dup(fd)
        } catch (t: Throwable) {
            null
        }
    }

    override fun getOutputStream(): ParcelFileDescriptor? = wrap(process.outputStream as OutputStream)
    override fun getInputStream(): ParcelFileDescriptor? = wrap(process.inputStream as InputStream)
    override fun getErrorStream(): ParcelFileDescriptor? = wrap(process.errorStream as InputStream)

    override fun waitFor(): Int = process.waitFor()
    override fun exitValue(): Int = process.exitValue()
    override fun destroy() = process.destroy()
    override fun alive(): Boolean = process.isAlive

    override fun waitForTimeout(timeout: Long, unit: String?): Boolean = try {
        val u = if (unit != null) java.util.concurrent.TimeUnit.valueOf(unit) else java.util.concurrent.TimeUnit.MILLISECONDS
        process.waitFor(timeout, u)
    } catch (t: Throwable) {
        false
    }
}
