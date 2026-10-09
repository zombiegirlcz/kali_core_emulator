package com.linux_core.shizuku

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import dalvik.system.PathClassLoader
import moe.shizuku.api.BinderContainer
import kotlin.system.exitProcess

/**
 * `app_process` entry point jednoho Shizuku UserService procesu (uid 2000).
 * Spouští ho `UserServiceManager` v Shizuku-compat serveru, náš APK je na
 * CLASSPATH, kód klienta se načte až tady přes jeho package context —
 * port `rikka.shizuku.server.UserService.create()` (reflexí, žádné
 * compile-time hidden API typy).
 *
 * Binder služby předá serveru přes `UserServiceBinderProvider` (put) a
 * ohlásí to řádkem `READY:<token>` na stdout. Server drží stdin otevřený
 * — EOF = server umřel → končíme taky (jako originál přes linkToDeath).
 */
object UserServiceStarter {

    private const val TAG = "ShizukuUserService"
    const val READY_PREFIX = "READY:"
    private const val CALLING_PKG = "com.android.shell"

    @JvmStatic
    fun main(args: Array<String>) {
        var tokenArg: String? = null
        var pkgArg: String? = null
        var clsArg: String? = null
        var apk: String? = null
        var uid = -1
        for (a in args) {
            when {
                a.startsWith("--token=") -> tokenArg = a.substringAfter('=')
                a.startsWith("--package=") -> pkgArg = a.substringAfter('=')
                a.startsWith("--class=") -> clsArg = a.substringAfter('=')
                a.startsWith("--apk=") -> apk = a.substringAfter('=')
                a.startsWith("--uid=") -> uid = a.substringAfter('=').toIntOrNull() ?: -1
            }
        }
        if (tokenArg.isNullOrEmpty() || pkgArg.isNullOrEmpty() || clsArg.isNullOrEmpty()) {
            System.err.println("[-] chybí --token/--package/--class")
            exitProcess(1)
        }
        val token: String = tokenArg!!
        val pkg: String = pkgArg!!
        val cls: String = clsArg!!

        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()

        Log.i(TAG, "startuji $pkg/$cls (uid=${android.os.Process.myUid()})")
        val service = createService(pkg, cls, apk, uid)
        if (service == null) {
            Log.e(TAG, "nelze vytvořit $pkg/$cls")
            exitProcess(1)
        }

        val extras = Bundle().apply {
            putString(UserServiceBinderProvider.ARG_TOKEN, token)
            putParcelable(UserServiceBinderProvider.EXTRA_BINDER, BinderContainer(service))
        }
        val reply = BinderDelivery.callProvider(
            CALLING_PKG, UserServiceBinderProvider.AUTHORITY, UserServiceBinderProvider.METHOD_PUT, extras
        )
        if (reply == null) {
            Log.e(TAG, "binder se nepodařilo předat serveru")
            exitProcess(1)
        }
        println(READY_PREFIX + token)
        System.out.flush()

        Thread({
            try {
                while (System.`in`.read() >= 0) { /* server nic neposílá */ }
            } catch (_: Throwable) {
            }
            Log.i(TAG, "server ukončen → končím ($pkg/$cls)")
            exitProcess(0)
        }, "shizuku-us-stdin").apply { isDaemon = true }.start()

        Looper.loop()
        exitProcess(0)
    }

    private fun createService(pkg: String, cls: String, apk: String?, uid: Int): IBinder? {
        var context: Context? = null
        var loader: ClassLoader? = null
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("systemMain").invoke(null)
            val systemContext = atClass.getMethod("getSystemContext").invoke(at) as Context
            val flags = Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            val pkgContext = try {
                val user = UserHandle.getUserHandleForUid(if (uid >= 0) uid else 0)
                systemContext.javaClass
                    .getMethod("createPackageContextAsUser", String::class.java, Int::class.javaPrimitiveType, UserHandle::class.java)
                    .invoke(systemContext, pkg, flags, user) as Context
            } catch (t: Throwable) {
                systemContext.createPackageContext(pkg, flags)
            }
            context = pkgContext
            loader = pkgContext.classLoader
            // Jako originál: Application (výchozí třída, ne klientova) jako kontext služby.
            try {
                val f = pkgContext.javaClass.getDeclaredField("mPackageInfo")
                f.isAccessible = true
                val loadedApk = f.get(pkgContext)
                val makeApp = loadedApk.javaClass.getDeclaredMethod(
                    "makeApplication", Boolean::class.javaPrimitiveType, Instrumentation::class.java
                )
                makeApp.isAccessible = true
                val app = makeApp.invoke(loadedApk, true, null) as Application
                val initial = atClass.getDeclaredField("mInitialApplication")
                initial.isAccessible = true
                initial.set(at, app)
                context = app
                loader = app.classLoader
            } catch (t: Throwable) {
                Log.w(TAG, "makeApplication selhal, použiji package context: $t")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "package context selhal: $t")
        }
        if (loader == null) {
            if (apk.isNullOrEmpty()) return null
            Log.w(TAG, "fallback: PathClassLoader($apk), bez Contextu")
            loader = PathClassLoader(apk, ClassLoader.getSystemClassLoader().parent)
        }
        return try {
            val serviceClass = loader.loadClass(cls)
            val ctor = if (context != null) {
                try { serviceClass.getConstructor(Context::class.java) } catch (_: Throwable) { null }
            } else null
            (if (ctor != null) ctor.newInstance(context) else serviceClass.getDeclaredConstructor().newInstance()) as IBinder
        } catch (t: Throwable) {
            Log.e(TAG, "instanciace $cls selhala", t)
            null
        }
    }
}
