package com.linux_core.core

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import java.security.MessageDigest
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.app.admin.DevicePolicyManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.telephony.TelephonyManager
import android.telephony.SignalStrength
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityTdscdma
import android.net.Uri
import android.content.ComponentName
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.linux_core.security.CertificateManager
import com.linux_core.security.KeystoreManager
import com.linux_core.security.VpnSettings
import com.linux_core.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern

object LocalApiServer {
    private const val TAG = "LocalApiServer"
    private const val PORT = 1337
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        if (isRunning) return
        isRunning = true
        appContext = context.applicationContext
        CertificateManager.init(appContext!!)
        UsbHostManager.init(appContext!!)
        // Pre-load USB bridge JNI library (actual UDS is started by ProotManager
        // with correct rootfs path when terminal opens)
        try {
            UsbFdExporter.ensureLoaded()
            Log.i(TAG, "UsbFdExporter JNI loaded (UDS starts later in ProotManager)")
        } catch (e: Exception) {
            Log.w(TAG, "UsbFdExporter not available (JNI missing?): ${e.message}")
        }
        initTts(context)
        val sharedPrefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        val shareLocalApi = sharedPrefs.getBoolean("share_local_api", false)
        val bindAddress = if (shareLocalApi && com.linux_core.core.VpnCaptureService.isRunning()) {
            com.linux_core.core.VpnCaptureService.getVpnAddress()
        } else {
            "127.0.0.1"
        }
        executor.execute {
            try {
                serverSocket = ServerSocket(PORT, 50, InetAddress.getByName(bindAddress))
                Log.i(TAG, "Local API Server started on $bindAddress:$PORT")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleConnection(context, socket) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket exception: ${e.message}")
            }
        }
    }

    fun restart(context: Context) {
        stop()
        try {
            Thread.sleep(200)
        } catch (e: Exception) {}
        start(context)
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        try {
            tts?.shutdown()
        } catch (e: Exception) {}
        tts = null
        ttsReady = false
        UsbHostManager.shutdown()
        try {
            UsbFdExporter.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UsbFdExporter: ${e.message}")
        }
    }

    private fun initTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
                Log.i(TAG, "TTS Initialized successfully")
            } else {
                Log.e(TAG, "TTS Initialization failed")
            }
        }
    }

    // Secure token for API authentication — auto-generated on first startup.
    // When the CertificateManager is active the token is stored encrypted in SharedPreferences
    // (KeystoreManager -> AES-GCM-256 with TEE key) and the plaintext lives only in memory.
    private var authToken: String? = null

    private fun getAuthToken(context: Context): String {
        if (authToken == null) {
            val prefs = context.getSharedPreferences("api_security", Context.MODE_PRIVATE)
            val ks = CertificateManager.keystore()
            val stored = prefs.getString("auth_token", null)
            if (ks != null && stored != null && stored.startsWith("enc:")) {
                authToken = ks.decryptString(stored.removePrefix("enc:"))
                    .getOrElse { fallbackToken(context) }
            } else if (stored != null && !stored.startsWith("enc:")) {
                // Legacy plain token – migrate to encrypted form, then keep it in memory.
                authToken = stored
                if (ks != null) migrateTokenToEncrypted(context, prefs, stored, ks)
            } else {
                authToken = fallbackToken(context)
            }
        }
        return authToken!!
    }

    fun getToken(context: Context): String {
        return getAuthToken(context)
    }

    private fun fallbackToken(context: Context): String {
        val prefs = context.getSharedPreferences("api_security", Context.MODE_PRIVATE)
        val ks = CertificateManager.keystore()
        val plain = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32)
        if (ks != null) {
            val b64 = ks.encryptString(plain).getOrNull()
            if (b64 != null) {
                prefs.edit().putString("auth_token", "enc:$b64").apply()
                return plain
            }
        }
        // Keystore unavailable — keep token only in memory, do NOT persist plain text to disk.
        // Token will be regenerated on next cold start (ephemeral single-session token).
        prefs.edit().remove("auth_token").apply()
        return plain
    }

    private fun migrateTokenToEncrypted(
        context: Context,
        prefs: android.content.SharedPreferences,
        plain: String,
        ks: KeystoreManager
    ) {
        try {
            val b64 = ks.encryptString(plain).getOrNull() ?: return
            prefs.edit().putString("auth_token", "enc:$b64").apply()
        } catch (_: Exception) {
            // Best-effort: if encryption fails the plain token is still in memory.
        }
    }

    private fun isAuthenticated(headers: Map<String, String>): Boolean {
        val token = headers["Authorization"] ?: headers["authorization"] ?: return false
        if (!token.startsWith("Bearer ") && !token.startsWith("Token ")) return false
        val providedToken = token.substringAfter(" ")
        // Use constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(
            providedToken.toByteArray(Charsets.UTF_8),
            (authToken ?: "").toByteArray(Charsets.UTF_8)
        )
    }

