package com.linux_core.bridge

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.linux_core.BuildConfig
import com.linux_core.core.DeviceInfo
import com.linux_core.core.ExecCore
import kotlinx.coroutines.runBlocking

/**
 * App-to-app Binder most (kali_ai_assistant → core).
 *
 * Chráněn signature permission BIND_BRIDGE — bind povolen pouze appkám
 * podepsaným stejným klíčem jako com.linux_core. ŽÁDNÁ vlastní
 * allowlist/guard logika — vše deleguje na ExecCore/DeviceInfo
 * (stejná business cesta jako HTTP endpointy).
 */
class CoreBridgeService : Service() {

    companion object { private const val TAG = "CoreBridgeService" }

    private val binder = object : ICoreBridge.Stub() {

        override fun prootExec(distro: String?, command: String?, timeoutMs: Int): String {
            checkCaller("prootExec")
            return runBlocking {
                ExecCore.guestExec(
                    applicationContext,
                    distro ?: "kali",
                    command ?: "",
                    timeoutMs.toLong().coerceAtLeast(1000)
                )
            }
        }

        override fun hostShell(command: String?): String {
            checkCaller("hostShell")
            return runBlocking { ExecCore.hostExec(applicationContext, command ?: "") }
        }

        override fun elfExec(command: String?, timeoutMs: Int): String {
            checkCaller("elfExec")
            return runBlocking {
                ExecCore.elfExec(
                    applicationContext,
                    command ?: "",
                    timeoutMs.toLong().coerceAtLeast(1000)
                )
            }
        }

        override fun getBattery(): String = DeviceInfo.batteryJson(applicationContext)
        override fun getWifi(): String = DeviceInfo.wifiJson(applicationContext)
        override fun getLocation(): String = DeviceInfo.locationJson(applicationContext)

        override fun getStatus(): String {
            checkCaller("getStatus")
            return """{"bridge_version":1,"core_version":"${BuildConfig.VERSION_NAME}"}"""
        }
    }

    /**
     * Defense in depth: manifest permission už ověřil podpis při bindu;
     * tohle pojistí i přímá IPC volání, kdyby service někdy v budoucnu
     * sloužil i jinému účelu.
     */
    private fun checkCaller(method: String) {
        val pm = packageManager
        val callerUid = Binder.getCallingUid()
        // getNameForUid vrací jméno balíku (případně "pkg1:pkg2" při sdíleném uid)
        val callerPkg = pm.getNameForUid(callerUid)
            ?.substringBefore(":")
            ?: run {
                Log.w(TAG, "Rejected $method from uid=$callerUid (unknown package)")
                throw SecurityException("Caller not signed with core key")
            }
        if (pm.checkSignatures(callerPkg, packageName) != PackageManager.SIGNATURE_MATCH) {
            Log.w(TAG, "Rejected $method from uid=$callerUid pkg=$callerPkg (signature mismatch)")
            throw SecurityException("Caller not signed with core key")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
