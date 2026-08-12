package com.linux_core.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object MiuiSecurityBridge {
    private const val TAG = "MiuiSecurityBridge"
    private const val MIUI_SECURITY_CENTER = "com.miui.securitycenter"
    private const val PERMISSION_MODEM_LOCATION = "com.miui.securitycenter.permission.modem_location"

    fun isXiaomiDevice(): Boolean {
        return Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
                Build.BRAND.equals("Redmi", ignoreCase = true) ||
                Build.BRAND.equals("POCO", ignoreCase = true)
    }

    fun isMiuiOrHyperOs(): Boolean {
        return isXiaomiDevice() || hasMiuiProperty()
    }

    private fun hasMiuiProperty(): Boolean {
        return try {
            val method = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            val miuiVersion = method.invoke(null, "ro.miui.ui.version.name", "") as String
            val hyperVersion = method.invoke(null, "ro.miui.ui.version.code", "") as String
            miuiVersion.isNotEmpty() || hyperVersion.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun requestModemLocationPermission(context: Context): Boolean {
        if (!isMiuiOrHyperOs()) {
            Log.d(TAG, "Not a MIUI/HyperOS device, skipping modem_location request")
            return false
        }
        try {
            val pm = context.packageManager
            val packageName = context.packageName
            val granted = pm.checkPermission(PERMISSION_MODEM_LOCATION, packageName)
            if (granted == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "modem_location permission already granted")
                return true
            }
            Log.d(TAG, "modem_location permission not granted on device, trying runtime grant via MIUI intent")
            val intent = Intent("com.miui.securitycenter.action.MODEM_LOCATION_REQUEST")
            intent.setPackage(MIUI_SECURITY_CENTER)
            intent.putExtra("package_name", packageName)
            context.sendBroadcast(intent)
            try {
                val shellResult = execViaShell("pm grant $packageName com.miui.securitycenter.permission.modem_location 2>&1")
                Log.d(TAG, "pm grant result: $shellResult")
                if (!shellResult.contains("granted") && !shellResult.contains("not a changeable")) {
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shell pm grant failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request modem_location permission: ${e.message}")
        }
        return false
    }

    fun querySecurityCenterProvider(context: Context): JSONObject {
        val result = JSONObject()
        if (!isMiuiOrHyperOs()) {
            result.put("available", false)
            result.put("reason", "Not a MIUI/HyperOS device")
            return result
        }
        result.put("available", true)
        result.put("manufacturer", Build.MANUFACTURER)
        result.put("brand", Build.BRAND)

        val miuiVersion = try {
            val method = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            method.invoke(null, "ro.miui.ui.version.name", "") as String
        } catch (e: Exception) {
            ""
        }
        try {
            result.put("miui_version", miuiVersion)
            val packageUri = android.net.Uri.parse("content://com.miui.securitycenter.provider/status")
            val cursor = context.contentResolver.query(packageUri, null, null, null, null)
            if (cursor != null) {
                cursor.use {
                    if (it.moveToFirst()) {
                        val providerData = JSONObject()
                        for (i in 0 until it.columnCount) {
                            providerData.put(it.columnNames[i], it.getString(i) ?: "")
                        }
                        result.put("provider_data", providerData)
                    }
                }
            } else {
                result.put("provider_accessible", false)
            }
        } catch (e: Exception) {
            result.put("provider_accessible", false)
            result.put("provider_error", e.message ?: "unknown")
        }
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(MIUI_SECURITY_CENTER, 0)
            result.put("security_center_installed", true)
            result.put("security_center_uid", appInfo.uid)
            val serviceIntent = Intent().setClassName(MIUI_SECURITY_CENTER, "com.miui.securitycenter.service.SecurityCenterService")
            val resolveInfo = pm.resolveService(serviceIntent, 0)
            result.put("service_resolvable", resolveInfo != null)
        } catch (e: PackageManager.NameNotFoundException) {
            result.put("security_center_installed", false)
        } catch (e: Exception) {
            result.put("security_center_check_error", e.message ?: "unknown")
        }
        return result
    }

    fun execViaShell(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
            process.waitFor()
            if (stderr.isNotBlank()) "$stdout\n$stderr" else stdout
        } catch (e: Exception) {
            Log.e(TAG, "Shell exec failed: ${e.message}")
            e.message ?: "exec failed"
        }
    }

    fun checkModemPermissionGranted(context: Context): Boolean {
        return try {
            context.packageManager.checkPermission(PERMISSION_MODEM_LOCATION, context.packageName) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
}
