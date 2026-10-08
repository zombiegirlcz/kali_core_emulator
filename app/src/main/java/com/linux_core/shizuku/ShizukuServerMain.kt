package com.linux_core.shizuku

import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
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
 * ZÁMĚRNĚ NEIMPLEMENTOVÁNO (dokumentovaný gap, ne bug):
 *  - raw transact-relay (BINDER_TRANSACTION_transact=1) — mechanismus
 *    neověřitelný z dostupných zdrojů.
 *  - plné UserService hostování (addUserService/removeUserService) — jen
 *    stub, viz komentáře u metod. Běžné "spusť příkaz/čti property" use-case
 *    appek (Termux:API-like nástroje, package manažery bez root) funguje.
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

        // Odešli binder manažerskému balíčku appky samotné (com.linux_core) —
        // odsud appka může binder přeposlat dál (status/diagnostika), a je
        // to i ověření, že BinderDelivery mechanismus vůbec funguje.
        try {
            BinderDelivery.sendBinderToPackage(
                callingPkg = "com.linux_core",
                targetPackage = "com.linux_core",
                binder = service.asBinder(),
                token = token
            )
        } catch (t: Throwable) {
            Log.w(TAG, "sendBinderToPackage(self) failed: ${t.message}")
        }

        Looper.loop()
    }
}

private class ShizukuServiceImpl : IShizukuService.Stub() {

    /** uid → IShizukuApplication (pro budoucí dispatch, dnes nevoláno bez UI toku). */
    private val attachedApps = ConcurrentHashMap<Int, IShizukuApplication>()
    private val flagsStore = ConcurrentHashMap<Int, Int>()

    private fun callerPackage(): String {
        val uid = Binder.getCallingUid()
        return try {
            val p = ProcessBuilder("sh", "-c", "cmd package list packages --uid $uid | head -1")
                .redirectErrorStream(true).start()
            val line = p.inputStream.bufferedReader().readLine() ?: ""
            p.waitFor()
            line.removePrefix("package:").trim().ifEmpty { "uid:$uid" }
        } catch (t: Throwable) {
            "uid:$uid"
        }
    }

    override fun getVersion(): Int = ShizukuServerMain.SERVER_VERSION

    override fun getUid(): Int = Process.myUid()

    override fun checkPermission(permission: String?): Int {
        val iam = HiddenApis.getActivityManagerProxy() ?: return -1
        return HiddenApis.checkPermission(iam, permission ?: return -1, Binder.getCallingPid(), Binder.getCallingUid())
    }

    override fun newProcess(cmd: Array<out String>?, env: Array<out String>?, dir: String?): IRemoteProcess {
        val command = (cmd ?: arrayOf("sh")).toList()
        val pb = ProcessBuilder(command)
        if (!dir.isNullOrEmpty()) pb.directory(File(dir))
        env?.forEach { kv ->
            val idx = kv.indexOf('=')
            if (idx > 0) pb.environment()[kv.substring(0, idx)] = kv.substring(idx + 1)
        }
        pb.redirectErrorStream(false)
        val process = pb.start()
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
        if (name != null && value != null) HiddenApis.setSystemProperty(name, value)
    }

    /**
     * STUB — plné spawnutí hostované AIDL služby klienta vyžaduje druhý
     * binder-delivery hop (spawnutý proces → klientova <pkg>.shizuku →
     * klientův ServiceConnection), který se bez testu na zařízení nedal
     * bezpečně implementovat. Vrací -1 (selhání), appka musí mít fallback.
     */
    override fun addUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int {
        Log.w(TAG_SVC, "addUserService: nepodporováno (viz AGENTS.md known gap)")
        return -1
    }

    override fun removeUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int = -1

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

    /** STUB — viz addUserService. */
    override fun attachUserService(binder: android.os.IBinder?, options: Bundle?) {
        Log.w(TAG_SVC, "attachUserService: nepodporováno (viz AGENTS.md known gap)")
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
private class RemoteProcessImpl(private val process: Process) : IRemoteProcess.Stub() {

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