/**
      * Verifies the mandatory X-Attest-* headers sent by callers (e.g. nethunter_agent.py in
      * the PRoot) when [com.linux_core.BuildConfig.ENABLE_ATTESTATION] is true.
      *
      * If attestation is disabled the function returns `true` immediately. If attestation is 
      * enabled but attestation headers are missing, this returns `false` to fail closed - 
      * attestation is REQUIRED for sensitive endpoints when ENABLE_ATTESTATION=true.
      *
      * The headers are:
      *   X-Attest-Nonce : base64 of 32 random bytes
      *   X-Attest-Sig   : base64 of ECDSA(SHA-256, nonce || body)
      *   X-Attest-Cert  : base64 of a DER X.509 leaf cert produced by the device's
      *                    AndroidKeyStore
      */
    private fun verifyAttestationHeaders(headers: Map<String, String>, body: String): Boolean {
        if (!com.linux_core.BuildConfig.ENABLE_ATTESTATION) return true
        
        val nonceB64 = headers["x-attest-nonce"] ?: run {
            Log.w(TAG, "Attestation required but x-attest-nonce header missing")
            return false
        }
        val sigB64 = headers["x-attest-sig"] ?: run {
            Log.w(TAG, "Attestation required but x-attest-sig header missing")
            return false
        }
        val certB64 = headers["x-attest-cert"] ?: run {
            Log.w(TAG, "Attestation required but x-attest-cert header missing")
            return false
        }
        
        return try {
            val nonce = android.util.Base64.decode(nonceB64, android.util.Base64.NO_WRAP)
            val sig = android.util.Base64.decode(sigB64, android.util.Base64.NO_WRAP)
            val certBytes = android.util.Base64.decode(certB64, android.util.Base64.NO_WRAP)
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(java.io.ByteArrayInputStream(certBytes)) as java.security.cert.X509Certificate
            com.linux_core.security.AttestationVerifier.verify(
                arrayOf(cert), nonce, body.toByteArray(Charsets.UTF_8), sig
            )
        } catch (t: Throwable) {
            Log.w(TAG, "verifyAttestationHeaders failed: ${t.message}")
            false
        }
    }

    private fun handleConnection(context: Context, socket: Socket) {
        try {
            val rawIn = socket.getInputStream()
            val out = socket.getOutputStream()

            // Read the request line byte-by-byte to preserve binary body
            val requestLineBytes = readLineBytes(rawIn) ?: return
            val requestLine = String(requestLineBytes, Charsets.UTF_8)
            Log.d(TAG, "Request: $requestLine")
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"Invalid HTTP request\"}")
                return
            }
            val method = parts[0]
            val path = parts[1]

            // Check if this is a binary USB endpoint — needs raw body (no BufferedReader/String)
            val isBinaryUsbEndpoint = path == "/usb/raw_transfer" || path == "/usb/stream"

            if (isBinaryUsbEndpoint) {
                // For binary USB endpoints, read headers from raw stream, then pass
                // the remaining raw stream to the handler (don't use BufferedReader)
                val rawHeaders = readRawHeaders(rawIn)
                if (rawHeaders == null) {
                    sendResponse(out, 400, "Bad Request", "{\"error\":\"Invalid HTTP headers\"}")
                    return
                }
                
                // Parse Content-Length
                val contentLength = rawHeaders["content-length"]?.toIntOrNull() ?: 0

                // Authentication check
                if (path.startsWith("/usb/")) {
                    val isLocalConnection = try {
                        val localAddr = socket.localAddress?.hostAddress ?: "127.0.0.1"
                        val remoteAddr = socket.inetAddress?.hostAddress ?: ""
                        remoteAddr == "127.0.0.1" || remoteAddr == "::1" || remoteAddr == localAddr
                    } catch (e: Exception) { false }

                    if (!isLocalConnection) {
                        if (!isAuthenticated(rawHeaders)) {
                            sendResponse(out, 401, "Unauthorized",
                                "{\"error\":\"Authentication required\"}")
                            return
                        }
                    }
                }

                when {
                    path == "/usb/raw_transfer" -> {
                        handleUsbRawTransfer(rawIn, out, socket, method, contentLength, rawHeaders)
                    }
                    path == "/usb/stream" -> {
                        handleUsbStream(socket, context, rawIn, out, contentLength, rawHeaders)
                        return  // streaming handler closes socket itself
                    }
                }
                // raw_transfer continues — let normal flow close socket
                return  // raw_transfer handles its own response
            }

            // ── Normal (non-binary) path ─────────────────────────────────
            val reader = BufferedReader(InputStreamReader(rawIn))

            // Parse headers (remaining headers if not already read)
            var contentLength = 0
            val headers = HashMap<String, String>()
            var line: String? = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                val colonIdx = line.indexOf(':')
                if (colonIdx != -1) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                }
                line = reader.readLine()
            }

            // Read request body if present
            var body = ""
            if (contentLength > 0) {
                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = reader.read(bodyChars, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                body = String(bodyChars, 0, totalRead)
            }

            // Authentication check
            val sensitiveEndpoints = listOf("/shell", "/clipboard", "/location", "/cellinfo",
                "/notifications/active", "/accessibility/hierarchy", "/accessibility/", "/voice_input",
                "/device/admin", "/device/lock", "/apps/usage", "/rootfs/backup", "/rootfs/restore",
                "/distro/kill", "/distro/remove",
                "/vpn/logs", "/map", "/agent/query", "/wifi", "/torch", "/volume",
                "/battery/optimize", "/app/logs", "/editor/", "/usb/",
                "/vpn/ai/", "/vpn/mitm/selective")
            val isLocalConnection = try {
                val localAddr = socket.localAddress?.hostAddress ?: "127.0.0.1"
                val remoteAddr = socket.inetAddress?.hostAddress ?: ""
                remoteAddr == "127.0.0.1" || remoteAddr == "::1" || remoteAddr == localAddr
            } catch (e: Exception) { false }

            if (!isLocalConnection && sensitiveEndpoints.any { path.startsWith(it) }) {
                if (!isAuthenticated(headers)) {
                    sendResponse(out, 401, "Unauthorized",
                        "{\"error\":\"Authentication required\"}")
                    return
                }
                val attOk = verifyAttestationHeaders(headers, body)
                if (!attOk) {
                    sendResponse(out, 401, "Unauthorized",
                        "{\"error\":\"Attestation required. Send X-Attest-Nonce (b64), X-Attest-Sig (b64) and X-Attest-Cert (b64 DER) signed with the device key.\"}")
                    return
                }
            }

            routeRequest(context, method, path, body, out, isLocalConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling connection: ${e.message}", e)
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    /** Read bytes until newline (binary-safe line reading). */
    private fun readLineBytes(rawIn: java.io.InputStream): ByteArray? {
        val baos = java.io.ByteArrayOutputStream()
        var b = rawIn.read()
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) baos.write(b)
            b = rawIn.read()
        }
        return if (baos.size() == 0 && b == -1) null else baos.toByteArray()
    }

    /** Read HTTP headers from raw stream (binary-safe, returns lowercase keys). */
    private fun readRawHeaders(rawIn: java.io.InputStream): Map<String, String>? {
        val headers = HashMap<String, String>()
        while (true) {
            val lineBytes = readLineBytes(rawIn) ?: return null
            if (lineBytes.isEmpty()) break  // end of headers
            val line = String(lineBytes, Charsets.UTF_8)
            val colonIdx = line.indexOf(':')
            if (colonIdx != -1) {
                val key = line.substring(0, colonIdx).trim().lowercase()
                val value = line.substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    private fun routeRequest(context: Context, method: String, path: String, body: String, out: OutputStream, isLocalConnection: Boolean = true) {
        try {
            when {
                path == "/battery" && method == "GET" -> handleBattery(context, out)
                path == "/vibrate" && method == "POST" -> handleVibrate(context, body, out)
                path == "/toast" && method == "POST" -> handleToast(context, body, out)
                path == "/tts" && method == "POST" -> handleTts(body, out)
                path == "/clipboard" && method == "GET" -> handleClipboardGet(context, out)
                path == "/clipboard" && method == "POST" -> handleClipboardSet(context, body, out)
                path == "/notification" && method == "POST" -> handleNotification(context, body, out)
                path == "/git-agent/notify" && method == "POST" -> handleGitAgentNotify(context, body, out)
                path == "/git-agent/action" && method == "POST" -> handleGitAgentAction(context, body, out)
                path == "/wifi" && method == "GET" -> handleWifi(context, out)
                path == "/wifi" && method == "POST" -> handleWifiControl(context, body, out)
                path == "/device/admin" && method == "GET" -> handleDeviceAdminStatus(context, out)
                path == "/device/admin" && method == "POST" -> handleDeviceAdminRequest(context, out)
                path == "/device/lock" && method == "POST" -> handleDeviceLock(context, out)
                path == "/location" && method == "GET" -> handleLocation(context, out)
                path == "/cellinfo" && method == "GET" -> handleCellInfo(context, out)
                path == "/volume" && method == "GET" -> handleVolumeGet(context, out)
                path == "/volume" && method == "POST" -> handleVolumeSet(context, body, out)
                path == "/torch" && method == "POST" -> handleTorch(context, body, out)
                path == "/shell" && method == "POST" -> handleShell(body, out)
                path == "/ashell" && method == "POST" -> handleAshell(context, body, out)
                path == "/vpn" && method == "GET" -> handleVpnStatus(context, out)
                path.startsWith("/vpn/logs") && method == "GET" -> handleVpnLogs(path, out)
                path == "/vpn/stop" && method == "POST" -> handleVpnStop(context, out)
                path == "/vpn/start" && method == "POST" -> handleVpnStart(context, out)
                path.startsWith("/vpn/ignore") && method == "GET" -> handleVpnIgnoreGet(path, out)
                path.startsWith("/vpn/ignore") && method == "POST" -> handleVpnIgnorePost(path, out)
                path == "/agent/query" && method == "POST" -> handleAgentQuery(body, out)
                path == "/api/share" && method == "GET" -> handleApiShareGet(context, out)
                path == "/api/share" && method == "POST" -> handleApiSharePost(context, body, out)
                path == "/voice_input" && method == "GET" -> handleVoiceInput(context, out)
                path == "/apps/usage" && method == "GET" -> handleAppsUsage(context, out)
                path == "/notifications/active" && method == "GET" -> handleNotificationsActive(context, out)
                path == "/accessibility/hierarchy" && method == "GET" -> handleAccessibilityHierarchy(out)
                path == "/accessibility/status" && method == "GET" -> handleAccessibilityStatus(out)
                path == "/accessibility/tap" && method == "POST" -> handleAccessibilityTap(body, out)
                path == "/accessibility/click" && method == "POST" -> handleAccessibilityClick(body, out)
                path == "/accessibility/longclick" && method == "POST" -> handleAccessibilityLongClick(body, out)
                path == "/accessibility/swipe" && method == "POST" -> handleAccessibilitySwipe(body, out)
                path == "/accessibility/text" && method == "POST" -> handleAccessibilityText(body, out)
                path == "/accessibility/scroll" && method == "POST" -> handleAccessibilityScroll(body, out)
                path == "/accessibility/global" && method == "POST" -> handleAccessibilityGlobal(body, out)
                path == "/battery/optimize" && method == "GET" -> handleBatteryOptimizeGet(context, out)
                path == "/battery/optimize" && method == "POST" -> handleBatteryOptimizePost(context, out)
                path == "/distro/list" && method == "GET" -> handleDistroList(context, out)
                path == "/distro/ps" && method == "GET" -> handleDistroPs(context, out)
                path == "/distro/kill" && method == "POST" -> handleDistroKill(context, body, out)
                path == "/distro/remove" && method == "POST" -> handleDistroRemove(context, body, out)
                path == "/rootfs/backup" && method == "POST" -> handleRootfsBackup(context, out)
                path == "/rootfs/restore" && method == "POST" -> handleRootfsRestore(context, body, out)
                path == "/map" && method == "GET" -> handleMap(context, out)
                path == "/vpn/mitm" && method == "POST" -> handleVpnMitmPost(context, body, out)
                path == "/vpn/mitm" && method == "GET" -> handleVpnMitmGet(context, out)
                path == "/vpn/mitm/ca" && method == "GET" -> handleVpnMitmCa(context, out)
                path.startsWith("/vpn/mitm/logs") && method == "GET" -> handleVpnMitmLogs(context, path, out)
                path == "/vpn/mitm/sni-fallback" && method == "POST" -> handleVpnMitmSniFallbackPost(context, body, out)
                path == "/vpn/mitm/sni-fallback" && method == "GET" -> handleVpnMitmSniFallbackGet(context, out)
                path == "/vpn/ai/pending" && method == "GET" -> handleVpnAiPending(out)
                path == "/vpn/ai/history" && method == "GET" -> handleVpnAiHistory(path, out)
                path == "/vpn/ai/verdict" && method == "POST" -> handleVpnAiVerdict(context, body, out)
                path == "/vpn/ai/notify" && method == "POST" -> handleVpnAiNotify(context, body, out)
                path == "/vpn/ai/summary" && method == "GET" -> handleVpnAiSummary(out)
                path == "/vpn/mitm/selective" && method == "POST" -> handleVpnMitmSelective(body, out)
                path == "/app/logs/level" && method == "GET" -> handleAppLogsLevel(context, out)
                path == "/app/logs/level" && method == "POST" -> handleAppLogsLevelSet(context, body, out)
                path.startsWith("/app/logs") && method == "GET" -> handleAppLogs(context, path, out)
                path == "/editor/start" && method == "POST" -> handleEditorStart(context, out)
                path == "/editor/stop" && method == "POST" -> handleEditorStop(context, out)
                path == "/editor/status" && method == "GET" -> handleEditorStatus(context, out)
                path == "/editor/password" && method == "GET" -> handleEditorPassword(context, out, isLocalConnection)
                path == "/editor/info" && method == "GET" -> handleEditorInfo(context, out)
                path == "/editor/install" && method == "POST" -> handleEditorInstall(context, out)

                // ─── USB Host endpoints ─────────────────────────────────────
                path == "/usb/devices" && method == "GET" -> handleUsbDevices(context, out)
                path == "/usb/permission" && method == "POST" -> handleUsbPermission(context, body, out)
                path == "/usb/claim" && method == "POST" -> handleUsbClaim(context, body, out)
                path == "/usb/release" && method == "POST" -> handleUsbRelease(body, out)
                path == "/usb/bulk_transfer" && method == "POST" -> handleUsbBulkTransfer(body, out)
                path == "/usb/control_transfer" && method == "POST" -> handleUsbControlTransfer(body, out)
                path == "/usb/send" && method == "POST" -> handleUsbSendRaw(body, out)
                path == "/usb/export_fd" && method == "POST" -> handleUsbExportFd(body, out)
                path == "/usb/raw_transfer" && method == "POST" -> sendResponse(out, 501, "Not Implemented", "{\"error\":\"Use handleConnection binary path\"}")
                path == "/usb/raw_transfer" && method == "GET" -> sendResponse(out, 501, "Not Implemented", "{\"error\":\"Use handleConnection binary path\"}")
                path == "/usb/stream" && method == "POST" -> sendResponse(out, 501, "Not Implemented", "{\"error\":\"Use handleConnection binary path\"}")
                else -> sendResponse(out, 404, "Not Found", "{\"error\":\"Endpoint not found\"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error routing request: ${e.message}", e)
            sendResponse(out, 500, "Internal Server Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun sendResponse(out: OutputStream, statusCode: Int, statusText: String, jsonResponse: String) {
        val rawResponse = jsonResponse.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${rawResponse.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(rawResponse)
        out.flush()
    }

    /**
     * Send HTTP response with keep-alive (for USB endpoints where multiple
     * transfers happen in rapid succession — critical for BROM timing).
     */
    private fun sendResponseKeepAlive(out: OutputStream, statusCode: Int, statusText: String, data: ByteArray, contentType: String = "application/octet-stream") {
        val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Connection: keep-alive\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Pragma: no-cache\r\n\r\n"
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(data)
        out.flush()
    }

    private fun handleBattery(context: Context, out: OutputStream) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = context.registerReceiver(null, filter)
        if (batteryIntent == null) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"Could not query battery state\"}")
            return
        }

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percentage = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val temperature = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)

        val statusInt = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val status = when (statusInt) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "unknown"
        }

        val healthInt = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val health = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
            else -> "unknown"
        }

        val pluggedInt = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val plugged = when (pluggedInt) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }

        val json = JSONObject().apply {
            put("percentage", percentage)
            put("temperature", temperature)
            put("voltage", voltage)
            put("status", status)
            put("health", health)
            put("plugged", plugged)
        }.toString()

        Log.i(TAG, "Battery EXECUTED: level=${percentage}% status=${status} temp=${temperature}C plugged=${plugged}")
        sendResponse(out, 200, "OK", json)
    }

    private fun handleVibrate(context: Context, body: String, out: OutputStream) {
        val durationMs = body.trim().toLongOrNull() ?: 500L
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) {
            sendResponse(out, 200, "OK", "{\"status\":\"no_vibrator\",\"duration\":$durationMs,\"error\":\"Device has no vibrator hardware\"}")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
        Log.i(TAG, "Vibrate EXECUTED: ${durationMs}ms")
        sendResponse(out, 200, "OK", "{\"status\":\"vibrated\",\"duration\":$durationMs}")
    }

    private fun handleToast(context: Context, body: String, out: OutputStream) {
        val message = body.ifEmpty { "Hello from API!" }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "Toast EXECUTED: \"$message\"")
        sendResponse(out, 200, "OK", "{\"status\":\"toasted\"}")
    }

    private fun handleTts(body: String, out: OutputStream) {
        val text = body.trim()
        if (text.isEmpty()) {
            sendResponse(out, 400, "Bad Request", "{\"error\":\"Text to speak cannot be empty\"}")
            return
        }
        if (!ttsReady || tts == null) {
            sendResponse(out, 500, "Service Unavailable", "{\"error\":\"TTS engine is initializing or unavailable\"}")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nethunter_api_tts")
        } else {
            @Suppress("DEPRECATION")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
        }
        Log.i(TAG, "TTS EXECUTED: \"${text.take(60)}\"")
        sendResponse(out, 200, "OK", "{\"status\":\"spoken\"}")
    }

    private fun handleClipboardGet(context: Context, out: OutputStream) {
        val latch = java.util.concurrent.CountDownLatch(1)
        var clipboardText = ""
        var errorMsg: String? = null

        handler.post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                clipboardText = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(context).toString()
                } else {
                    ""
                }
            } catch (e: Exception) {
                errorMsg = e.message
            } finally {
                latch.countDown()
            }
        }

        try {
            val completed = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                sendResponse(out, 504, "Gateway Timeout", "{\"error\":\"Clipboard retrieval timed out\"}")
                return
            }
            if (errorMsg != null) {
                sendResponse(out, 500, "Internal Error", JSONObject().put("error", errorMsg).toString())
                return
            }
            Log.i(TAG, "Clipboard READ EXECUTED: ${clipboardText.take(40)}")
            sendResponse(out, 200, "OK", JSONObject().put("text", clipboardText).toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleClipboardSet(context: Context, body: String, out: OutputStream) {
        val latch = java.util.concurrent.CountDownLatch(1)
        var errorMsg: String? = null

        handler.post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = android.content.ClipData.newPlainText("nethunter_api", body)
                clipboard.setPrimaryClip(clip)
            } catch (e: Exception) {
                errorMsg = e.message
            } finally {
                latch.countDown()
            }
        }

        try {
            val completed = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                sendResponse(out, 504, "Gateway Timeout", "{\"error\":\"Clipboard update timed out\"}")
                return
            }
            if (errorMsg != null) {
                sendResponse(out, 500, "Internal Error", JSONObject().put("error", errorMsg).toString())
                return
            }
            Log.i(TAG, "Clipboard WRITE EXECUTED: ${body.take(40)}")
            sendResponse(out, 200, "OK", "{\"status\":\"updated\"}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleNotification(context: Context, body: String, out: OutputStream) {
        try {
            val json = if (body.trim().isNotEmpty()) JSONObject(body) else JSONObject()
            val title = json.optString("title", "NetHunter API")
            val content = json.optString("content", "")
            if (content.isEmpty()) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"content cannot be empty\"}")
                return
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(context, "terminal_sessions")
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            manager.notify(System.currentTimeMillis().toInt(), notification)
            Log.i(TAG, "Notification POSTED: title=\"$title\" | $content")
            sendResponse(out, 200, "OK", "{\"status\":\"notified\"}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    // ─── Git Agent Interactive Notifications ─────────────────────────────────

    private fun handleGitAgentNotify(context: Context, body: String, out: OutputStream) {
        try {
            val json = if (body.trim().isNotEmpty()) JSONObject(body) else JSONObject()
            val repoId = json.optString("repo_id", "")
            val repoPath = json.optString("repo_path", "")
            val branch = json.optString("branch", "main")
            val commitMsg = json.optString("commit_msg", "auto-commit")

            if (repoId.isEmpty() || repoPath.isEmpty()) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"repo_id and repo_path are required\"}")
                return
            }

            val notificationId = repoId.hashCode() + 1000
            GitAgentNotifier.showInteractiveNotification(
                context = context,
                repoId = repoId,
                repoPath = repoPath,
                branch = branch,
                commitMsg = commitMsg,
                notificationId = notificationId
            )

            sendResponse(out, 200, "OK", "{\"status\":\"notified\",\"repo_id\":\"$repoId\",\"notification_id\":$notificationId}")
            Log.i(TAG, "GitAgent interactive notification posted: repo=$repoPath id=$notificationId")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleGitAgentAction(context: Context, body: String, out: OutputStream) {
        try {
            val json = if (body.trim().isNotEmpty()) JSONObject(body) else JSONObject()
            val repoId = json.optString("repo_id", "")
            val action = json.optString("action", "none")
            val repoPath = json.optString("repo_path", "")
            val branch = json.optString("branch", "main")

            // Write action file for PRoot git-agent to process
            val actionDir = java.io.File("/sdcard/.git-agent/actions")
            actionDir.mkdirs()

            val timestamp = android.text.format.DateFormat.format("yyyyMMdd-HHmmss", System.currentTimeMillis())
            val repoHash = repoPath.hashCode().toString().replace("-", "")
            val actionFile = java.io.File(actionDir, "${timestamp}-${repoHash}.json")

            val actionJson = "{\"repo\":\"$repoPath\",\"branch\":\"$branch\",\"action\":\"$action\",\"repo_id\":\"$repoId\",\"ts\":\"$timestamp\",\"status\":\"pending\"}"
            actionFile.writeText(actionJson)

            Log.i(TAG, "GitAgent action received: repo=$repoPath action=$action file=${actionFile.absolutePath}")
            sendResponse(out, 200, "OK", "{\"status\":\"queued\",\"action\":\"$action\",\"repo\":\"$repoPath\"}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleWifi(context: Context, out: OutputStream) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            val json = JSONObject().apply {
                if (info != null) {
                    put("ssid", info.ssid?.replace("\"", ""))
                    put("bssid", info.bssid)
                    put("rssi", info.rssi)
                    put("link_speed_mbps", info.linkSpeed)
                    // IP + MAC z DHCP/WifiInfo, aby `ifconfig` mohl zobrazit inet/ether
                    @Suppress("DEPRECATION")
                    val dhcp = wifiManager.dhcpInfo
                    if (dhcp != null && dhcp.ipAddress != 0) {
                        put("ip", formatIpv4(dhcp.ipAddress))
                        put("netmask", formatIpv4(dhcp.netmask))
                        put("gateway", formatIpv4(dhcp.gateway))
                        put("dns1", formatIpv4(dhcp.dns1))
                    } else {
                        put("ip", "")
                    }
                    @Suppress("DEPRECATION")
                    val mac = try { info.macAddress } catch (e: Exception) { null }
                    put("mac", mac ?: "")
                } else {
                    put("error", "No connection info available")
                }
            }.toString()
            sendResponse(out, 200, "OK", json)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun formatIpv4(value: Int): String {
        return "${value and 0xFF}.${(value shr 8) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 24) and 0xFF}"
    }

    private fun handleLocation(context: Context, out: OutputStream) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var location: Location? = null
            try {
                location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (_: SecurityException) {}

            val json = JSONObject().apply {
                if (location != null) {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy.toDouble())
                    put("provider", location.provider)
                    put("time", location.time)
                    put("maps_url", "https://www.google.com/maps?q=${location.latitude},${location.longitude}")
                    put("geo_uri", "geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}")
                } else {
                    put("error", "No last known location available. Check permissions and GPS.")
                }
            }.toString()
            sendResponse(out, 200, "OK", json)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleCellInfo(context: Context, out: OutputStream) {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val json = JSONObject()

            json.put("network_type", getNetworkTypeName(tm))
            json.put("carrier", tm.networkOperatorName ?: "Unknown")
            json.put("sim_carrier", tm.simOperatorName ?: "Unknown")
            json.put("data_state", when (tm.dataState) {
                TelephonyManager.DATA_CONNECTED -> "CONNECTED"
                TelephonyManager.DATA_CONNECTING -> "CONNECTING"
                TelephonyManager.DATA_DISCONNECTED -> "DISCONNECTED"
                TelephonyManager.DATA_SUSPENDED -> "SUSPENDED"
                else -> "UNKNOWN"
            })
            json.put("is_roaming", tm.isNetworkRoaming)

            try {
                val signal = tm.signalStrength
                if (signal != null) {
                    val level = signal.level
                    json.put("signal_level", level)
                    json.put("signal_max", 4)
                    json.put("signal_bar", "█".repeat(level.coerceIn(0, 4)) + "░".repeat((4 - level).coerceAtLeast(0)))
                }
            } catch (_: Exception) {}

            val cellsArray = org.json.JSONArray()
            try {
                val cellList = tm.allCellInfo
                if (cellList != null) {
                    for (cell in cellList) {
                        val cellObj = JSONObject()
                        cellObj.put("type", cell::class.java.simpleName.replace("CellInfo", ""))
                        cellObj.put("registered", cell.isRegistered)
                        cellObj.put("timestamp", cell.timestampMillis)

                        try {
                            @Suppress("DEPRECATION")
                            val ci = cell.cellIdentity
                            when (ci) {
                                is CellIdentityGsm -> {
                                    @Suppress("DEPRECATION")
                                    cellObj.put("mcc", ci.mcc)
                                    @Suppress("DEPRECATION")
                                    cellObj.put("mnc", ci.mnc)
                                    cellObj.put("tac_lac", ci.lac)
                                    cellObj.put("cid", ci.cid)
                                    @Suppress("DEPRECATION")
                                    cellObj.put("pci_psc", ci.psc.takeIf { it != Int.MAX_VALUE })
                                }
                                is CellIdentityLte -> {
                                    @Suppress("DEPRECATION")
                                    cellObj.put("mcc", ci.mcc)
                                    @Suppress("DEPRECATION")
                                    cellObj.put("mnc", ci.mnc)
                                    cellObj.put("tac_lac", ci.tac)
                                    cellObj.put("cid", ci.ci)
                                    cellObj.put("pci_psc", ci.pci.takeIf { it != Int.MAX_VALUE })
                                }
                                is CellIdentityWcdma -> {
                                    cellObj.put("tac_lac", ci.lac)
                                    cellObj.put("cid", ci.cid)
                                    cellObj.put("pci_psc", ci.psc.takeIf { it != Int.MAX_VALUE })
                                }
                                is CellIdentityNr -> {
                                    cellObj.put("tac_lac", ci.tac)
                                    cellObj.put("cid", ci.nci)
                                    cellObj.put("pci_psc", ci.pci.takeIf { it != Int.MAX_VALUE })
                                }
                                is CellIdentityTdscdma -> {
                                    cellObj.put("tac_lac", ci.lac)
                                    cellObj.put("cid", ci.cid)
                                    cellObj.put("pci_psc", ci.cpid.takeIf { it != Int.MAX_VALUE })
                                }
                            }
                        } catch (_: Exception) {}
                        try {
                            val ss = cell.cellSignalStrength
                            cellObj.put("signal_dbm", ss.dbm)
                            cellObj.put("signal_level", ss.level)
                            cellObj.put("asu", ss.asuLevel)
                        } catch (_: Exception) {}
                        cellsArray.put(cellObj)
                    }
                }
            } catch (_: SecurityException) {
                cellsArray.put(JSONObject().apply { put("error", "Location permission required for cell info") })
            } catch (_: Exception) {}
            json.put("cells", cellsArray)

            sendResponse(out, 200, "OK", json.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun getNetworkTypeName(tm: TelephonyManager): String {
        return try {
            @Suppress("DEPRECATION")
            when (tm.networkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                TelephonyManager.NETWORK_TYPE_HSPAP -> "4G HSPA+"
                TelephonyManager.NETWORK_TYPE_HSDPA -> "3.5G HSDPA"
                TelephonyManager.NETWORK_TYPE_HSUPA -> "3.5G HSUPA"
                TelephonyManager.NETWORK_TYPE_HSPA -> "3.5G HSPA"
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G EVDO-B"
                TelephonyManager.NETWORK_TYPE_EVDO_A -> "3G EVDO-A"
                TelephonyManager.NETWORK_TYPE_EVDO_0 -> "3G EVDO"
                TelephonyManager.NETWORK_TYPE_1xRTT -> "2.5G CDMA"
                TelephonyManager.NETWORK_TYPE_EDGE -> "2.5G EDGE"
                TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
                TelephonyManager.NETWORK_TYPE_GSM -> "2G GSM"
                TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN"
                TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G TD-SCDMA"
                TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
                else -> "Unknown(${tm.networkType})"
            }
        } catch (_: Exception) { "N/A" }
    }

    private fun handleVolumeGet(context: Context, out: OutputStream) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val json = JSONObject().apply {
                put("stream", "music")
                put("volume", current)
                put("max_volume", max)
            }.toString()
            Log.i(TAG, "Volume READ EXECUTED: music=$current/$max")
            sendResponse(out, 200, "OK", json)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVolumeSet(context: Context, body: String, out: OutputStream) {
        try {
            val volume = body.trim().toIntOrNull()
            if (volume == null) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"volume level must be an integer\"}")
                return
            }
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = volume.coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            Log.i(TAG, "Volume SET EXECUTED: music=$target (max=$max)")
            sendResponse(out, 200, "OK", "{\"status\":\"volume_set\",\"volume\":$target}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleTorch(context: Context, body: String, out: OutputStream) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            sendResponse(out, 500, "Not Supported", "{\"error\":\"Torch control is only supported on Android 6.0+\"}")
            return
        }
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val firstCamera = cm.cameraIdList.firstOrNull()
            if (firstCamera == null) {
                sendResponse(out, 500, "Internal Error", "{\"error\":\"No camera found\"}")
                return
            }
            val enable = body.trim().equals("on", ignoreCase = true)
            cm.setTorchMode(firstCamera, enable)
            Log.i(TAG, "Torch EXECUTED: ${if (enable) "on" else "off"} (camera=$firstCamera)")
            sendResponse(out, 200, "OK", "{\"status\":\"torch_updated\",\"state\":\"${if (enable) "on" else "off"}\"}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    // ── Shell command security ──────────────────────────────────────────────
    // Allowlist of safe shell commands (first word must match)
    private val SHELL_ALLOWLIST = setOf(
        // Diagnostic
        "ls", "cat", "echo", "printf", "pwd", "whoami", "id", "uname",
        "ps", "df", "du", "free", "uptime", "date", "which", "find",
        "grep", "egrep", "fgrep", "rg", "head", "tail", "wc", "sort", "cut",
        "tr", "sed", "awk", "xargs", "tee", "basename", "dirname",
        "readlink", "realpath", "stat", "file",
        // Network
        "ping", "ping6", "curl", "wget", "netstat", "ss", "ip", "ifconfig",
        "nslookup", "dig", "host", "traceroute", "tracepath", "mtr", "nc", "ncat",
        // Filesystem (safe subset — rm is allowed but guarded by DESTRUCTIVE_PATTERNS)
        "touch", "chmod", "chown", "ln", "mkdir", "rmdir",
        "tar", "gzip", "gunzip", "bzip2", "xz", "unzip", "zip",
        "cp", "mv", "rm",
        // Package management
        "apt", "apt-get", "dpkg", "pip", "pip3", "npm",
        // System / Android
        "env", "printenv", "getprop", "dmesg", "logcat",
        // Interpreters (inline code can still be dangerous — DESTRUCTIVE_PATTERNS also scans
        // inside the argument string so e.g. 'rm -rf /' inside a python -c is caught)
        "python", "python3", "perl",
        "bash", "sh", "zsh",
        // Project CLI
        "nh", "nethunter", "vpn-cli",
        // Editors / pagers
        "nano", "less", "more", "vim", "vi",
        // Utility
        "free", "w", "who", "users", "last",
        "diff", "cmp", "patch",
        "clear", "reset", "history",
        "test", "expr",
        "sleep", "timeout",
        "dd"
    )

    // Destructive patterns — checked across the entire command string *after*
    // the allowlist gate, so they catch attempts like `python3 -c "import os; os.system('rm -rf /')"`.
    private val DESTRUCTIVE_PATTERNS = listOf(
        "rm -rf /", "rm -rf /*", "rm -rf *", "rm -rf .", "rm -rf ~",
        "rm -fr /", "rm -fr /*", "rm -fr *", "rm -fr .", "rm -fr ~",
        "mkfs.", "mkfs ", "mkswap",
        "dd if=/dev/zero", "dd if=/dev/random", "dd if=/dev/urandom",
        ">/dev/sda", ">/dev/sdb", ">/dev/sdc", ">/dev/sdd",
        ">/dev/mem", ">/dev/kmem", ">/dev/port",
        "fdisk", "parted", "cfdisk",
        "reboot", "shutdown", "poweroff", "halt", "init 0", "init 6",
        "> /proc/", ">/proc/",
        ":(){ :|:& };:",  // fork bomb
        "chmod -R 0 /", "chown -R 0 /",
        "mv /", "mv /*",
        "cat /dev/sda", "cat /dev/sdb", "cat /dev/mem"
    )

    /**
     * Host shell environment pro `sh -c` (ashell -c, /shell API).
     * App process dědí výchozí PATH (/system/bin:/system/xbin...) a NEMÁ
     * cesty k host-side nástrojům (files/usr/bin) ani LD_LIBRARY_PATH na
     * glibc (files/usr/lib). Bez toho ashell -c vidí jen toybox binárky a
     * GNU sed/nano/rsync z usertools nejdou spustit. Sestavujeme env ve
     * stejném vzoru jako startAshellSession() v TerminalActivity.
     */
    private fun hostShellEnv(): Array<String> {
        val ctx = appContext ?: return emptyArray()
        val filesDir = ctx.filesDir
        val hostPrefixBin = File(filesDir, "usr/bin").absolutePath
        val hostPrefixLib = File(filesDir, "usr/lib").absolutePath
        val basePath = "/system/bin:/system/xbin:/vendor/bin"
        // usr/bin na začátku: GNU sed/nano/rsync/rg přebíjejí toybox
        val fullPath = "$hostPrefixBin:$basePath:${filesDir.absolutePath}"
        return arrayOf(
            "HOME=${filesDir.absolutePath}",
            "USER=app",
            "PATH=$fullPath",
            "PREFIX=${File(filesDir, "usr").absolutePath}",
            "LD_LIBRARY_PATH=$hostPrefixLib",
            "TERM=xterm-256color",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system"
        )
    }

    private fun handleShell(body: String, out: OutputStream) {
        try {
            val command = body.trim()
            if (command.isEmpty()) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"Command cannot be empty\"}")
                return
            }

            // Enforce maximum command length
            if (command.length > 1024) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"Command too long (max 1024 chars)\"}")
                return
            }

            // ── 1. Allowlist gate ───────────────────────────────────────────
            val cmdName = command
                .trim()
                .substringBefore(" ")
                .substringBefore("\t")
                .substringAfterLast("/")
            if (cmdName !in SHELL_ALLOWLIST) {
                sendResponse(out, 403, "Forbidden",
                    "{\"error\":\"Command '$cmdName' is not in the allowed commands list\"}")
                return
            }

            // ── 2. Destructive patterns guard ───────────────────────────────
            val commandLower = command.lowercase()
            for (pattern in DESTRUCTIVE_PATTERNS) {
                if (commandLower.contains(pattern.lowercase())) {
                    sendResponse(out, 403, "Forbidden",
                        "{\"error\":\"Command blocked for security reasons\"}")
                    return
                }
            }

            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command), hostShellEnv())
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            // Use explicit quoting to avoid org.json toString() escaping bugs
            // on some Android builds (raw control chars inside JSON strings).
            val json = buildString {
                append("{")
                append("\"exit_code\":").append(exitCode).append(",")
                append("\"stdout\":").append(JSONObject.quote(output)).append(",")
                append("\"stderr\":").append(JSONObject.quote(error))
                append("}")
            }
            sendResponse(out, 200, "OK", json)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    /**
     * ashell — escape proot → host app shell.
     *
     * Spustí interaktivní shell v kontextu aplikace (uid aplikace, HOME = filesDir,
     * mimo PRoot). Slouží k tomu, aby uživatel zevnitř guestu (root@kali) mohl
     * přepnout do hostitelského prostředí Androidu.
     *
     * Implementace: spustí novou TerminalActivity s rootfsDirName="ashell-host",
     * což donutí TerminalService vytvořit nový ProcessBuilder, který NEpoužívá
     * PRoot, ale přímo /system/bin/sh s cestou k host filesDir.
     *
     * Vstup: POST /ashell  (body ignorováno, ashell je vždy interaktivní).
     * Výstup: 200 + JSON s {status, pwd, uid}.
     */
    private fun handleAshell(context: Context, body: String, out: OutputStream) {
        val ctx = appContext ?: run {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"App context not initialized\"}")
            return
        }
        try {
            // Spustíme novou TerminalActivity v "ashell módu" (rootfsDirName="ashell-host")
            // FLAG_ACTIVITY_NEW_TASK + FLAG_ACTIVITY_NEW_DOCUMENT + FLAG_ACTIVITY_MULTIPLE_TASK
            // překoná singleTask launchMode a otevře novou instanci pro ashell escape.
            val intent = Intent(ctx, com.linux_core.ui.terminal.TerminalActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                putExtra("rootfsDirName", "ashell-host")
                putExtra("mountStorage", false)
                putExtra("ashellMode", true)
            }
            ctx.startActivity(intent)
            sendResponse(out, 200, "OK", JSONObject().apply {
                put("status", "ashell_started")
                put("uid", Process.myUid())
                put("pwd", ctx.filesDir.absolutePath)
                put("user", "app")
            }.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnLogs(path: String, out: OutputStream) {
        val params = parseQueryParams(path)
        val format = params["format"]
        val logs = VpnLogManager.getLogs()
        if (format == "csv" || path.contains("/csv")) {
            val csv = StringBuilder()
            csv.append("timestamp,protocol,srcIp,srcPort,dstIp,dstPort,size,category,detail,entropy\n")
            for (log in logs) {
                val escapedDetail = log.detail.replace("\"", "\"\"")
                csv.append("${log.timestamp},${log.protocol},${log.srcIp},${log.srcPort},${log.dstIp},${log.dstPort},${log.size},${log.category.name},\"$escapedDetail\",${log.entropy}\n")
            }
            sendCsvResponse(out, 200, "OK", csv.toString())
        } else {
            val array = org.json.JSONArray()
            logs.forEach { array.put(it.toJsonObject()) }
            sendResponse(out, 200, "OK", array.toString())
        }
    }

    private fun sendCsvResponse(out: OutputStream, statusCode: Int, statusText: String, csvResponse: String) {
        val rawResponse = csvResponse.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: text/csv\r\n" +
                "Content-Length: ${rawResponse.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(rawResponse)
        out.flush()
    }

    private fun handleVpnStatus(context: Context, out: OutputStream) {
        val running = com.linux_core.core.VpnCaptureService.isRunning()
        val packets = com.linux_core.core.VpnCaptureService.getCapturedPacketCount()
        val bytes = com.linux_core.core.VpnCaptureService.getCapturedByteCount()
        val vpnIp = com.linux_core.core.VpnCaptureService.getVpnAddress()
        val json = JSONObject().apply {
            put("running", running)
            put("packets", packets)
            put("bytes", bytes)
            put("vpn_ip", vpnIp)
        }.toString()
        sendResponse(out, 200, "OK", json)
    }

    private fun handleVpnStop(context: Context, out: OutputStream) {
        try {
            val intent = Intent(context, VpnCaptureService::class.java).apply {
                action = VpnCaptureService.ACTION_STOP
            }
            context.startService(intent)
            sendResponse(out, 200, "OK", "{\"status\":\"stopping\"}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnStart(context: Context, out: OutputStream) {
        try {
            val intent = Intent(context, VpnCaptureService::class.java).apply {
                action = VpnCaptureService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            sendResponse(out, 200, "OK", "{\"status\":\"starting\"}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun parseQueryParams(path: String): Map<String, String> {
        val params = HashMap<String, String>()
        val queryStart = path.indexOf('?')
        if (queryStart != -1 && queryStart + 1 < path.length) {
            val query = path.substring(queryStart + 1)
            val pairs = query.split("&")
            for (pair in pairs) {
                val idx = pair.indexOf("=")
                if (idx != -1) {
                    try {
                        val key = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                        val value = if (idx + 1 < pair.length) {
                            java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                        } else {
                            ""
                        }
                        params[key] = value
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode query param: ${e.message}")
                    }
                }
            }
        }
        return params
    }

    private fun handleVpnIgnoreGet(path: String, out: OutputStream) {
        val params = parseQueryParams(path)
        val sessionId = params["session_id"]
        if (sessionId.isNullOrEmpty()) {
            sendResponse(out, 400, "Bad Request", "{\"error\":\"session_id parameter is required\"}")
            return
        }
        val isIgnored = TerminalService.isSessionVpnIgnoredById(sessionId)
        sendResponse(out, 200, "OK", "{\"session_id\":\"$sessionId\",\"ignored\":$isIgnored}")
    }

    private fun handleVpnIgnorePost(path: String, out: OutputStream) {
        val params = parseQueryParams(path)
        val sessionId = params["session_id"]
        val ignoredStr = params["ignored"] ?: "true"
        if (sessionId.isNullOrEmpty()) {
            sendResponse(out, 400, "Bad Request", "{\"error\":\"session_id parameter is required\"}")
            return
        }
        val ignored = ignoredStr.toBoolean()
        TerminalService.setSessionVpnIgnored(sessionId, ignored)
        sendResponse(out, 200, "OK", "{\"session_id\":\"$sessionId\",\"ignored\":$ignored}")
    }

    /** Volatile status string updated during agent query processing.
     *  NetHunterAssistantSession reads this for real-time UI updates. */
    @Volatile
    @JvmField
    var currentAgentStatus: String = ""

    private fun callAgentDaemon(prompt: String, context: Context): String? {
        try {
            val agentToken = getOrCreateAgentToken(context)
            val url = java.net.URL("http://127.0.0.1:13338/query")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = 0
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $agentToken")

            val payload = JSONObject().put("prompt", prompt).toString()
            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${conn.responseCode}"
                Log.w(TAG, "Agent daemon returned $conn.responseCode: $errText")
                return null
            }
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "Agent daemon not reachable on port 13338")
            return null
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "Agent daemon connect timeout")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Agent daemon call failed: ${e.message}")
            return null
        }
    }

    private fun handleAgentQuery(body: String, out: OutputStream) {
        val prompt = try {
            if (body.trim().startsWith("{")) {
                JSONObject(body).optString("prompt", "")
            } else {
                body.trim()
            }
        } catch (e: Exception) {
            body.trim()
        }

        if (prompt.isEmpty()) {
            sendResponse(out, 400, "Bad Request", "{\"error\":\"Empty prompt\"}")
            return
        }

        currentAgentStatus = "Connecting to agent..."
        val ctx = appContext ?: run {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"App context not initialized\"}")
            return
        }
        val launcherScript = java.io.File(ctx.filesDir, "launcher.sh")

        // Try daemon first (fast path) with auth token
        currentAgentStatus = "Connecting to agent..."
        val daemonResponse = callAgentDaemon(prompt, ctx)
        if (daemonResponse != null) {
            currentAgentStatus = ""
            sendResponse(out, 200, "OK", daemonResponse)
            return
        }

        // Self-healing: try to start the daemon
        if (launcherScript.exists() && launcherScript.canExecute()) {
            try {
                currentAgentStatus = "Starting agent daemon..."
                val pbStart = ProcessBuilder("sh", launcherScript.absolutePath, "nethunter-agent-cli", "start")
                pbStart.directory(ctx.filesDir)
                val procStart = pbStart.start()
                procStart.waitFor()
                Thread.sleep(1500) // Give the daemon 1.5s to bind to the port

                currentAgentStatus = "Connecting to agent..."
                val retryResponse = callAgentDaemon(prompt, ctx)
                if (retryResponse != null) {
                    currentAgentStatus = ""
                    sendResponse(out, 200, "OK", retryResponse)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auto-starting agent daemon failed: ${e.message}, falling back to inline PRoot execution")
            }
        }

        // Fallback: run agent inline via PRoot + launcher.sh
        currentAgentStatus = "Starting agent..."
        try {
            if (!launcherScript.exists() || !launcherScript.canExecute()) {
                currentAgentStatus = ""
                sendResponse(out, 500, "Internal Error", "{\"error\":\"launcher.sh not found. Please open a terminal session first.\"}")
                return
            }

            currentAgentStatus = "Agent is processing..."
            val pb = ProcessBuilder("sh", launcherScript.absolutePath,
                "python3", "/usr/local/bin/nethunter_agent.py", "run-direct", prompt)
            pb.directory(appContext?.filesDir)
            pb.redirectErrorStream(true)
            val process = pb.start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()

            // Parse output: launcher.sh prepends "[*] Starting session..." and "[*] Running custom launcher command..."
            // The actual agent response is everything after those lines
            val lines = output.lines()
            val agentOutput = lines.dropWhile { it.startsWith("[*]") || it.isBlank() }.joinToString("\n").trim()

            val responseJson = if (agentOutput.isNotEmpty()) {
                JSONObject().put("response", agentOutput).toString()
            } else {
                JSONObject().put("response", "Agent returned no response.").toString()
            }
            currentAgentStatus = ""
            sendResponse(out, 200, "OK", responseJson)
        } catch (e: Exception) {
            Log.e(TAG, "Inline PRoot agent execution failed: ${e.message}", e)
            currentAgentStatus = ""
            sendResponse(out, 500, "Internal Error", "{\"error\":\"Inline agent execution failed: ${e.message}\"}")
        }
    }

    /** Application context reference, set during start(). */
    private var appContext: Context? = null

    private fun getOrCreateAgentToken(context: Context): String {
        val prefs = context.getSharedPreferences("api_security", Context.MODE_PRIVATE)
        var token = prefs.getString("agent_auth_token", null)
        if (token == null) {
            token = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32)
            prefs.edit().putString("agent_auth_token", token).apply()
        }
        // Write token to guest-accessible path so agent daemon can read it
        // Use mode 0600 (owner only) for security
        try {
            val tokenFile = java.io.File(context.filesDir, "tmp/nethunter_agent_token")
            tokenFile.parentFile?.mkdirs()
            tokenFile.writeText(token)
            // Restrict to owner only (mode 0600) - not world-readable
            tokenFile.setReadable(true, true)   // owner read only
            tokenFile.setWritable(true, true)   // owner write only
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write agent token file: ${e.message}")
        }
        return token
    }

    private fun handleVoiceInput(context: Context, out: java.io.OutputStream) {
        val latch = java.util.concurrent.CountDownLatch(1)
        var resultText = ""
        var errorMsg: String? = null

        handler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    errorMsg = "Speech recognition not available"
                    latch.countDown()
                    return@post
                }

                val googleService = ComponentName.unflattenFromString(
                    "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
                )
                val recognizer = if (googleService != null) {
                    SpeechRecognizer.createSpeechRecognizer(context.applicationContext, googleService)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
                }

                val listener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("LocalApiServer", "SpeechRecognizer: onReadyForSpeech")
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                            else -> "Unknown error"
                        }
                        errorMsg = "Speech error: $msg ($error)"
                        Log.e("LocalApiServer", "Speech error: $errorMsg")
                        recognizer.destroy()
                        latch.countDown()
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            resultText = matches[0]
                            Log.i(TAG, "Voice EXECUTED recognized: \"${resultText.take(60)}\"")
                        } else {
                            errorMsg = "No speech detected"
                            Log.w(TAG, "Voice EXECUTED: no speech detected")
                        }
                        recognizer.destroy()
                        latch.countDown()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }

                recognizer.setRecognitionListener(listener)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    setPackage("com.google.android.tts")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                }
                recognizer.startListening(intent)
            } catch (e: Exception) {
                errorMsg = "Exception: ${e.message}"
                latch.countDown()
            }
        }

        try {
            // Wait up to 15 seconds for user speech
            val completed = latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                sendResponse(out, 504, "Gateway Timeout", "{\"error\":\"Speech recognition timed out\"}")
                return
            }
            if (errorMsg != null) {
                sendResponse(out, 500, "Internal Error", "{\"error\":\"$errorMsg\"}")
                return
            }
            val response = JSONObject().put("text", resultText).toString()
            sendResponse(out, 200, "OK", response)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleAppsUsage(context: Context, out: OutputStream) {
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            val granted = (mode == AppOpsManager.MODE_ALLOWED)

            if (!granted) {
                val json = JSONObject().apply {
                    put("error", "Usage access permission not granted")
                    put("needs_permission", "android.settings.USAGE_ACCESS_SETTINGS")
                }.toString()
                sendResponse(out, 200, "OK", json)
                return
            }

            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val time = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 24 * 60 * 60 * 1000, time)
            
            val array = org.json.JSONArray()
            if (stats != null) {
                for (usageStats in stats) {
                    val obj = JSONObject().apply {
                        put("packageName", usageStats.packageName)
                        put("totalTimeInForeground", usageStats.totalTimeInForeground)
                        put("lastTimeUsed", usageStats.lastTimeUsed)
                    }
                    array.put(obj)
                }
            }
            val response = JSONObject().put("apps", array).toString()
            sendResponse(out, 200, "OK", response)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleNotificationsActive(context: Context, out: OutputStream) {
        try {
            val sets = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            val enabled = sets != null && sets.contains(context.packageName)

            if (!enabled) {
                val json = JSONObject().apply {
                    put("error", "Notification Access permission not granted")
                    put("needs_permission", "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                }.toString()
                sendResponse(out, 200, "OK", json)
                return
            }

            val list = NetHunterNotificationListenerService.getActiveNotificationsList()
            val array = org.json.JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("package", item.packageName)
                    put("id", item.id)
                    put("title", item.title)
                    put("text", item.text)
                    put("post_time", item.postTime)
                }
                array.put(obj)
            }
            sendResponse(out, 200, "OK", JSONObject().put("notifications", array).toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleAccessibilityHierarchy(out: OutputStream) {
        try {
            val enabled = NetHunterAccessibilityService.isServiceRunning()
            if (!enabled) {
                val json = JSONObject().apply {
                    put("error", "Accessibility Service not enabled")
                    put("needs_permission", "android.settings.ACCESSIBILITY_SETTINGS")
                }.toString()
                sendResponse(out, 200, "OK", json)
                return
            }

            val hierarchy = NetHunterAccessibilityService.getScreenHierarchy()
            Log.i(TAG, "Accessibility hierarchy dump EXECUTED (${hierarchy.length}B)")
            sendResponse(out, 200, "OK", hierarchy)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleAccessibilityStatus(out: OutputStream) {
        val enabled = NetHunterAccessibilityService.isServiceRunning()
        Log.i(TAG, "Accessibility status EXECUTED enabled=$enabled")
        sendResponse(out, 200, "OK", JSONObject().put("enabled", enabled).toString())
    }

    private fun requireServiceOrError(out: OutputStream): Boolean {
        if (!NetHunterAccessibilityService.isServiceRunning()) {
            Log.w(TAG, "Accessibility service NOT running — action SKIPPED (jen HTTP)")
            sendResponse(out, 200, "OK", JSONObject().apply {
                put("error", "Accessibility Service not enabled")
                put("needs_permission", "android.settings.ACCESSIBILITY_SETTINGS")
            }.toString())
            return false
        }
        return true
    }

    private fun handleAccessibilityTap(body: String, out: OutputStream) {
        try {
            if (!requireServiceOrError(out)) return
            val j = JSONObject(body)
            if (!j.has("x") || !j.has("y")) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"x and y coordinates are required\"}")
                return
            }
            val ok = NetHunterAccessibilityService.tap(j.optInt("x", 0), j.optInt("y", 0))
            Log.i(TAG, "Accessibility tap(${j.optInt("x", 0)},${j.optInt("y", 0)}) EXECUTED ok=$ok")
            sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleAccessibilityClick(body: String, out: OutputStream) {
        try {
            if (!requireServiceOrError(out)) return
            val j = JSONObject(body)
            val text = if (j.has("text")) j.getString("text") else null
            val ok = if (text != null) {
                NetHunterAccessibilityService.clickByText(text!!)
            } else {
                if (!j.has("x") || !j.has("y")) {
                    sendResponse(out, 400, "Bad Request", "{\"error\":\"x and y coordinates are required when text is not provided\"}")
                    return
                }
                NetHunterAccessibilityService.tap(j.optInt("x", 0), j.optInt("y", 0))
            }
            Log.i(TAG, "Accessibility click EXECUTED ok=$ok")
            sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleAccessibilityLongClick(body: String, out: OutputStream) {
        try {
            if (!requireServiceOrError(out)) return
            val j = JSONObject(body)
            val text = if (j.has("text")) j.getString("text") else null
            val ok = if (text != null) {
                NetHunterAccessibilityService.longClickByText(text!!)
            } else {
                if (!j.has("x") || !j.has("y")) {
                    sendResponse(out, 400, "Bad Request", "{\"error\":\"x and y coordinates are required when text is not provided\"}")
                    return
                }
                NetHunterAccessibilityService.longTap(j.optInt("x", 0), j.optInt("y", 0))
            }
            Log.i(TAG, "Accessibility longclick EXECUTED ok=$ok")
            sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleAccessibilitySwipe(body: String, out: OutputStream) {
        try {
            if (!requireServiceOrError(out)) return
            val j = JSONObject(body)
            if (!j.has("x1") || !j.has("y1") || !j.has("x2") || !j.has("y2")) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"x1, y1, x2, y2 coordinates are required\"}")
                return
            }
            val ok = NetHunterAccessibilityService.swipe(
                j.optInt("x1", 0), j.optInt("y1", 0), j.optInt("x2", 0), j.optInt("y2", 0),
                j.optLong("duration_ms", 300L)
            )
            Log.i(TAG, "Accessibility swipe EXECUTED ok=$ok")
            sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleAccessibilityText(body: String, out: OutputStream) {
        try {
            if (!requireServiceOrError(out)) return
            val j = JSONObject(body)
            if (!j.has("text")) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"text field is required\"}")
                return
            }
            val text = j.getString("text")
            val targetText = if (j.has("target_text")) j.getString("target_text") else null
            val ok = NetHunterAccessibilityService.setText(text, targetText)
            Log.i(TAG, "Accessibility text-input EXECUTED ok=$ok")
            sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleAccessibilityScroll(body: String, out: OutputStream) {
        try {
            if (!requireServiceOrError(out)) return
            val j = JSONObject(body)
            val forward = j.optString("direction", "forward") == "forward"
            val targetText = if (j.has("text")) j.getString("text") else null
            val ok = NetHunterAccessibilityService.scroll(forward, targetText)
            Log.i(TAG, "Accessibility scroll EXECUTED ok=$ok")
            sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleAccessibilityGlobal(body: String, out: OutputStream) {
        try {
            if (!requireServiceOrError(out)) return
            val j = JSONObject(body)
            if (!j.has("action")) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"action field is required (back|home|recents|notifications|quick_settings|lock_screen|screenshot)\"}")
                return
            }
            val ok = NetHunterAccessibilityService.globalAction(j.optString("action", ""))
            Log.i(TAG, "Accessibility global(${j.optString("action", "")}) EXECUTED ok=$ok")
            sendResponse(out, 200, "OK", JSONObject().put("success", ok).toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleBatteryOptimizeGet(context: Context, out: OutputStream) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
            Log.i(TAG, "Battery-optimize READ EXECUTED ignored=$isIgnoring")
            sendResponse(out, 200, "OK", "{\"ignored\":$isIgnoring}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleBatteryOptimizePost(context: Context, out: OutputStream) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    try {
                        Log.i(TAG, "Battery-optimize EXECUTED: launching exemption request activity")
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        sendResponse(out, 200, "OK", "{\"status\":\"requested\"}")
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        sendResponse(out, 200, "OK", "{\"status\":\"opened_settings\"}")
                    }
                } else {
                    sendResponse(out, 200, "OK", "{\"status\":\"already_ignored\"}")
                }
            } else {
                sendResponse(out, 200, "OK", "{\"status\":\"not_supported\"}")
            }
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleWifiControl(context: Context, body: String, out: OutputStream) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val cmd = body.trim()
            when {
                cmd.equals("on", ignoreCase = true) || cmd.equals("true", ignoreCase = true) -> {
                    @Suppress("DEPRECATION")
                    val success = wifiManager.setWifiEnabled(true)
                    sendResponse(out, 200, "OK", "{\"enabled\":true,\"success\":$success}")
                }
                cmd.equals("off", ignoreCase = true) || cmd.equals("false", ignoreCase = true) -> {
                    @Suppress("DEPRECATION")
                    val success = wifiManager.setWifiEnabled(false)
                    sendResponse(out, 200, "OK", "{\"enabled\":false,\"success\":$success}")
                }
                cmd.equals("status", ignoreCase = true) -> {
                    @Suppress("DEPRECATION")
                    val info = wifiManager.connectionInfo
                    val json = JSONObject().apply {
                        put("wifi_enabled", wifiManager.isWifiEnabled)
                        if (info != null) {
                            put("ssid", info.ssid?.replace("\"", ""))
                            put("bssid", info.bssid)
                            put("rssi", info.rssi)
                            put("link_speed_mbps", info.linkSpeed)
                        }
                    }.toString()
                    sendResponse(out, 200, "OK", json)
                }
                cmd.equals("scan", ignoreCase = true) -> {
                    @Suppress("DEPRECATION")
                    val scanSuccess = wifiManager.startScan()
                    // Android needs time + Location ON + Location runtime permission
                    try {
                        // Poll up to ~3s for results
                        repeat(6) {
                            Thread.sleep(500)
                            if (wifiManager.scanResults.isNotEmpty()) return@repeat
                        }
                    } catch (e: Exception) {}
                    val results = wifiManager.scanResults
                    val empty = results.isEmpty()
                    val json = JSONObject().apply {
                        put("scan_complete", scanSuccess)
                        put("empty", empty)
                        val arr = org.json.JSONArray()
                        val seen = mutableSetOf<String>()
                        for (r in results) {
                            @Suppress("DEPRECATION")
                            val key = r.SSID + r.BSSID
                            if (key in seen) continue
                            seen.add(key)
                            @Suppress("DEPRECATION")
                            if (r.SSID.isNullOrEmpty()) continue
                            arr.put(JSONObject().apply {
                                @Suppress("DEPRECATION")
                                put("ssid", r.SSID)
                                put("bssid", r.BSSID)
                                put("level", r.level)
                                put("capabilities", r.capabilities)
                                put("frequency", r.frequency)
                            })
                        }
                        put("networks", arr)
                    }.toString()
                    sendResponse(out, 200, "OK", json)
                }
                cmd.startsWith("connect:", ignoreCase = true) -> {
                    val parts = cmd.removePrefix("connect:").removePrefix("CONNECT:").trim().split(":", limit=2)
                    val ssid = parts[0].trim()
                    val password = if (parts.size > 1) parts[1].trim() else ""
                    if (ssid.isEmpty()) {
                        sendResponse(out, 400, "Bad Request", "{\"error\":\"SSID cannot be empty\"}")
                        return
                    }
                    @Suppress("DEPRECATION")
                    val conf = android.net.wifi.WifiConfiguration().apply {
                        SSID = "\"$ssid\""
                        if (password.isNotEmpty()) {
                            allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                            preSharedKey = "\"$password\""
                        } else {
                            allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                        }
                    }
                    @Suppress("DEPRECATION")
                    val netId = wifiManager.addNetwork(conf)
                    if (netId == -1) {
                        sendResponse(out, 500, "Internal Error", "{\"error\":\"Failed to add network\"}")
                        return
                    }
                    @Suppress("DEPRECATION")
                    wifiManager.disconnect()
                    @Suppress("DEPRECATION")
                    wifiManager.enableNetwork(netId, true)
                    @Suppress("DEPRECATION")
                    wifiManager.reconnect()
                    sendResponse(out, 200, "OK", "{\"connected\":true,\"ssid\":\"$ssid\"}")
                }
                else -> {
                    // Legacy compat: treat as enable/disable boolean
                    val enable = cmd.toBooleanStrictOrNull() ?: false
                    @Suppress("DEPRECATION")
                    val success = wifiManager.setWifiEnabled(enable)
                    sendResponse(out, 200, "OK", "{\"enabled\":$enable,\"success\":$success}")
                }
            }
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleDeviceAdminStatus(context: Context, out: OutputStream) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, NetHunterDeviceAdminReceiver::class.java)
            val active = dpm.isAdminActive(adminComponent)
            Log.i(TAG, "DeviceAdmin status EXECUTED active=$active")
            sendResponse(out, 200, "OK", "{\"active\":$active}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleDeviceAdminRequest(context: Context, out: OutputStream) {
        try {
            val adminComponent = ComponentName(context, NetHunterDeviceAdminReceiver::class.java)
            Log.i(TAG, "DeviceAdmin request EXECUTED: launching ADD_DEVICE_ADMIN activity")
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Requesting Device Admin privileges for NetHunter Operator.")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            sendResponse(out, 200, "OK", "{\"status\":\"requested\"}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleDeviceLock(context: Context, out: OutputStream) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, NetHunterDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                Log.i(TAG, "Device LOCK EXECUTED (lockNow triggered)")
                sendResponse(out, 200, "OK", "{\"status\":\"locked\"}")
            } else {
                sendResponse(out, 200, "OK", "{\"error\":\"Device admin not active\",\"needs_activation\":\"/device/admin\"}")
            }
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    // ─── nh distro endpointy (proot-distro-like wrapper) ───────────────────

    private fun handleDistroList(context: Context, out: OutputStream) {
        try {
            val arr = JSONArray()
            for (d in RootfsManager.DISTROS) {
                val installed = RootfsManager.isRootfsExtracted(context, d)
                val obj = JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("rootfs_dir", d.rootfsDirName)
                    put("installed", installed)
                }
                arr.put(obj)
            }
            sendResponse(out, 200, "OK", JSONObject().apply {
                put("distros", arr)
                put("sessions", TerminalService.getActiveSessionCount())
            }.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleDistroPs(context: Context, out: OutputStream) {
        try {
            val arr = JSONArray()
            for (session in TerminalService.sessions) {
                val id = TerminalService.getSessionId(session) ?: "unknown"
                val obj = JSONObject().apply {
                    put("session_id", id)
                    put("distro", TerminalService.getSessionDistro(session))
                    put("name", TerminalService.getSessionName(session) ?: "")
                    put("vpn_ignored", TerminalService.isSessionVpnIgnoredById(id))
                }
                arr.put(obj)
            }
            sendResponse(out, 200, "OK", JSONObject().apply {
                put("sessions", arr)
            }.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleDistroKill(context: Context, body: String, out: OutputStream) {
        try {
            val json = if (body.trim().isNotEmpty()) JSONObject(body) else JSONObject()
            val sessionId = json.optString("session_id", "")
            val force = json.optBoolean("force", false)
            if (sessionId.isEmpty()) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"session_id parameter is required\"}")
                return
            }
            if (!force) {
                sendResponse(out, 409, "Confirmation Required",
                    "{\"error\":\"confirmation_required\",\"hint\":\"pass force=true\"}")
                return
            }
            val session = TerminalService.idToSession[sessionId]
            if (session == null) {
                sendResponse(out, 404, "Not Found", "{\"error\":\"Session not found: $sessionId\"}")
                return
            }
            TerminalService.removeSession(session)
            sendResponse(out, 200, "OK", "{\"status\":\"killed\",\"session\":\"$sessionId\"}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleDistroRemove(context: Context, body: String, out: OutputStream) {
        try {
            val json = if (body.trim().isNotEmpty()) JSONObject(body) else JSONObject()
            val distroId = json.optString("id", "")
            val force = json.optBoolean("force", false)
            val distro = RootfsManager.DISTROS.find { it.id == distroId }
            if (distro == null) {
                sendResponse(out, 404, "Not Found", "{\"error\":\"Unknown distro: $distroId (kali|parrot)\"}")
                return
            }
            if (!force) {
                sendResponse(out, 409, "Confirmation Required",
                    "{\"error\":\"confirmation_required\",\"hint\":\"pass force=true\"}")
                return
            }
            val deleted = RootfsManager.deleteRootfs(context, distro)
            sendResponse(out, 200, "OK", "{\"status\":\"removed\",\"distro\":\"$distroId\",\"deleted\":$deleted}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleRootfsBackup(context: Context, out: OutputStream) {
        try {
            val distro = RootfsManager.DISTROS.find { it.id == "kali" } ?: RootfsManager.DISTROS.first()
            var finalPath = ""
            kotlinx.coroutines.runBlocking {
                RootfsManager.backupRootfs(context, distro).collect { (progress, status) ->
                    if (progress == 100) finalPath = status
                }
            }
            sendResponse(out, 200, "OK", JSONObject().apply {
                put("status", "success")
                put("path", finalPath)
            }.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleRootfsRestore(context: Context, body: String, out: OutputStream) {
        try {
            val json = if (body.trim().isNotEmpty()) JSONObject(body) else JSONObject()
            val fileName = json.optString("file", "")
            if (fileName.isEmpty()) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"file parameter is required\"}")
                return
            }
            
            val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val backupFile = java.io.File(downloads, fileName)
            if (!backupFile.exists()) {
                sendResponse(out, 404, "Not Found", "{\"error\":\"Backup file not found in Downloads: $fileName\"}")
                return
            }

            val distro = RootfsManager.DISTROS.find { it.id == "kali" } ?: RootfsManager.DISTROS.first()
            kotlinx.coroutines.runBlocking {
                RootfsManager.restoreRootfs(context, backupFile, distro).collect { }
            }
            sendResponse(out, 200, "OK", "{\"status\":\"restored\",\"path\":\"${backupFile.absolutePath}\"}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleApiShareGet(context: Context, out: OutputStream) {
        val sharedPrefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        val shareLocalApi = sharedPrefs.getBoolean("share_local_api", false)
        sendResponse(out, 200, "OK", "{\"shared\":$shareLocalApi}")
    }

    private fun handleApiSharePost(context: Context, body: String, out: OutputStream) {
        val sharedPrefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        val share = body.trim().equals("on", ignoreCase = true) || body.trim().equals("true", ignoreCase = true)
        sharedPrefs.edit().putBoolean("share_local_api", share).apply()
        sendResponse(out, 200, "OK", "{\"shared\":$share}")
        // Restart the server on a separate thread to apply bind address changes
        executor.execute {
            restart(context)
        }
    }

    private fun handleMap(context: Context, out: OutputStream) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var location: Location? = null
            try {
                location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (_: SecurityException) {}

            val json = JSONObject().apply {
                if (location != null) {
                    put("success", true)
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy.toDouble())
                    put("provider", location.provider)
                    put("time", location.time)
                    put("message", "Open terminalmap with: terminalmap")
                    put("hint", "To focus on this location, set initial_lat/lon and initial_zoom in the Rust code")
                } else {
                    put("success", false)
                    put("error", "No last known location available. Check permissions and GPS.")
                    put("hint", "Run: nethunter-location to get current GPS; then open terminalmap")
                }
            }.toString()
            sendResponse(out, 200, "OK", json)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnMitmPost(context: Context, body: String, out: OutputStream) {
        try {
            val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
            val value = body.trim().lowercase()
            val enabled = when (value) {
                "on", "true", "1" -> true
                "off", "false", "0" -> false
                else -> {
                    sendResponse(out, 400, "Bad Request", "{\"error\":\"Use 'on' or 'off'\"}")
                    return
                }
            }
            prefs.edit().putBoolean("enable_mitm", enabled).apply()

            val action = if (enabled) "on" else "off"
            sendResponse(out, 200, "OK", JSONObject().apply {
                put("mitm", action)
                put("status", "mitm_$action")
            }.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnMitmGet(context: Context, out: OutputStream) {
        try {
            val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("enable_mitm", com.linux_core.BuildConfig.ENABLE_MITM)
            val sessions = com.linux_core.core.TlsMitmEngine.getSessionSnapshots()
            val json = JSONObject().apply {
                put("mitm", if (enabled) "on" else "off")
                put("active_sessions", sessions.size)
                put("sessions", org.json.JSONArray(sessions.map { (port, snippet) ->
                    JSONObject().apply {
                        put("port", port)
                        put("snippet", snippet)
                    }
                }))
            }.toString()
            sendResponse(out, 200, "OK", json)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnMitmLogs(context: Context, path: String, out: OutputStream) {
        try {
            val params = parseQueryParams(path)
            val fmt = params["format"] ?: "pretty"
            val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50
            val since = params["since"]?.toLongOrNull() ?: 0L
            val host = params["host"]?.takeIf { it.isNotBlank() }
            val grep = params["grep"]?.takeIf { it.isNotBlank() }
            val store = MitmTrafficStore.get(context)
            val records = store.query(limit = limit, since = since, host = host, grep = grep)

            if (fmt == "json") {
                val payload = JSONObject().apply {
                    put("records", store.toJsonArray(records))
                    put("count", records.size)
                    if (records.isNotEmpty()) {
                        put("latest_ts", records.maxOf { it.timestamp })
                        put("latest_id", records.maxOf { it.id })
                    }
                }
                sendResponse(out, 200, "OK", payload.toString())
                return
            }

            if (fmt == "legacy") {
                val sessions = com.linux_core.core.TlsMitmEngine.getSessionSnapshots()
                val sb = StringBuilder()
                for ((port, snippet) in sessions) {
                    sb.append("=== Port $port ===\n")
                    sb.append(snippet)
                    sb.append("\n")
                }
                val raw = sb.toString().toByteArray(Charsets.UTF_8)
                val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${raw.size}\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(headers.toByteArray(Charsets.UTF_8))
                out.write(raw)
                out.flush()
                return
            }

            val pretty = store.toPrettyText(records)
            val raw = pretty.toByteArray(Charsets.UTF_8)
            val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "X-Mitm-Count: ${records.size}\r\n" +
                if (records.isNotEmpty()) "X-Mitm-Latest-Ts: ${records.maxOf { it.timestamp }}\r\n" else "" +
                "Content-Length: ${raw.size}\r\n" +
                "Connection: close\r\n\r\n"
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.write(raw)
            out.flush()
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleAppLogs(context: Context, path: String, out: OutputStream) {
        try {
            val params = parseQueryParams(path)
            val limit = params["limit"]?.toIntOrNull() ?: 500
            val keyboardOnly = params["keyboard_only"]?.toIntOrNull() == 1
            val pid = android.os.Process.myPid().toString()
            val prefs = context.getSharedPreferences("log_settings", Context.MODE_PRIVATE)
            val level = prefs.getInt("log_level", 3)
            // Map level 1-5 to exact logcat priority: 1=E, 2=W, 3=I, 4=D, 5=V
            val priority = when (level) {
                1 -> 'E'
                2 -> 'W'
                3 -> 'I'
                4 -> 'D'
                5 -> 'V'
                else -> 'I'
            }
            // Build logcat command with PID + exact priority filter (E:E, W:W, etc.)
            val logcatCmd = arrayListOf(
                "logcat", "-d", "-v", "time", "-t", limit.toString(),
                "--pid", pid,
                "$priority:$priority"  // exact priority
            )
            val process = Runtime.getRuntime().exec(logcatCmd.toTypedArray())
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            reader.close()
            process.destroy()
            // If limit reached with no results, fall back to unfiltered logcat
            if (sb.isEmpty()) {
                val fallback = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "200"))
                fallback.inputStream.bufferedReader().use { r ->
                    r.forEachLine { sb.append(it).append("\n") }
                }
                fallback.destroy()
            }
            // Client-side keyboard tag filtering (logcat -v time format: tag after priority)
            val keyboardTags = setOf("inputreader", "inputdispatcher", "inputmanager", "keyboard", "touchinjector")
            val filtered = if (keyboardOnly) {
                sb.lines().filter { line ->
                    !keyboardTags.any { tag -> line.lowercase().contains(tag) }
                }.joinToString("\n")
            } else {
                sb.lines().joinToString("\n")
            }
            val raw = filtered.toByteArray(Charsets.UTF_8)
            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${raw.size}\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.write(raw)
            out.flush()
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleAppLogsLevel(context: Context, out: OutputStream) {
        try {
            val prefs = context.getSharedPreferences("log_settings", Context.MODE_PRIVATE)
            val level = prefs.getInt("log_level", 3)
            sendResponse(out, 200, "OK", "{\"level\":$level}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleAppLogsLevelSet(context: Context, body: String, out: OutputStream) {
        try {
            val level = body.trim().toIntOrNull()?.coerceIn(1, 5) ?: 3
            context.getSharedPreferences("log_settings", Context.MODE_PRIVATE)
                .edit().putInt("log_level", level).apply()
            Log.i(TAG, "Log level set to $level")
            sendResponse(out, 200, "OK", "{\"level\":$level}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnMitmSniFallbackGet(context: Context, out: OutputStream) {
        try {
            val fallback = VpnSettings.getMitmSniFallback(context)
            sendResponse(out, 200, "OK", JSONObject().apply {
                put("fallback", fallback ?: JSONObject.NULL)
            }.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnMitmSniFallbackPost(context: Context, body: String, out: OutputStream) {
        try {
            val trimmed = body.trim().removeSurrounding("\"").removeSurrounding("'")
            if (trimmed.length > 253) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"Hostname exceeds 253 characters\"}")
                return
            }
            if (trimmed.isNotEmpty()) {
                val hostnamePattern = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)*$")
                if (!hostnamePattern.matcher(trimmed).matches()) {
                    sendResponse(out, 400, "Bad Request", "{\"error\":\"Invalid hostname format\"}")
                    return
                }
            }
            VpnSettings.setMitmSniFallback(context, trimmed.ifEmpty { null })
            val effective = VpnSettings.getMitmSniFallback(context)
            val status = if (trimmed.isEmpty()) "cleared" else "set"
            sendResponse(out, 200, "OK", JSONObject().apply {
                put("fallback", effective ?: JSONObject.NULL)
                put("status", status)
            }.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    // ── VPN AI Brain endpointy (Krok 7) ────────────────────────────

    private fun handleVpnAiPending(out: OutputStream) {
        try {
            val aggregator = TrafficAggregator.getInstance()
            if (aggregator == null) {
                sendResponse(out, 503, "Service Unavailable", "{\"error\":\"TrafficAggregator not initialized\"}")
                return
            }
            val pending = aggregator.getPendingFlows()
            val array = org.json.JSONArray()
            for (flow in pending) {
                array.put(org.json.JSONObject().apply {
                    put("a", flow.address)
                    put("n", flow.occurrenceCount)
                    put("detected", flow.detectedAt)
                    put("expires", flow.expiresAt)
                    put("b_conf", flow.brainConfidence)
                    put("reason", flow.reason)
                    put("sni", flow.sni ?: org.json.JSONObject.NULL)
                    put("escalated", flow.escalatedToLlm)
                })
            }
            sendResponse(out, 200, "OK", array.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnAiHistory(path: String, out: OutputStream) {
        try {
            val params = parseQueryParams(path)
            val address = params["address"] ?: run {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"address parameter required\"}")
                return
            }
            val aggregator = TrafficAggregator.getInstance()
            if (aggregator == null) {
                sendResponse(out, 503, "Service Unavailable", "{\"error\":\"TrafficAggregator not initialized\"}")
                return
            }
            val history = aggregator.getAddressHistory(address)
            if (history == null) {
                sendResponse(out, 404, "Not Found", "{\"error\":\"Address not found\"}")
                return
            }
            sendResponse(out, 200, "OK", org.json.JSONObject().apply {
                put("a", history.address)
                put("first_seen", history.firstSeen)
                put("last_seen", history.lastSeen)
                put("count", history.occurrenceCount)
                put("avg_entropy", history.avgEntropy)
                put("avg_interval", history.avgIntervalSec)
                put("port", history.typicalPort)
                put("verdict", history.verdict)
                put("source", history.verdictSource)
                put("confidence", history.verdictConfidence)
                put("baseline_entropy", history.baselineEntropy)
            }.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnAiVerdict(context: Context, body: String, out: OutputStream) {
        try {
            val json = org.json.JSONObject(body)
            val address = json.optString("a", "").takeIf { it.isNotBlank() } ?: run {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"'a' (address) required\"}")
                return
            }
            val verdict = json.optString("v", "blocked")
            val confidence = json.optDouble("conf", 0.5)
            val note = json.optString("note", "")

            val aggregator = TrafficAggregator.getInstance()
            if (aggregator == null) {
                sendResponse(out, 503, "Service Unavailable", "{\"error\":\"TrafficAggregator not initialized\"}")
                return
            }
            aggregator.setVerdict(
                address = address,
                verdict = verdict,
                source = "api",
                confidence = confidence,
                note = note
            )
            sendResponse(out, 200, "OK", "{\"status\":\"verdict_set\",\"a\":\"$address\",\"v\":\"$verdict\"}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnAiNotify(context: Context, body: String, out: OutputStream) {
        try {
            val json = org.json.JSONObject(body)
            val address = json.optString("address", "").takeIf { it.isNotBlank() } ?: run {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"'address' required\"}")
                return
            }
            val question = json.optString("question", "Allow connection to $address?")
            val confidence = json.optDouble("confidence", 0.5)

            VerdictNotifier.notify(
                address = address,
                question = question,
                confidence = confidence,
                context = context
            )
            sendResponse(out, 200, "OK", "{\"status\":\"notified\",\"a\":\"$address\"}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnAiSummary(out: OutputStream) {
        try {
            val aggregator = TrafficAggregator.getInstance()
            if (aggregator == null) {
                sendResponse(out, 503, "Service Unavailable", "{\"error\":\"TrafficAggregator not initialized\"}")
                return
            }
            val stat = aggregator.getDailyStat()
            if (stat == null) {
                sendResponse(out, 200, "OK", "{\"total_flows\":0,\"blocked\":0,\"allowed\":0,\"pending\":0}")
                return
            }
            sendResponse(out, 200, "OK", org.json.JSONObject().apply {
                put("date", stat.date)
                put("total_flows", stat.totalFlows)
                put("new_addresses", stat.newAddresses)
                put("blocked", stat.blockedCount)
                put("allowed", stat.allowedCount)
                put("pending", stat.pendingCount)
                put("top_entropy", stat.topEntropyAddress ?: org.json.JSONObject.NULL)
            }.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnMitmSelective(body: String, out: OutputStream) {
        try {
            val json = org.json.JSONObject(body)
            val address = json.optString("address", "").takeIf { it.isNotBlank() } ?: run {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"'address' required\"}")
                return
            }
            val durationSec = json.optInt("duration_sec", 30).coerceIn(1, 60)

            TlsMitmEngine.startCaptureOnlyForAddress(address, durationSec * 1000L)
            sendResponse(out, 200, "OK", "{\"status\":\"selective_mitm\",\"a\":\"$address\",\"duration\":$durationSec}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnMitmCa(context: Context, out: OutputStream) {
        try {
            val caBytes = com.linux_core.security.RootCaInstaller(context).caBytes()
            if (caBytes != null) {
                val headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/x-x509-ca-cert\r\n" +
                        "Content-Length: ${caBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(headers.toByteArray(Charsets.UTF_8))
                out.write(caBytes)
                out.flush()
            } else {
                sendResponse(out, 404, "Not Found", "{\"error\":\"Root CA certificate not found in assets\"}")
            }
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    // ============================================================
    //  code-server (VS Code in browser) — editor control endpoints
    // ============================================================
    //
    // These endpoints run the code-server-ctl shell script inside the PRoot
    // guest (the same pattern used for nethunter-desktop start/stop).
    //
    // Security:
    //   - All endpoints require a Bearer token when accessed remotely
    //     (added to the sensitive-endpoint list at the connection handler).
    //   - The /editor/password endpoint is additionally localhost-restricted
    //     so a shared-API listener never leaks the password over the LAN.
    //   - No free-form shell input is accepted. Only fixed subcommands.
    //
    // Storage:
    //   - code-server state lives in /root/.config/code-server/config.yaml
    //     (auto-deployed by code-server-ctl with chmod 600).
    //   - Editor status cache is held in SharedPreferences ("editor_settings").

    private fun editorPrefs(context: Context) =
        context.getSharedPreferences("editor_settings", Context.MODE_PRIVATE)

    private fun runCodeServerCtl(context: Context, vararg args: String): String {
        val launcherFile = java.io.File(context.filesDir, "launcher.sh")
        if (!launcherFile.exists() || !launcherFile.canExecute()) {
            return "{\"error\":\"launcher.sh not found or not executable. Open a terminal session first to bootstrap the rootfs.\"}"
        }
        return try {
            val pb = ProcessBuilder("sh", launcherFile.absolutePath, "code-server-ctl", *args)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return "{\"error\":\"code-server-ctl timed out after 15s\"}"
            }
            val exitCode = proc.exitValue()
            if (exitCode != 0) {
                return JSONObject().apply {
                    put("error", "code-server-ctl exited with code $exitCode")
                    put("exit_code", exitCode)
                    put("output", output.take(500))
                }.toString()
            }
            output
        } catch (e: Exception) {
            Log.e(TAG, "runCodeServerCtl failed: ${e.message}", e)
            "{\"error\":\"${e.message}\"}"
        }
    }

    private fun handleEditorStart(context: Context, out: OutputStream) {
        try {
            val raw = runCodeServerCtl(context, "start")
            val scriptJson = parseScriptJsonOrNull(raw)
            val payload = if (scriptJson != null && !scriptJson.has("error")) {
                scriptJson
            } else {
                val errorMsg = scriptJson?.optString("error")
                    ?: raw.take(200)
                JSONObject().apply { put("error", errorMsg) }
            }
            val code = if (payload.has("error")) 500 else 200
            editorPrefs(context).edit()
                .putLong("last_start_ts", System.currentTimeMillis())
                .apply()
            sendResponse(out, code, if (code == 200) "OK" else "Start Failed", payload.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleEditorStop(context: Context, out: OutputStream) {
        try {
            val raw = runCodeServerCtl(context, "stop")
            val scriptJson = parseScriptJsonOrNull(raw)
            val payload = scriptJson ?: JSONObject().apply {
                put("status", "stopped"); put("raw", raw)
            }
            val code = if (payload.has("error")) 500 else 200
            sendResponse(out, code, if (code == 200) "OK" else "Stop Failed", payload.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleEditorStatus(context: Context, out: OutputStream) {
        try {
            val raw = runCodeServerCtl(context, "status")
            val scriptJson = parseScriptJsonOrNull(raw)
            val payload = if (scriptJson != null) {
                // Add last_start_ts from prefs for UI diagnostics
                try {
                    val lastStart = editorPrefs(context).getLong("last_start_ts", 0L)
                    if (lastStart > 0L) scriptJson.put("last_start_ts", lastStart) else scriptJson
                } catch (e: Exception) { scriptJson }
                scriptJson
            } else {
                JSONObject().apply { put("status", "unknown"); put("raw", raw) }
            }
            sendResponse(out, 200, "OK", payload.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleEditorPassword(context: Context, out: OutputStream, isLocalConnection: Boolean = true) {
        try {
            // localhost-only enforcement: password must never leak over network
            // even if the caller presents a valid Bearer token.
            if (!isLocalConnection) {
                sendResponse(out, 403, "Forbidden",
                    "{\"error\":\"Password endpoint is restricted to localhost\"}")
                return
            }

            val raw = runCodeServerCtl(context, "password")
            val scriptJson = parseScriptJsonOrNull(raw)
            val payload = if (scriptJson != null && scriptJson.has("password")) {
                scriptJson
            } else {
                JSONObject().apply {
                    put("error", "password not available")
                    put("raw", raw)
                }
            }
            sendResponse(out, 200, "OK", payload.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleEditorInfo(context: Context, out: OutputStream) {
        try {
            val raw = runCodeServerCtl(context, "info")
            val scriptJson = parseScriptJsonOrNull(raw)
            val payload = scriptJson ?: JSONObject().apply {
                put("error", "info not available"); put("raw", raw)
            }
            sendResponse(out, 200, "OK", payload.toString())
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleEditorInstall(context: Context, out: OutputStream) {
        try {
            val raw = runCodeServerCtl(context, "install")
            val obj = parseScriptJsonOrNull(raw)
            // If install succeeds, the script may print success messages but not JSON.
            // Parse the raw output for error keywords.
            if (obj != null && obj.has("error")) {
                sendResponse(out, 500, "Internal Error", obj.toString())
            } else if (raw.lowercase().contains("error") || raw.lowercase().contains("fail")) {
                sendResponse(out, 500, "Internal Error", JSONObject().apply {
                    put("error", "Installation failed")
                    put("output", raw.take(500))
                }.toString())
            } else {
                sendResponse(out, 200, "OK", JSONObject().apply {
                    put("status", "installed")
                    put("output", raw.take(500))
                }.toString())
            }
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun parseScriptJsonOrNull(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        return try {
            val trimmed = raw.trim().lines().lastOrNull { it.trim().startsWith("{") } ?: raw.trim()
            JSONObject(trimmed)
        } catch (e: Exception) {
            Log.w(TAG, "parseScriptJsonOrNull: not JSON: ${raw.take(200)}")
            null
        }
    }

    private fun parseScriptJsonOrWrap(raw: String, fallbackStatus: String): String {
        val obj = parseScriptJsonOrNull(raw)
        if (obj != null) return obj.toString()
        return JSONObject().apply {
            put("status", fallbackStatus)
            put("raw", raw)
        }.toString()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  USB Host Mode — enumeration, permission, claim, bulk/control
    // ═══════════════════════════════════════════════════════════════════
    //
    // These endpoints expose Android's UsbHostManager via the same
    // localhost API bridge so the PRoot guest can enumerate, connect to,
    // and exchange data with any USB device attached in Host (OTG) mode.
    //
    // Usage from PRoot:
    //   curl -s http://127.0.0.1:1337/usb/devices
    //   curl -s -X POST -d "device_name" http://127.0.0.1:1337/usb/permission
    //   curl -s -X POST -d '{"device_name":"...","interface_id":0}' http://127.0.0.1:1337/usb/claim
    //   curl -s -X POST -d '{"device_name":"...","endpoint_address":1,"data_base64":"..."}' http://127.0.0.1:1337/usb/bulk_transfer
    //
    // All endpoints return JSON. Errors have "success":false + "error":string.

    private fun handleUsbDevices(context: Context, out: OutputStream) {
        try {
            val result = UsbHostManager.listDevices()
            sendResponse(out, 200, "OK", result)
        } catch (e: Exception) {
            Log.e(TAG, "USB listDevices error: ${e.message}", e)
            sendResponse(out, 500, "Internal Error",
                JSONObject().apply { put("error", e.message) }.toString())
        }
    }

    private fun handleUsbPermission(context: Context, body: String, out: OutputStream) {
        try {
            val deviceName = body.trim().removeSurrounding("\"").removeSurrounding("'")
            if (deviceName.isEmpty()) {
                sendResponse(out, 400, "Bad Request",
                    JSONObject().apply { put("error", "device_name (raw body) required") }.toString())
                return
            }
            val result = UsbHostManager.requestPermission(deviceName)
            sendResponse(out, 200, "OK", result.toString())
        } catch (e: Exception) {
            Log.e(TAG, "USB permission error: ${e.message}", e)
            sendResponse(out, 500, "Internal Error",
                JSONObject().apply { put("error", e.message) }.toString())
        }
    }

    private fun handleUsbClaim(context: Context, body: String, out: OutputStream) {
        try {
            val j = if (body.trim().startsWith("{")) JSONObject(body) else JSONObject()
            val deviceName = j.optString("device_name", "").ifEmpty {
                body.trim().removeSurrounding("\"").removeSurrounding("'")
            }
            if (deviceName.isEmpty()) {
                sendResponse(out, 400, "Bad Request",
                    JSONObject().apply { put("error", "device_name required") }.toString())
                return
            }
            val interfaceId = j.optInt("interface_id", 0)
            val forceClaim = j.optBoolean("force", false)
            val result = UsbHostManager.claimInterface(deviceName, interfaceId, forceClaim)
            sendResponse(out, 200, "OK", result.toString())
        } catch (e: Exception) {
            Log.e(TAG, "USB claim error: ${e.message}", e)
            sendResponse(out, 500, "Internal Error",
                JSONObject().apply { put("error", e.message) }.toString())
        }
    }

    private fun handleUsbRelease(body: String, out: OutputStream) {
        try {
            val j = if (body.trim().startsWith("{")) JSONObject(body) else JSONObject()
            val deviceName = j.optString("device_name", "").ifEmpty {
                body.trim().removeSurrounding("\"").removeSurrounding("'")
            }
            if (deviceName.isEmpty()) {
                sendResponse(out, 400, "Bad Request",
                    JSONObject().apply { put("error", "device_name required") }.toString())
                return
            }
            val interfaceId = if (j.has("interface_id")) j.optInt("interface_id", -1) else null
            val result = UsbHostManager.releaseInterface(deviceName, interfaceId)
            sendResponse(out, 200, "OK", result.toString())
        } catch (e: Exception) {
            Log.e(TAG, "USB release error: ${e.message}", e)
            sendResponse(out, 500, "Internal Error",
                JSONObject().apply { put("error", e.message) }.toString())
        }
    }

    private fun handleUsbBulkTransfer(body: String, out: OutputStream) {
        try {
            val j = JSONObject(body)
            val deviceName = j.optString("device_name", "")
            val endpointAddress = j.optInt("endpoint", -1)
            if (deviceName.isEmpty() || endpointAddress < 0) {
                sendResponse(out, 400, "Bad Request",
                    JSONObject().apply { put("error", "device_name and endpoint are required") }.toString())
                return
            }
            val dataBase64 = j.optString("data_base64", "")
            val timeout = j.optInt("timeout", 1000)
            val direction = if (j.has("direction")) j.getString("direction") else null
            val result = UsbHostManager.bulkTransfer(deviceName, endpointAddress, dataBase64, timeout, direction)
            sendResponse(out, 200, "OK", result)
        } catch (e: Exception) {
            Log.e(TAG, "USB bulkTransfer error: ${e.message}", e)
            sendResponse(out, 500, "Internal Error",
                JSONObject().apply { put("error", e.message) }.toString())
        }
    }

    private fun handleUsbControlTransfer(body: String, out: OutputStream) {
        try {
            val j = JSONObject(body)
            val deviceName = j.optString("device_name", "")
            if (deviceName.isEmpty()) {
                sendResponse(out, 400, "Bad Request",
                    JSONObject().apply { put("error", "device_name required") }.toString())
                return
            }
            val requestType = j.optInt("request_type", 0x40)
            val request = j.optInt("request", 0)
            val value = j.optInt("value", 0)
            val index = j.optInt("index", 0)
            val dataBase64 = j.optString("data_base64", "")
            val timeout = j.optInt("timeout", 1000)
            val result = UsbHostManager.controlTransfer(deviceName, requestType, request, value, index, dataBase64, timeout)
            sendResponse(out, 200, "OK", result)
        } catch (e: Exception) {
            Log.e(TAG, "USB controlTransfer error: ${e.message}", e)
            sendResponse(out, 500, "Internal Error",
                JSONObject().apply { put("error", e.message) }.toString())
        }
    }

    private fun handleUsbSendRaw(body: String, out: OutputStream) {
        try {
            val j = JSONObject(body)
            val deviceName = j.optString("device_name", "")
            val dataBase64 = j.optString("data_base64", "")
            if (deviceName.isEmpty() || dataBase64.isEmpty()) {
                sendResponse(out, 400, "Bad Request",
                    JSONObject().apply { put("error", "device_name and data_base64 are required") }.toString())
                return
            }
            val timeout = j.optInt("timeout", 1000)
            val result = UsbHostManager.sendRawData(deviceName, dataBase64, timeout)
            sendResponse(out, 200, "OK", result)
        } catch (e: Exception) {
            Log.e(TAG, "USB sendRaw error: ${e.message}", e)
            sendResponse(out, 500, "Internal Error",
                JSONObject().apply { put("error", e.message) }.toString())
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  USB Raw Binary & Streaming endpoints (no Base64/JSON overhead)
    // ═══════════════════════════════════════════════════════════════════
    //
    // These endpoints eliminate HTTP/JSON/Base64 overhead for high-speed
    // USB bulk transfers where timing is critical (e.g. BROM/EDL mode).
    //
    // ── /usb/endpoint_info ────────────────────────────────────────────
    //   POST {"device_name":"...","endpoint":1}
    //   Returns endpoint metadata for raw/stream usage.
    //
    // ── /usb/raw_transfer (GET/POST) ────────────────────────────────
    //   POST with Content-Type: application/octet-stream
    //   Query params: ?device=...&endpoint=N&timeout=1000
    //   Body = raw bytes for OUT; empty for IN
    //   Response = raw bytes (IN) or 4-byte big-endian count (OUT)
    //   No JSON, no Base64 — pure binary.
    //
    // ── /usb/stream ───────────────────────────────────────────────────
    //   POST with JSON config body, then switches to raw binary protocol.
    //   After the HTTP response, the connection becomes a persistent
    //   binary stream for back-to-back USB transfers.
    //
    //   Binary frame format (all multi-byte big-endian):
    //     OUT (host→device):
    //       Client → Server: [1B 0x01][3B reserved][4B len:payload][payload...]
    //       Server → Client: [4B bytes_written]
    //     IN (device→host):
    //       Client → Server: [1B 0x02][3B reserved][4B max_read]
    //       Server → Client: [4B bytes_read][payload...]
    //     CLOSE:
    //       Client → Server: [1B 0xFF] → server closes connection
    //
    //   Usage from PRoot guest:
    //     exec 3<>/dev/tcp/127.0.0.1/1337
    //     echo -e 'POST /usb/stream HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 50\r\n\r\n{"device_name":"/dev/bus/usb/...","endpoint_in":1,"endpoint_out":2}' >&3
    //     head -1 <&3  # consume HTTP response
    //     # Now raw binary frames:
    //     # printf '\x01\x00\x00\x00\x00\x00\x10\x00' >&3  # OUT 4096 bytes
    //     # dd if=/dev/zero bs=4096 count=1 >&3
    //     # # read response
    //     # dd bs=4 count=1 <&3 2>/dev/null | od -An -tu4

    private fun handleUsbEndpointInfo(body: String, out: OutputStream) {
        try {
            val j = JSONObject(body)
            val deviceName = j.optString("device_name", "")
            val endpointAddress = j.optInt("endpoint", -1)
            if (deviceName.isEmpty() || endpointAddress < 0) {
                sendResponse(out, 400, "Bad Request",
                    JSONObject().apply { put("error", "device_name and endpoint are required") }.toString())
                return
            }
            val info = UsbHostManager.getEndpointInfo(deviceName, endpointAddress)
            if (info == null) {
                sendResponse(out, 404, "Not Found",
                    JSONObject().apply { put("error", "Endpoint not found") }.toString())
                return
            }
            sendResponse(out, 200, "OK", info)
        } catch (e: Exception) {
            Log.e(TAG, "USB endpoint_info error: ${e.message}", e)
            sendResponse(out, 500, "Internal Error",
                JSONObject().apply { put("error", e.message) }.toString())
        }
    }

    /**
     * Export USB device file descriptor to PRoot via UDS + SCM_RIGHTS.
     *
     * POST /usb/export_fd
     * Body: {"device_name": "/dev/bus/usb/001/002", "interface_id": 0}
     *
     * This queues the raw USB fd for the next PRoot client that connects
     * to the UsbFdExporter's UDS socket. After calling this, run `usb_bridge`
     * in PRoot to receive the fd and perform direct ioctl operations.
     */
    private fun handleUsbExportFd(body: String, out: OutputStream) {
        try {
            val j = JSONObject(body)
            val deviceName = j.optString("device_name", "")
            if (deviceName.isEmpty()) {
                sendResponse(out, 400, "Bad Request",
                    JSONObject().apply { put("error", "device_name is required") }.toString())
                return
            }

            // Auto-claim interface 0 if not already claimed
            if (!UsbHostManager.hasOpenConnection(deviceName)) {
                val ifaceId = j.optInt("interface_id", 0)
                val claimObj = UsbHostManager.claimInterface(deviceName, ifaceId)
                if (!claimObj.optBoolean("success", false) && !claimObj.optBoolean("already_claimed", false)) {
                    sendResponse(out, 500, "USB Error",
                        JSONObject().apply { put("error", "Failed to claim interface: ${claimObj.optString("error", "unknown")}") }.toString())
                    return
                }
                Log.i(TAG, "Auto-claimed USB interface $ifaceId for $deviceName")
            }

            // Extract raw fd via reflection
            val fd = UsbHostManager.getRawFileDescriptor(deviceName)
            if (fd < 0) {
                sendResponse(out, 500, "USB Error",
                    JSONObject().apply { put("error", "Failed to extract USB fd. Is the device claimed?") }.toString())
                return
            }

            // Queue fd for export
            UsbFdExporter.exportFd(deviceName, fd)

            val resp = JSONObject().apply {
                put("success", true)
                put("device_name", deviceName)
                put("uds_path", UsbFdExporter.getUdsPath())
                put("fd", fd)
                put("message", "USB fd queued. Run 'usb_bridge ${UsbFdExporter.getUdsPath()} ...' in PRoot to use it.")
            }
            sendResponse(out, 200, "OK", resp.toString())
            Log.i(TAG, "USB fd=$fd exported for $deviceName via ${UsbFdExporter.getUdsPath()}")

        } catch (e: Exception) {
            Log.e(TAG, "USB export_fd error: ${e.message}", e)
            sendResponse(out, 500, "Internal Error",
                JSONObject().apply { put("error", e.message) }.toString())
        }
    }

    /**
     * Raw binary USB bulk transfer — no JSON/Base64 overhead.
     *
     * Accepts raw binary POST body and returns raw binary response.
     * Parameters passed via HTTP headers or query string:
     *   X-USB-Device: device name
     *   X-USB-Endpoint: endpoint number
     *   X-USB-Timeout: timeout in ms (default 1000)
     *
     * For IN transfers: body = empty, response = raw data
     * For OUT transfers: body = data to send, response = 4-byte big-endian count
     *
     * This endpoint uses keep-alive so multiple transfers can reuse the connection.
     */
    private fun handleUsbRawTransfer(
        rawIn: java.io.InputStream,
        out: OutputStream,
        socket: Socket,
        method: String,
        contentLength: Int,
        headers: Map<String, String>
    ) {
        Log.i(TAG, "USB raw_transfer: contentLength=$contentLength, method=$method")

        try {
            val deviceName = headers["x-usb-device"] ?: ""
            val endpointAddress = headers["x-usb-endpoint"]?.toIntOrNull() ?: -1
            val timeout = headers["x-usb-timeout"]?.toIntOrNull() ?: 1000
            val direction = headers["x-usb-direction"] ?: ""

            if (deviceName.isEmpty() || endpointAddress < 0) {
                sendResponseKeepAlive(out, 400, "Bad Request",
                    "{\"error\":\"X-USB-Device and X-USB-Endpoint headers required\"}".toByteArray(),
                    "application/json")
                return
            }

            // Read raw body bytes (binary safe, from raw stream)
            val rawBody = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = rawIn.read(buf, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                buf.copyOf(totalRead)
            } else {
                ByteArray(0)
            }

            val effectiveDirection = when {
                direction.isNotEmpty() -> direction
                contentLength > 0 -> "OUT"
                else -> "IN"
            }

            if (effectiveDirection.equals("OUT", ignoreCase = true)) {
                val result = UsbHostManager.rawBulkTransfer(deviceName, endpointAddress, rawBody, timeout)
                if (!result.success) {
                    sendResponseKeepAlive(out, 500, "Transfer Failed",
                        JSONObject().apply { put("error", result.error) }.toString().toByteArray(),
                        "application/json")
                } else {
                    // Return 4-byte big-endian transferred count
                    val countBytes = java.nio.ByteBuffer.allocate(4).putInt(result.transferred).array()
                    sendResponseKeepAlive(out, 200, "OK", countBytes)
                }
            } else {
                val result = UsbHostManager.rawBulkTransfer(deviceName, endpointAddress, null, timeout)
                if (!result.success) {
                    sendResponseKeepAlive(out, 500, "Transfer Failed",
                        JSONObject().apply { put("error", result.error) }.toString().toByteArray(),
                        "application/json")
                } else {
                    // Return: 4B big-endian length + raw data
                    val lenBytes = java.nio.ByteBuffer.allocate(4).putInt(result.transferred).array()
                    val response = lenBytes + result.data
                    sendResponseKeepAlive(out, 200, "OK", response)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB raw_transfer error: ${e.message}", e)
            try {
                sendResponseKeepAlive(out, 500, "Internal Error",
                    JSONObject().apply { put("error", e.message) }.toString().toByteArray(),
                    "application/json")
            } catch (_: Exception) {}
        }
    }

    /**
     * USB streaming endpoint for BROM/EDL and other time-critical protocols.
     *
     * After the HTTP handshake, switches to a raw binary frame protocol
     * over the same TCP connection. No HTTP/JSON/Base64 overhead after startup.
     */
    private fun handleUsbStream(
        socket: Socket,
        context: Context,
        rawIn: java.io.InputStream,
        out: OutputStream,
        contentLength: Int,
        headers: Map<String, String>
    ) {
        Log.i(TAG, "USB stream connection established")

        try {
            // Read config from request body (binary-safe from raw stream)
            val configBody = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val n = rawIn.read(buf, totalRead, contentLength - totalRead)
                    if (n == -1) break
                    totalRead += n
                }
                String(buf, 0, totalRead, Charsets.UTF_8)
            } else {
                "{}"
            }

            val j = JSONObject(configBody)
            val deviceName = j.optString("device_name", headers["x-usb-device"] ?: "")
            val endpointIn = j.optInt("endpoint_in", headers["x-usb-endpoint-in"]?.toIntOrNull() ?: -1)
            val endpointOut = j.optInt("endpoint_out", headers["x-usb-endpoint-out"]?.toIntOrNull() ?: -1)

            if (deviceName.isEmpty() || (endpointIn < 0 && endpointOut < 0)) {
                sendResponse(out, 400, "Bad Request",
                    JSONObject().apply { put("error", "device_name and at least one endpoint required") }.toString())
                socket.close()
                return
            }

            // Send HTTP 200 and switch to raw binary mode
            val okHeaders = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: 0\r\n" +
                    "X-USB-Stream: ready\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(okHeaders.toByteArray(Charsets.UTF_8))
            out.flush()

            Log.i(TAG, "USB stream ready for $deviceName (IN=$endpointIn, OUT=$endpointOut)")

            // ── Binary frame loop on the raw socket ───────────────────
            val buffer = ByteArray(64 * 1024) // 64KB read buffer

            // Compute effective endpoint for a given direction
            fun endpointForDirection(isIn: Boolean): Int {
                return if (isIn) {
                    if (endpointIn >= 0) endpointIn else endpointOut
                } else {
                    if (endpointOut >= 0) endpointOut else endpointIn
                }
            }

            while (socket.isConnected && !socket.isClosed) {
                // Read frame header: [1B cmd][3B reserved][4B len] = 8 bytes
                var headerRead = 0
                val header = ByteArray(8)
                while (headerRead < 8) {
                    val n = rawIn.read(header, headerRead, 8 - headerRead)
                    if (n == -1) throw java.io.IOException("Stream closed by client")
                    headerRead += n
                }

                val cmd = header[0].toInt() and 0xFF
                val len = ((header[4].toInt() and 0xFF) shl 24) or
                        ((header[5].toInt() and 0xFF) shl 16) or
                        ((header[6].toInt() and 0xFF) shl 8) or
                        (header[7].toInt() and 0xFF)

                when (cmd) {
                    0x01 -> {
                        // OUT: host → device
                        if (len > 0) {
                            var payloadRead = 0
                            while (payloadRead < len) {
                                val n = rawIn.read(buffer, payloadRead, len - payloadRead)
                                if (n == -1) throw java.io.IOException("Stream closed during payload read")
                                payloadRead += n
                            }
                            val payload = buffer.copyOf(payloadRead)
                            val ep = endpointForDirection(false)
                            val result = UsbHostManager.rawBulkTransfer(deviceName, ep, payload, 1000)
                            val respLen = java.nio.ByteBuffer.allocate(4).putInt(result.transferred).array()
                            try { out.write(respLen); out.flush() } catch (_: Exception) { break }
                        } else {
                            val respLen = java.nio.ByteBuffer.allocate(4).putInt(0).array()
                            try { out.write(respLen); out.flush() } catch (_: Exception) { break }
                        }
                    }
                    0x02 -> {
                        // IN: device → host
                        val readSize = if (len in 1..buffer.size) len else 65536
                        val ep = endpointForDirection(true)
                        val result = UsbHostManager.rawBulkTransfer(deviceName, ep, null, 1000)
                        val actual = result.transferred
                        val respHeader = java.nio.ByteBuffer.allocate(4).putInt(actual).array()
                        try {
                            out.write(respHeader)
                            if (actual > 0) out.write(result.data, 0, actual)
                            out.flush()
                        } catch (_: Exception) { break }
                    }
                    0xFF -> {
                        // CLOSE
                        Log.i(TAG, "USB stream closed by client")
                        socket.close()
                        return
                    }
                    else -> {
                        Log.w(TAG, "USB stream unknown command: $cmd")
                        // Try to skip payload if any
                        if (len > 0) {
                            var skipped = 0
                            while (skipped < len) {
                                val n = rawIn.read(buffer, 0, minOf(len - skipped, buffer.size))
                                if (n == -1) break
                                skipped += n
                            }
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            Log.i(TAG, "USB stream ended: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "USB stream error: ${e.message}", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
            Log.i(TAG, "USB stream connection closed")
        }
    }
}

