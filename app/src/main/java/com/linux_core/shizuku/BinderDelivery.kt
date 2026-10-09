package com.linux_core.shizuku

import android.os.Bundle
import android.os.IBinder
import android.util.Log
import moe.shizuku.api.BinderContainer

/**
 * Doručení binderu do cizí appky přes její vlastní `<packageName>.shizuku`
 * ContentProvider — stejný mechanismus jako `rikka.shizuku:provider`
 * knihovna (ServiceStarter.sendBinder v originále). Appka, co používá
 * `rikka.shizuku:api`, tenhle provider má deklarovaný automaticky ve svém
 * manifestu (merge z knihovny) — nemusí se nic přepisovat.
 */
object BinderDelivery {
    private const val TAG = "ShizukuBinderDelivery"
    private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
    private const val ARG_TOKEN = "shizuku:user-service-arg-token"
    const val METHOD_SEND_BINDER = "sendBinder"
    const val METHOD_SEND_USER_SERVICE = "sendUserService"

    /** @return true pokud se binder podařilo doručit (provider odpověděl). */
    fun sendBinderToPackage(
        callingPkg: String,
        targetPackage: String,
        binder: IBinder,
        method: String = METHOD_SEND_BINDER,
        token: String? = null,
        userId: Int = 0
    ): Boolean {
        val extras = Bundle()
        extras.putParcelable(EXTRA_BINDER, BinderContainer(binder))
        if (token != null) extras.putString(ARG_TOKEN, token)
        callProvider(callingPkg, "$targetPackage.shizuku", method, extras, userId) ?: return false
        Log.i(TAG, "Binder doručen do $targetPackage (method=$method)")
        return true
    }

    /**
     * `IContentProvider.call` na provider s danou authority (pod uid 2000).
     * @return odpověď provideru, null = provider nenalezen/mrtvý/bez odpovědi.
     */
    fun callProvider(
        callingPkg: String,
        authority: String,
        method: String,
        extras: Bundle,
        userId: Int = 0
    ): Bundle? {
        val iam = HiddenApis.getActivityManagerProxy() ?: run {
            Log.e(TAG, "ActivityManager proxy unavailable")
            return null
        }
        val provider = HiddenApis.getContentProviderExternal(iam, authority, userId, null, authority)
        if (provider == null) {
            Log.w(TAG, "Provider $authority nenalezen")
            return null
        }
        try {
            val providerBinder = HiddenApis.providerAsBinder(provider)
            if (providerBinder == null || !providerBinder.pingBinder()) {
                Log.w(TAG, "Provider $authority je mrtvý")
                return null
            }
            val reply = HiddenApis.contentProviderCall(provider, callingPkg, authority, method, null, extras)
            if (reply == null) Log.w(TAG, "contentProviderCall($authority, $method) bez odpovědi")
            return reply
        } finally {
            HiddenApis.removeContentProviderExternal(iam, authority, null)
        }
    }
}
