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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors

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
        initTts(context)
        executor.execute {
            try {
                serverSocket = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "Local API Server started on port $PORT")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleConnection(context, socket) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket exception: ${e.message}")
            }
        }
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

    private fun handleConnection(context: Context, socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            Log.d(TAG, "Request: $requestLine")
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"Invalid HTTP request\"}")
                return
            }
            val method = parts[0]
            val path = parts[1]

            // Parse headers to find Content-Length
            var contentLength = 0
            var line: String? = reader.readLine()
            while (line != null && line.isNotEmpty()) {
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

            routeRequest(context, method, path, body, out)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling connection: ${e.message}", e)
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun routeRequest(context: Context, method: String, path: String, body: String, out: OutputStream) {
        try {
            when {
                path == "/battery" && method == "GET" -> handleBattery(context, out)
                path == "/vibrate" && method == "POST" -> handleVibrate(context, body, out)
                path == "/toast" && method == "POST" -> handleToast(context, body, out)
                path == "/tts" && method == "POST" -> handleTts(body, out)
                path == "/clipboard" && method == "GET" -> handleClipboardGet(context, out)
                path == "/clipboard" && method == "POST" -> handleClipboardSet(context, body, out)
                path == "/notification" && method == "POST" -> handleNotification(context, body, out)
                path == "/wifi" && method == "GET" -> handleWifi(context, out)
                path == "/location" && method == "GET" -> handleLocation(context, out)
                path == "/volume" && method == "GET" -> handleVolumeGet(context, out)
                path == "/volume" && method == "POST" -> handleVolumeSet(context, body, out)
                path == "/torch" && method == "POST" -> handleTorch(context, body, out)
                path == "/shell" && method == "POST" -> handleShell(body, out)
                path == "/vpn" && method == "GET" -> handleVpnStatus(context, out)
                path == "/vpn/logs" && method == "GET" -> handleVpnLogs(out)
                path == "/vpn/stop" && method == "POST" -> handleVpnStop(context, out)
                path == "/vpn/start" && method == "POST" -> handleVpnStart(context, out)
                path.startsWith("/vpn/ignore") && method == "GET" -> handleVpnIgnoreGet(path, out)
                path.startsWith("/vpn/ignore") && method == "POST" -> handleVpnIgnorePost(path, out)
                path == "/agent/query" && method == "POST" -> handleAgentQuery(body, out)
                path == "/voice_input" && method == "GET" -> handleVoiceInput(context, out)
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

        sendResponse(out, 200, "OK", json)
    }

    private fun handleVibrate(context: Context, body: String, out: OutputStream) {
        val durationMs = body.trim().toLongOrNull() ?: 500L
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
        sendResponse(out, 200, "OK", "{\"status\":\"vibrated\",\"duration\":$durationMs}")
    }

    private fun handleToast(context: Context, body: String, out: OutputStream) {
        val message = body.ifEmpty { "Hello from API!" }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
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
        sendResponse(out, 200, "OK", "{\"status\":\"spoken\"}")
    }

    private fun handleClipboardGet(context: Context, out: OutputStream) {
        Handler(Looper.getMainLooper()).post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
                sendResponse(out, 200, "OK", JSONObject().put("text", text).toString())
            } catch (e: Exception) {
                sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
            }
        }
    }

    private fun handleClipboardSet(context: Context, body: String, out: OutputStream) {
        Handler(Looper.getMainLooper()).post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = android.content.ClipData.newPlainText("nethunter_api", body)
                clipboard.setPrimaryClip(clip)
                sendResponse(out, 200, "OK", "{\"status\":\"updated\"}")
            } catch (e: Exception) {
                sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
            }
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
            sendResponse(out, 200, "OK", "{\"status\":\"notified\"}")
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
                } else {
                    put("error", "No connection info available")
                }
            }.toString()
            sendResponse(out, 200, "OK", json)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
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
                } else {
                    put("error", "No last known location available. Check permissions and GPS.")
                }
            }.toString()
            sendResponse(out, 200, "OK", json)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
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
            sendResponse(out, 200, "OK", "{\"status\":\"torch_updated\",\"state\":\"${if (enable) "on" else "off"}\"}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleShell(body: String, out: OutputStream) {
        try {
            val command = body.trim()
            if (command.isEmpty()) {
                sendResponse(out, 400, "Bad Request", "{\"error\":\"Command cannot be empty\"}")
                return
            }
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            val json = JSONObject().apply {
                put("exit_code", exitCode)
                put("stdout", output)
                put("stderr", error)
            }.toString()
            sendResponse(out, 200, "OK", json)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleVpnLogs(out: OutputStream) {
        val logs = VpnLogManager.getLogs()
        val array = org.json.JSONArray()
        logs.forEach { array.put(it.toJsonObject()) }
        sendResponse(out, 200, "OK", array.toString())
    }

    private fun handleVpnStatus(context: Context, out: OutputStream) {
        val running = com.linux_core.core.VpnCaptureService.isRunning()
        val packets = com.linux_core.core.VpnCaptureService.getCapturedPacketCount()
        val bytes = com.linux_core.core.VpnCaptureService.getCapturedByteCount()
        val json = JSONObject().apply {
            put("running", running)
            put("packets", packets)
            put("bytes", bytes)
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

        // Try daemon first (fast path)
        try {
            val url = java.net.URL("http://127.0.0.1:13338/query")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = 0
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            val payload = JSONObject().put("prompt", prompt).toString()
            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
            }

            currentAgentStatus = "Agent is processing..."

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                currentAgentStatus = ""
                sendResponse(out, 200, "OK", responseText)
                return
            } else {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${conn.responseCode}"
                currentAgentStatus = ""
                sendResponse(out, 500, "Internal Error", "{\"error\":\"Agent error: $errText\"}")
                return
            }
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "Agent daemon not reachable on port 13338, falling back to inline PRoot execution")
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "Agent daemon connect timeout, falling back to inline PRoot execution")
        } catch (e: Exception) {
            // For other errors during daemon communication (e.g. read timeout during processing),
            // don't fall back - that means daemon IS running but query failed
            currentAgentStatus = ""
            sendResponse(out, 500, "Internal Error", "{\"error\":\"Agent daemon error: ${e.message}\"}")
            return
        }

        // Fallback: run agent inline via PRoot + launcher.sh
        currentAgentStatus = "Starting agent..."
        try {
            val launcherScript = java.io.File(appContext?.filesDir, "launcher.sh")
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
                        } else {
                            errorMsg = "No speech detected"
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
}

