package com.linux_core.shizuku

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Server běží jako uid 2000 (app_process), nemá přístup k `filesDir` appky
 * (vlastník uid appky) — stejný problém jako `shell_daemon`. Token i
 * permission store se proto řeší přes loopback HTTP na `LocalApiServer`
 * (127.0.0.1:1337), stejně jako `ashell`/`nh` to dělají opačným směrem.
 */
object ShizukuHttpClient {
    private const val TAG = "ShizukuHttpClient"
    private const val BASE = "http://127.0.0.1:1337"

    @Volatile var token: String? = null

    private fun request(method: String, path: String, body: String? = null): JSONObject? {
        return try {
            val url = URL("$BASE$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val out: OutputStream = conn.outputStream
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
                out.close()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream)).readText()
            if (code !in 200..299) {
                Log.w(TAG, "$method $path -> HTTP $code: $text")
                return null
            }
            JSONObject(text)
        } catch (t: Throwable) {
            Log.e(TAG, "$method $path failed", t)
            null
        }
    }

    fun isGranted(pkg: String): Boolean =
        request("GET", "/shizuku/permission?pkg=$pkg")?.optBoolean("granted", false) ?: false

    fun resolvePackage(pkg: String): JSONObject? = request("GET", "/shizuku/resolve?pkg=$pkg")
}
