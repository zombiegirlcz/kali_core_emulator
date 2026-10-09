package com.linux_core.shizuku

import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteCallbackList
import android.util.Log
import moe.shizuku.api.BinderContainer
import moe.shizuku.server.IShizukuServiceConnection
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Hostování Shizuku UserService — port `rikka.shizuku.server.UserServiceManager`
 * + `UserServiceRecord` (Shizuku-API, server-shared). Klíč záznamu je
 * `<pkg>:<tag ?: className>`; proces = `app_process` s `UserServiceStarter`
 * (uid 2000), binder se vrací přes `UserServiceBinderProvider`.
 *
 * Všechny změny záznamů a broadcasty běží pod zámkem `this`
 * (`RemoteCallbackList.beginBroadcast` není reentrantní).
 */
internal class UserServiceManager {

    private val records = HashMap<String, Record>()
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    inner class Record(val key: String, val versionCode: Int, var daemon: Boolean) {
        val token: String = UUID.randomUUID().toString() + "-" + System.currentTimeMillis()
        var service: IBinder? = null
        var starting = false
        @Volatile var process: java.lang.Process? = null
        val callbacks = ConnectionList()
        private val deathRecipient = IBinder.DeathRecipient {
            Log.i(TAG, "binder služby $key ($token) umřel")
            removeSelf()
        }
        private val startTimeout = Runnable {
            synchronized(this@UserServiceManager) {
                if (starting && service == null) {
                    Log.w(TAG, "$key se nespustil do ${START_TIMEOUT_MS} ms")
                    removeLocked(this)
                }
            }
        }

        inner class ConnectionList : RemoteCallbackList<IShizukuServiceConnection>() {
            override fun onCallbackDied(callback: IShizukuServiceConnection?) {
                if (daemon || registeredCallbackCount != 0) return
                Log.i(TAG, "$key: non-daemon a všechna spojení jsou pryč → ruším")
                removeSelf()
            }
        }

        fun removeSelf() = synchronized(this@UserServiceManager) { removeLocked(this) }

        fun alive(): Boolean = service?.pingBinder() == true

        fun setStartingTimeout() {
            starting = true
            main.postDelayed(startTimeout, START_TIMEOUT_MS)
        }

        fun setBinderLocked(binder: IBinder) {
            main.removeCallbacks(startTimeout)
            starting = false
            service = binder
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "linkToDeath $key: ${t.message}")
            }
            broadcastConnectedLocked()
        }

        fun broadcastConnectedLocked() {
            val s = service ?: return
            val n = callbacks.beginBroadcast()
            for (i in 0 until n) {
                try {
                    callbacks.getBroadcastItem(i).connected(s)
                } catch (t: Throwable) {
                    Log.w(TAG, "connected() $key selhalo: ${t.message}")
                }
            }
            callbacks.finishBroadcast()
        }

        fun destroyLocked() {
            main.removeCallbacks(startTimeout)
            starting = false
            val s = service
            if (s != null) {
                try { s.unlinkToDeath(deathRecipient, 0) } catch (_: Throwable) {}
                if (s.pingBinder()) {
                    val data = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(s.interfaceDescriptor ?: "")
                        s.transact(TRANSACTION_DESTROY, data, null, Binder.FLAG_ONEWAY)
                    } catch (t: Throwable) {
                        Log.w(TAG, "destroy transakce $key selhala: ${t.message}")
                    } finally {
                        data.recycle()
                    }
                }
            }
            callbacks.kill()
            // Klientův destroy() má sám zavolat System.exit — po krátké době
            // proces dorazíme, ať nezůstávají sirotci.
            val p = process ?: return
            main.postDelayed({ if (p.isAlive) p.destroy() }, KILL_GRACE_MS)
        }
    }

    private fun removeLocked(record: Record) {
        if (records[record.key] === record) {
            records.remove(record.key)
            record.destroyLocked()
            Log.i(TAG, "záznam ${record.key} (${record.token}) odstraněn")
        }
    }

    /** Ověří, že `pkg` patří volajícímu (shoda appId), vrátí cestu k APK. */
    private fun ensureCallingPackage(pkg: String, callingUid: Int): String {
        val info = ShizukuHttpClient.resolvePackage(pkg)
            ?: throw SecurityException("nelze najít balíček $pkg")
        val uid = info.optInt("uid", -1)
        if (uid < 0 || uid % 100000 != callingUid % 100000) {
            throw SecurityException("balíček $pkg nepatří uid $callingUid")
        }
        return info.optString("apkPath")
    }

    private fun keyOf(options: Bundle): Pair<ComponentName, String> {
        @Suppress("DEPRECATION")
        val component = options.getParcelable<ComponentName>(ARG_COMPONENT)
            ?: throw NullPointerException("component is null")
        val tag = options.getString(ARG_TAG)
        return component to "${component.packageName}:${tag ?: component.className}"
    }

    fun addUserService(conn: IShizukuServiceConnection, options: Bundle, callingUid: Int, callingApiVersion: Int): Int {
        val (component, key) = keyOf(options)
        val pkg = component.packageName
        val apk = ensureCallingPackage(pkg, callingUid)
        val versionCode = options.getInt(ARG_VERSION_CODE, 1)
        val suffix = options.getString(ARG_PROCESS_NAME) ?: "user_service"
        val noCreate = options.getBoolean(ARG_NO_CREATE, false)
        val daemon = options.getBoolean(ARG_DAEMON, true)
        val use32 = options.getBoolean(ARG_USE_32_BIT, false)

        synchronized(this) {
            var record = records[key]
            if (noCreate) {
                if (record != null) {
                    record.callbacks.register(conn)
                    if (record.alive()) {
                        record.broadcastConnectedLocked()
                        return if (callingApiVersion >= 13) record.versionCode else 0
                    }
                }
                return if (callingApiVersion >= 13) -1 else 1
            }

            if (record != null) {
                if (record.versionCode != versionCode) {
                    Log.i(TAG, "$key: jiný versionCode (${record.versionCode} → $versionCode) → nový proces")
                    removeLocked(record)
                    record = null
                } else if (!record.starting && !record.alive()) {
                    Log.i(TAG, "$key: služba je mrtvá → nový proces")
                    removeLocked(record)
                    record = null
                } else {
                    Log.i(TAG, "$key: existující záznam (${record.token})")
                    record.daemon = daemon
                }
            }
            val r = record ?: Record(key, versionCode, daemon).also {
                records[key] = it
                Log.i(TAG, "nový záznam $key (${it.token}): version=$versionCode daemon=$daemon apk=$apk")
            }
            r.callbacks.register(conn)
            if (r.alive()) {
                r.broadcastConnectedLocked()
            } else if (!r.starting) {
                r.setStartingTimeout()
                executor.execute { startProcess(r, pkg, component.className, suffix, callingUid, apk, use32) }
            }
            return 0
        }
    }

    fun removeUserService(conn: IShizukuServiceConnection?, options: Bundle, callingUid: Int): Int {
        val (component, key) = keyOf(options)
        ensureCallingPackage(component.packageName, callingUid)
        // API < 13.1.4 USER_SERVICE_ARG_REMOVE neposílá → výchozí true
        val remove = if (options.containsKey(ARG_REMOVE)) options.getBoolean(ARG_REMOVE) else true
        synchronized(this) {
            val record = records[key] ?: return 1
            if (remove) removeLocked(record) else if (conn != null) record.callbacks.unregister(conn)
        }
        return 0
    }

    /** `attachUserService` (101) — originální tok přes token; náš starter jde přes provider. */
    fun attachUserService(binder: IBinder, options: Bundle) {
        val token = options.getString(ARG_TOKEN) ?: throw NullPointerException("token is null")
        attachByToken(binder, token)
    }

    private fun attachByToken(binder: IBinder, token: String) {
        synchronized(this) {
            val record = records.values.firstOrNull { it.token == token }
                ?: throw IllegalArgumentException("unable to find token $token")
            Log.i(TAG, "binder pro ${record.key} přijat")
            record.setBinderLocked(binder)
        }
    }

    private fun startProcess(
        record: Record, pkg: String, cls: String, suffix: String, callingUid: Int, apk: String, use32: Boolean
    ) {
        val ourApk = ShizukuHttpClient.resolvePackage(OUR_PKG)?.optString("apkPath")?.takeIf { it.isNotEmpty() }
            ?: System.getenv("CLASSPATH")
        val bin = if (use32 && File("/system/bin/app_process32").exists()) "/system/bin/app_process32"
        else "/system/bin/app_process"
        val pb = ProcessBuilder(
            bin, "/system/bin", "--nice-name=$pkg:$suffix", UserServiceStarter::class.java.name,
            "--token=${record.token}", "--package=$pkg", "--class=$cls", "--uid=$callingUid", "--apk=$apk"
        )
        if (ourApk != null) pb.environment()["CLASSPATH"] = ourApk
        Log.i(TAG, "spouštím ${record.key}: $bin --nice-name=$pkg:$suffix")
        val p: java.lang.Process = try {
            pb.start()
        } catch (t: Throwable) {
            Log.e(TAG, "spuštění ${record.key} selhalo", t)
            record.removeSelf()
            return
        }
        record.process = p
        // stdin záměrně nezavíráme: EOF pro starter = smrt serveru.
        Thread({
            try {
                p.errorStream.bufferedReader().forEachLine { Log.w(TAG, "[${record.key}] $it") }
            } catch (_: Throwable) {
            }
        }, "shizuku-us-err").apply { isDaemon = true }.start()
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line == UserServiceStarter.READY_PREFIX + record.token) onReady(record)
                    else Log.i(TAG, "[${record.key}] $line")
                }
            } catch (_: Throwable) {
            }
            val code = try { p.waitFor() } catch (_: Throwable) { -1 }
            Log.i(TAG, "proces ${record.key} skončil (exit=$code)")
            record.removeSelf()
        }, "shizuku-us-out").apply { isDaemon = true }.start()
    }

    private fun onReady(record: Record) {
        val reply = BinderDelivery.callProvider(
            CALLING_PKG, UserServiceBinderProvider.AUTHORITY, UserServiceBinderProvider.METHOD_TAKE,
            Bundle().apply { putString(UserServiceBinderProvider.ARG_TOKEN, record.token) }
        )
        reply?.classLoader = BinderContainer::class.java.classLoader
        @Suppress("DEPRECATION")
        val binder = reply?.getParcelable<BinderContainer>(UserServiceBinderProvider.EXTRA_BINDER)?.binder
        if (binder == null) {
            Log.e(TAG, "binder ${record.key} z provideru nevyzvednut")
            record.removeSelf()
            return
        }
        try {
            attachByToken(binder, record.token)
        } catch (t: Throwable) {
            // záznam mezitím zrušen (timeout/remove) → proces nepotřebujeme
            Log.w(TAG, "${record.key}: ${t.message}")
            record.process?.destroy()
        }
    }

    companion object {
        private const val TAG = "ShizukuUserService"
        private const val OUR_PKG = "com.linux_core"
        private const val CALLING_PKG = "com.android.shell"
        private const val START_TIMEOUT_MS = 30_000L
        private const val KILL_GRACE_MS = 2_000L
        const val TRANSACTION_DESTROY = 16777115

        const val ARG_TAG = "shizuku:user-service-arg-tag"
        const val ARG_COMPONENT = "shizuku:user-service-arg-component"
        const val ARG_VERSION_CODE = "shizuku:user-service-arg-version-code"
        const val ARG_PROCESS_NAME = "shizuku:user-service-arg-process-name"
        const val ARG_NO_CREATE = "shizuku:user-service-arg-no-create"
        const val ARG_DAEMON = "shizuku:user-service-arg-daemon"
        const val ARG_USE_32_BIT = "shizuku:user-service-arg-use-32-bit-app-process"
        const val ARG_REMOVE = "shizuku:user-service-remove"
        const val ARG_TOKEN = "shizuku:user-service-arg-token"
    }
}
