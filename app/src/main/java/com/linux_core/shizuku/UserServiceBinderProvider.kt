package com.linux_core.shizuku

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import moe.shizuku.api.BinderContainer
import java.util.concurrent.ConcurrentHashMap

/**
 * Schránka na binder UserService mezi `UserServiceStarter` (spawnutý
 * `app_process`, uid 2000) a Shizuku-compat serverem (taky uid 2000).
 * Binder jde předat jen binder transakcí — ne pipe ani souborem — a ani
 * jeden z obou procesů nemá vlastní ContentProvider. V originále tuhle roli
 * hraje provider Shizuku manageru (`sendUserService`).
 *
 * Tok: starter `put(token, binder)` → na stdout `READY` → server `take(token)`.
 * Navíc `sendServerBinder`: server sem posílá vlastní binder → `ShizukuServerState`.
 * Volat smí jen uid 0/2000 (ověřeno v `call`, manifest navíc chce
 * INTERACT_ACROSS_USERS_FULL, kterou běžná appka nemá).
 */
class UserServiceBinderProvider : ContentProvider() {

    private class Entry(val binder: IBinder, val time: Long)

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != 0 && uid != SHELL_UID) {
            Log.w(TAG, "$method zamítnuto pro uid $uid")
            return null
        }
        if (method == METHOD_SEND_SERVER) {
            extras ?: return null
            extras.classLoader = BinderContainer::class.java.classLoader
            @Suppress("DEPRECATION")
            val binder = extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder ?: return null
            ShizukuServerState.set(binder, extras.getInt(ARG_PID, -1))
            return Bundle()
        }
        val token = extras?.getString(ARG_TOKEN) ?: return null
        prune()
        return when (method) {
            METHOD_PUT -> {
                extras.classLoader = BinderContainer::class.java.classLoader
                @Suppress("DEPRECATION")
                val binder = extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder ?: return null
                pending[token] = Entry(binder, SystemClock.elapsedRealtime())
                Bundle()
            }
            METHOD_TAKE -> {
                val e = pending.remove(token) ?: return null
                Bundle().apply { putParcelable(EXTRA_BINDER, BinderContainer(e.binder)) }
            }
            else -> null
        }
    }

    /** Nevyzvednuté bindery (server mezitím spadl) nedržet věčně. */
    private fun prune() {
        val now = SystemClock.elapsedRealtime()
        pending.entries.removeIf { now - it.value.time > TTL_MS }
    }

    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "ShizukuUSProvider"
        private const val SHELL_UID = 2000
        private const val TTL_MS = 60_000L
        const val AUTHORITY = "com.linux_core.shizuku.userservice"
        const val METHOD_PUT = "putUserService"
        const val METHOD_TAKE = "takeUserService"
        /** Server → appka: binder serveru pro stav/stop v UI (`ShizukuServerState`). */
        const val METHOD_SEND_SERVER = "sendServerBinder"
        const val ARG_PID = "shizuku:server-pid"
        const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
        const val ARG_TOKEN = "shizuku:user-service-arg-token"

        private val pending = ConcurrentHashMap<String, Entry>()
    }
}
