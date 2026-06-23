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
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.app.admin.DevicePolicyManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.telephony.TelephonyManager
import android.telephony.SignalStrength
import android.net.Uri
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
        val sharedPrefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        val shareLocalApi = sharedPrefs.getBoolean("share_local_api", false)
        val bindAddress = if (shareLocalApi) "0.0.0.0" else "127.0.0.1"
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
                path == "/battery/optimize" && method == "GET" -> handleBatteryOptimizeGet(context, out)
                path == "/battery/optimize" && method == "POST" -> handleBatteryOptimizePost(context, out)
                path == "/rootfs/backup" && method == "POST" -> handleRootfsBackup(context, out)
                path == "/rootfs/restore" && method == "POST" -> handleRootfsRestore(context, body, out)
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
                    var dbm = Int.MIN_VALUE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try { dbm = signal.getDbm(0) } catch (_: Exception) {}
                    }
                    val level = signal.level
                    json.put("signal_dbm", if (dbm > -1000) dbm else JSONObject.NULL)
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
                        if (!cell.isRegistered) continue
                        val cellObj = JSONObject()
                        cellObj.put("type", cell::class.java.simpleName.replace("CellInfo", ""))
                        cellObj.put("registered", cell.isRegistered)
                        cellObj.put("timestamp", cell.timestampMillis)
                        try {
                            val ci = cell.cellIdentity
                            if (ci != null) {
                                val mcc = ci.mcc
                                val mnc = ci.mnc
                                val plmn = if (mcc != null && mnc != null) "${mcc}-${mnc}" else null
                                cellObj.put("mcc", mcc ?: JSONObject.NULL)
                                cellObj.put("mnc", mnc ?: JSONObject.NULL)
                                if (plmn != null) cellObj.put("plmn", plmn)
                            }
                        } catch (_: Exception) {}
                        try {
                            val ss = cell.cellSignalStrength
                            if (ss != null) {
                                cellObj.put("signal_dbm", ss.dbm)
                                cellObj.put("signal_level", ss.level)
                                cellObj.put("asu", ss.asuLevel)
                            }
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

    private fun handleVpnLogs(path: String, out: OutputStream) {
        val params = parseQueryParams(path)
        val format = params["format"]
        val logs = VpnLogManager.getLogs()
        if (format == "csv" || path.contains("/csv")) {
            val csv = StringBuilder()
            csv.append("timestamp,protocol,srcIp,srcPort,dstIp,dstPort,size,category,detail,entropy,payloadHex\n")
            for (log in logs) {
                val escapedDetail = log.detail.replace("\"", "\"\"")
                csv.append("${log.timestamp},${log.protocol},${log.srcIp},${log.srcPort},${log.dstIp},${log.dstPort},${log.size},${log.category.name},\"$escapedDetail\",${log.entropy},${log.payloadHex ?: ""}\n")
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

        val launcherScript = java.io.File(appContext?.filesDir, "launcher.sh")

        // Try daemon first (fast path)
        var daemonSuccess = false
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
            Log.w(TAG, "Agent daemon not reachable on port 13338, attempting to start it...")
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "Agent daemon connect timeout, attempting to start it...")
        } catch (e: Exception) {
            currentAgentStatus = ""
            sendResponse(out, 500, "Internal Error", "{\"error\":\"Agent daemon error: ${e.message}\"}")
            return
        }

        // Self-healing: try to start the daemon
        if (launcherScript.exists() && launcherScript.canExecute()) {
            try {
                currentAgentStatus = "Starting agent daemon..."
                val pbStart = ProcessBuilder("sh", launcherScript.absolutePath, "nethunter-agent-cli", "start")
                pbStart.directory(appContext?.filesDir)
                val procStart = pbStart.start()
                procStart.waitFor()
                Thread.sleep(1500) // Give the daemon 1.5s to bind to the port

                // Retry connecting to daemon
                currentAgentStatus = "Connecting to agent..."
                val url = java.net.URL("http://127.0.0.1:13338/query")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 3000
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

    private fun handleAppsUsage(context: Context, out: OutputStream) {
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
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
            sendResponse(out, 200, "OK", hierarchy)
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
            val enable = body.trim().equals("on", ignoreCase = true) || body.trim().equals("true", ignoreCase = true)
            @Suppress("DEPRECATION")
            val success = wifiManager.setWifiEnabled(enable)
            sendResponse(out, 200, "OK", "{\"status\":\"wifi_updated\",\"enabled\":$enable,\"success\":$success}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleDeviceAdminStatus(context: Context, out: OutputStream) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, NetHunterDeviceAdminReceiver::class.java)
            val active = dpm.isAdminActive(adminComponent)
            sendResponse(out, 200, "OK", "{\"active\":$active}")
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Error", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleDeviceAdminRequest(context: Context, out: OutputStream) {
        try {
            val adminComponent = ComponentName(context, NetHunterDeviceAdminReceiver::class.java)
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
                sendResponse(out, 200, "OK", "{\"status\":\"locked\"}")
            } else {
                sendResponse(out, 200, "OK", "{\"error\":\"Device admin not active\",\"needs_activation\":\"/device/admin\"}")
            }
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
}

