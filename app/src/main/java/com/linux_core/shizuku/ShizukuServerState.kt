package com.linux_core.shizuku

import android.os.IBinder
import moe.shizuku.server.IShizukuService

/**
 * Binder Shizuku-compat serveru v procesu APPKY. Server ho posílá
 * (`BinderDistributor`, při každém novém PID appky) do
 * `UserServiceBinderProvider.sendServerBinder`. Stav se pak zjišťuje přímým
 * binder voláním — appka (uid 10xxx) procesy uid 2000 v /proc nevidí
 * (hidepid) a nemusí jít přes shell_daemon/adb.
 */
object ShizukuServerState {

    data class Status(val running: Boolean, val pid: Int = -1, val version: Int = -1, val uid: Int = -1)

    @Volatile private var binder: IBinder? = null
    @Volatile private var pid: Int = -1

    internal fun set(b: IBinder, serverPid: Int) {
        binder = b
        pid = serverPid
        try {
            b.linkToDeath({ if (binder === b) binder = null }, 0)
        } catch (_: Throwable) {
            binder = null
        }
    }

    fun service(): IShizukuService? {
        val b = binder ?: return null
        if (!b.pingBinder()) return null
        return IShizukuService.Stub.asInterface(b)
    }

    /** Volá binder (IPC) — ne na UI vlákně. */
    fun status(): Status {
        val svc = service() ?: return Status(false)
        return try {
            Status(true, pid, svc.version, svc.uid)
        } catch (_: Throwable) {
            Status(false)
        }
    }

    /** Ukončí server (`IShizukuService.exit`, oneway pád spojení je očekávaný). */
    fun stop(): Boolean {
        val svc = service() ?: return false
        try {
            svc.exit()
        } catch (_: Throwable) {
            // server umře uprostřed transakce → DeadObjectException je úspěch
        }
        binder = null
        return true
    }
}
