package com.linux_core.shizuku

import android.os.Bundle
import android.os.IBinder
import android.util.Log

/**
 * Čistě reflexní můstek na skryté (@hide) framework třídy potřebné pro
 * doručení binderu do cizí appky stejným mechanismem jako reálné Shizuku
 * (`IActivityManager.getContentProviderExternal` + `IContentProvider.call`).
 *
 * ŽÁDNÉ compile-time typy na `android.app.IActivityManager`/
 * `android.content.IContentProvider` — tyto třídy jsou v SDK stub jaru
 * (android.jar) úplně odstraněné, ne jen `@hide` metody. Vše jde přes
 * `Class.forName` + `Method.invoke` na runtime instancích.
 *
 * NEVERIFIKOVÁNO na zařízení — signatury `getContentProviderExternal`/
 * `IContentProvider.call` se liší SDK verzí a `invokeByNameArity` zkouší
 * víc variant podle arity. Viz AGENTS.md "známé technické dluhy" pro tento
 * modul.
 */
object HiddenApis {
    private const val TAG = "ShizukuHiddenApis"

    fun getActivityManagerBinder(): IBinder? = try {
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        getService.invoke(null, "activity") as? IBinder
    } catch (t: Throwable) {
        Log.e(TAG, "getActivityManagerBinder failed", t)
        null
    }

    fun getActivityManagerProxy(): Any? {
        val binder = getActivityManagerBinder() ?: return null
        return try {
            val iamStub = Class.forName("android.app.IActivityManager\$Stub")
            val asInterface = iamStub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, binder)
        } catch (t: Throwable) {
            Log.e(TAG, "getActivityManagerProxy failed", t)
            null
        }
    }

    /** Zavolá metodu podle jména a arity — zkusí všechny overloady, dokud jeden neprojde. */
    private fun invokeByNameArity(target: Any, methodName: String, args: Array<Any?>): Any? {
        val candidates = target.javaClass.methods.filter {
            it.name == methodName && it.parameterTypes.size == args.size
        }
        var lastError: Throwable? = null
        for (m in candidates) {
            try {
                m.isAccessible = true
                return m.invoke(target, *args)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw NoSuchMethodException("$methodName/${args.size} nenalezena na ${target.javaClass}: ${lastError?.message}")
    }

    fun checkPermission(iam: Any, permission: String, pid: Int, uid: Int): Int = try {
        invokeByNameArity(iam, "checkPermission", arrayOf(permission, pid, uid)) as? Int ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "checkPermission failed", t)
        -1
    }

    /**
     * `getContentProviderExternal` — na starších API 3 argumenty (name, userId, token),
     * na novějších 4 (+ tag). Zkusíme obě arity.
     */
    fun getContentProviderExternal(iam: Any, name: String, userId: Int, token: IBinder?, tag: String): Any? {
        val holder = try {
            invokeByNameArity(iam, "getContentProviderExternal", arrayOf(name, userId, token, tag))
        } catch (t: Throwable) {
            try {
                invokeByNameArity(iam, "getContentProviderExternal", arrayOf(name, userId, token))
            } catch (t2: Throwable) {
                Log.e(TAG, "getContentProviderExternal($name) failed", t2)
                null
            }
        } ?: return null
        return extractProvider(holder)
    }

    /** Výsledek je buď přímo IContentProvider proxy, nebo ContentProviderHolder.provider. */
    private fun extractProvider(holder: Any): Any? {
        if (holder.javaClass.methods.any { it.name == "asBinder" && it.parameterTypes.isEmpty() }) return holder
        return try {
            val f = holder.javaClass.getField("provider")
            f.isAccessible = true
            f.get(holder)
        } catch (t: Throwable) {
            Log.e(TAG, "extractProvider: no .provider field on ${holder.javaClass}", t)
            null
        }
    }

    fun removeContentProviderExternal(iam: Any, name: String, token: IBinder?) {
        try {
            invokeByNameArity(iam, "removeContentProviderExternal", arrayOf(name, token))
        } catch (t: Throwable) {
            Log.w(TAG, "removeContentProviderExternal($name) failed: ${t.message}")
        }
    }

    fun providerAsBinder(provider: Any): IBinder? = try {
        invokeByNameArity(provider, "asBinder", arrayOf()) as? IBinder
    } catch (t: Throwable) {
        Log.e(TAG, "providerAsBinder failed", t)
        null
    }

    /**
     * `IContentProvider.call(...)` — signatura se měnila napříč SDK verzemi
     * (callingPkg[, callingFeatureId], authority, method, arg, extras).
     * Zkoušíme klesající arity od nejnovější k nejstarší; na úplnou shodu
     * s API 30+ `AttributionSource` variantou se nespoléhá (viz gap v AGENTS.md).
     */
    fun contentProviderCall(
        provider: Any,
        callingPkg: String,
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle? {
        val attempts = listOf(
            arrayOf<Any?>(callingPkg, null, authority, method, arg, extras), // API 29/30 s featureId
            arrayOf<Any?>(callingPkg, authority, method, arg, extras),       // API 29
            arrayOf<Any?>(callingPkg, method, arg, extras)                  // API 28
        )
        for (a in attempts) {
            try {
                return invokeByNameArity(provider, "call", a) as? Bundle
            } catch (t: Throwable) {
                Log.d(TAG, "contentProviderCall attempt (${a.size} args) failed: ${t.message}")
            }
        }
        Log.e(TAG, "contentProviderCall: žádná varianta signatury nesedí (authority=$authority)")
        return null
    }

    /** `android.os.SystemProperties` je @hide, ale ne odstraněná ze stub jaru stejně jako IAM/ICP — jen reflexe pro jistotu. */
    fun getSystemProperty(name: String, default: String): String = try {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java, String::class.java)
        m.invoke(null, name, default) as? String ?: default
    } catch (t: Throwable) {
        default
    }

    fun setSystemProperty(name: String, value: String): Boolean = try {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("set", String::class.java, String::class.java)
        m.invoke(null, name, value)
        true
    } catch (t: Throwable) {
        Log.e(TAG, "setSystemProperty($name) failed", t)
        false
    }
}
