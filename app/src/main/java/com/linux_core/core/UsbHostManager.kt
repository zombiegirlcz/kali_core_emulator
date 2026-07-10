package com.linux_core.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages USB Host-mode device discovery and communication.
 *
 * Uses Android's [UsbManager] to enumerate attached USB devices,
 * claim interfaces, and perform bulk/control transfers.
 *
 * Permission model:
 * - For each device, the app must obtain user consent via an intent.
 * - This class caches permission grants in [permittedDeviceNames] during runtime.
 * - For first-time use, call [requestPermission] which triggers the system dialog.
 */
object UsbHostManager {
    private const val TAG = "UsbHostManager"
    private const val ACTION_USB_PERMISSION = "com.linux_core.USB_PERMISSION"

    private var usbManager: UsbManager? = null
    private var appContext: Context? = null

    /** Devices that the user has granted permission for during this session. */
    private val permittedDeviceNames = ConcurrentHashMap.newKeySet<String>()

    /** Open connections keyed by deviceName. */
    private val openConnections = ConcurrentHashMap<String, UsbDeviceConnection>()

    /** Currently claimed interfaces keyed by "deviceName:interfaceId". */
    private val claimedInterfaces = ConcurrentHashMap<String, UsbInterface>()

    /** Registered permission broadcast receiver (one instance for the whole app). */
    private var permissionReceiver: BroadcastReceiver? = null

    // ─── Initialisation ──────────────────────────────────────────────

    fun init(context: Context) {
        appContext = context.applicationContext
        usbManager = appContext?.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            Log.w(TAG, "UsbManager not available – device may not support USB Host")
        } else {
            Log.i(TAG, "UsbHostManager initialised")
        }
        registerPermissionReceiver(context)
    }

    private fun registerPermissionReceiver(context: Context) {
        if (permissionReceiver != null) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION != intent.action) return
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (device != null && granted) {
                    permittedDeviceNames.add(device.deviceName)
                    Log.i(TAG, "USB permission GRANTED for ${device.deviceName}")
                } else if (device != null) {
                    Log.w(TAG, "USB permission DENIED for ${device.deviceName}")
                }
            }
        }
        try {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            // Fallback: register exported (may need the permission broadcast)
            try {
                context.registerReceiver(permissionReceiver, filter)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to register USB permission receiver: ${e2.message}")
            }
        }
    }

    // ─── Device Enumeration ──────────────────────────────────────────

    /**
     * Returns a JSON array of all currently attached USB host devices.
     */
    fun listDevices(): String {
        val mgr = usbManager ?: return JSONArray().put(
            JSONObject().put("error", "USB Host not available")
        ).toString()

        val deviceMap = mgr.deviceList ?: return "[]"
        val arr = JSONArray()

        for ((_, device) in deviceMap) {
            val obj = JSONObject().apply {
                put("device_name", device.deviceName)
                put("device_id", device.deviceId)
                put("vendor_id", device.vendorId)
                put("product_id", device.productId)
                put("class", device.deviceClass)
                put("subclass", device.deviceSubclass)
                put("protocol", device.deviceProtocol)
                put("manufacturer_name", device.manufacturerName ?: JSONObject.NULL)
                put("product_name", device.productName ?: JSONObject.NULL)
                put("serial_number", device.serialNumber ?: JSONObject.NULL)
                put("has_permission", mgr.hasPermission(device))

                // Interfaces
                val ifaces = JSONArray()
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    val ifObj = JSONObject().apply {
                        put("id", iface.id)
                        put("class", iface.interfaceClass)
                        put("subclass", iface.interfaceSubclass)
                        put("protocol", iface.interfaceProtocol)
                        put("name", iface.name ?: JSONObject.NULL)

                        val eps = JSONArray()
                        for (j in 0 until iface.endpointCount) {
                            val ep = iface.getEndpoint(j)
                            ep.toJson().let { eps.put(it) }
                        }
                        put("endpoints", eps)
                    }
                    ifaces.put(ifObj)
                }
                put("interfaces", ifaces)
            }
            arr.put(obj)
        }

        return arr.toString()
    }

    /**
     * Convert a UsbEndpoint to a JSON object.
     */
    private fun UsbEndpoint.toJson(): JSONObject {
        val dir = when (direction) {
            UsbConstants.USB_DIR_IN -> "IN"
            UsbConstants.USB_DIR_OUT -> "OUT"
            else -> "UNKNOWN"
        }
        val type = when (type) {
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
            else -> "UNKNOWN"
        }
        return JSONObject().apply {
            put("address", endpointNumber)
            put("direction", dir)
            put("type", type)
            put("max_packet_size", maxPacketSize)
            put("interval", interval)
            put("attributes", attributes)
        }
    }

    // ─── Permission ──────────────────────────────────────────────────

    /**
     * Request permission for a USB device.
     * The system shows a dialog to the user.
     * The result is cached in [permittedDeviceNames].
     */
    fun requestPermission(deviceName: String): JSONObject {
        val mgr = usbManager ?: return JSONObject().apply {
            put("success", false)
            put("error", "USB Host not available")
        }
        val ctx = appContext ?: return JSONObject().apply {
            put("success", false)
            put("error", "Context not available")
        }

        val device = findDeviceByName(deviceName)
        if (device == null) {
            return JSONObject().apply {
                put("success", false)
                put("error", "Device not found: $deviceName")
            }
        }

        if (mgr.hasPermission(device)) {
            permittedDeviceNames.add(device.deviceName)
            return JSONObject().apply {
                put("success", true)
                put("already_granted", true)
            }
        }

        val intent = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mgr.requestPermission(device, intent)

        return JSONObject().apply {
            put("success", true)
            put("pending", true)
            put("message", "Permission dialog requested. Use GET /usb/devices to check has_permission later.")
        }
    }

    // ─── Claim / Release ─────────────────────────────────────────────

    /**
     * Claim an interface on a USB device, opening a connection if needed.
     */
    fun claimInterface(deviceName: String, interfaceId: Int, forceClaim: Boolean = false): JSONObject {
        val mgr = usbManager ?: return errorObj("USB Host not available")
        val ctx = appContext ?: return errorObj("Context not available")

        val device = findDeviceByName(deviceName)
        if (device == null) return errorObj("Device not found: $deviceName")

        if (!mgr.hasPermission(device) && deviceName !in permittedDeviceNames) {
            return errorObj("Permission not granted for $deviceName. Call requestPermission first.")
        }

        if (interfaceId < 0 || interfaceId >= device.interfaceCount) {
            return errorObj("Invalid interface ID $interfaceId (device has ${device.interfaceCount} interfaces)")
        }

        val iface = device.getInterface(interfaceId)

        // Already claimed?
        val claimedKey = "${deviceName}:${interfaceId}"
        if (claimedInterfaces.containsKey(claimedKey) && !forceClaim) {
            return JSONObject().apply {
                put("success", true)
                put("already_claimed", true)
            }
        }

        // Open connection if not already open
        var connection = openConnections[deviceName]
        if (connection == null) {
            connection = mgr.openDevice(device)
            if (connection == null) {
                return errorObj("Failed to open device connection")
            }
            openConnections[deviceName] = connection
        }

        val claimed = connection.claimInterface(iface, forceClaim)
        if (!claimed) {
            return errorObj("Failed to claim interface $interfaceId")
        }

        claimedInterfaces[claimedKey] = iface
        return JSONObject().apply {
            put("success", true)
            put("interface_id", interfaceId)
            put("device_name", deviceName)
        }
    }

    /**
     * Release a previously claimed interface and optionally close the connection.
     */
    fun releaseInterface(deviceName: String, interfaceId: Int? = null): JSONObject {
        val connection = openConnections[deviceName] ?: return errorObj("No open connection for $deviceName")

        if (interfaceId != null) {
            val claimedKey = "${deviceName}:${interfaceId}"
            val iface = claimedInterfaces[claimedKey]
            if (iface != null) {
                connection.releaseInterface(iface)
                claimedInterfaces.remove(claimedKey)
                Log.i(TAG, "Released interface $interfaceId on $deviceName")
            }
        } else {
            // Release all interfaces for this device
            val toRemove = claimedInterfaces.keys.filter { it.startsWith("$deviceName:") }
            for (key in toRemove) {
                val iface = claimedInterfaces[key]
                if (iface != null) {
                    connection.releaseInterface(iface)
                }
                claimedInterfaces.remove(key)
            }
        }

        // Close connection if no interfaces remain claimed for this device
        val hasRemaining = claimedInterfaces.keys.any { it.startsWith("$deviceName:") }
        if (!hasRemaining) {
            connection.close()
            openConnections.remove(deviceName)
            Log.i(TAG, "Closed USB connection for $deviceName")
        }

        return JSONObject().apply {
            put("success", true)
        }
    }

    // ─── Data Transfer ───────────────────────────────────────────────

    /**
     * Perform a bulk transfer on a claimed endpoint.
     *
     * @param deviceName the device identifier
     * @param endpointAddress endpoint number (from the device descriptor)
     * @param dataBase64 base64-encoded payload to send (OUT endpoint) or empty for IN
     * @param timeout milliseconds (default 1000)
     * @param direction "IN" for device→host, "OUT" for host→device (default auto-detect from endpoint)
     * @return JSON with transferred bytes or received data encoded in base64
     */
    fun bulkTransfer(
        deviceName: String,
        endpointAddress: Int,
        dataBase64: String = "",
        timeout: Int = 1000,
        direction: String? = null
    ): String {
        val connection = openConnections[deviceName]
            ?: return errorObj("No open connection for $deviceName. Claim an interface first.").toString()

        // Find the endpoint by address on any claimed interface
        val endpoint = findEndpointOnClaimedInterfaces(deviceName, endpointAddress)
        if (endpoint == null) {
            return errorObj("Endpoint $endpointAddress not found on any claimed interface").toString()
        }

        val isIn = when {
            direction != null -> direction.equals("IN", ignoreCase = true)
            else -> endpoint.direction == UsbConstants.USB_DIR_IN
        }

        return try {
            if (isIn) {
                // IN: read from device
                val bufSize = endpoint.maxPacketSize.coerceAtLeast(4096)
                val buffer = ByteArray(bufSize)
                val transferred = connection.bulkTransfer(endpoint, buffer, buffer.size, timeout)
                if (transferred < 0) {
                    errorObj("Bulk IN transfer failed (returned $transferred)").toString()
                } else {
                    val received = buffer.copyOf(transferred)
                    JSONObject().apply {
                        put("success", true)
                        put("transferred", transferred)
                        put("data_base64", Base64.encodeToString(received, Base64.NO_WRAP))
                        put("data_hex", received.joinToString("") { "%02x".format(it) })
                    }.toString()
                }
            } else {
                // OUT: write to device
                val data = Base64.decode(dataBase64, Base64.NO_WRAP)
                val transferred = connection.bulkTransfer(endpoint, data, data.size, timeout)
                if (transferred < 0) {
                    errorObj("Bulk OUT transfer failed (returned $transferred)").toString()
                } else {
                    JSONObject().apply {
                        put("success", true)
                        put("transferred", transferred)
                        put("requested", data.size)
                    }.toString()
                }
            }
        } catch (e: Exception) {
            errorObj("Bulk transfer error: ${e.message}").toString()
        }
    }

    /**
     * Perform a control transfer on the device's control endpoint (endpoint 0).
     */
    fun controlTransfer(
        deviceName: String,
        requestType: Int = 0x40,  // Vendor OUT by default
        request: Int = 0,
        value: Int = 0,
        index: Int = 0,
        dataBase64: String = "",
        timeout: Int = 1000
    ): String {
        val connection = openConnections[deviceName]
            ?: return errorObj("No open connection for $deviceName. Claim an interface first.").toString()

        return try {
            val data = if (dataBase64.isNotEmpty()) {
                Base64.decode(dataBase64, Base64.NO_WRAP)
            } else {
                ByteArray(0)
            }

            val transferred = connection.controlTransfer(requestType, request, value, index, data, data.size, timeout)
            if (transferred < 0) {
                errorObj("Control transfer failed (returned $transferred)").toString()
            } else {
                JSONObject().apply {
                    put("success", true)
                    put("transferred", transferred)
                    if (data.isNotEmpty() && (requestType and 0x80) != 0) {
                        // Device-to-host direction → include received data
                        put("data_base64", Base64.encodeToString(data, Base64.NO_WRAP))
                        put("data_hex", data.joinToString("") { "%02x".format(it) })
                    }
                }.toString()
            }
        } catch (e: Exception) {
            errorObj("Control transfer error: ${e.message}").toString()
        }
    }

    /**
     * Send a raw data blob to the device (convenience: finds first OUT bulk endpoint
     * on the first claimed interface and sends the data).
     */
    fun sendRawData(deviceName: String, dataBase64: String, timeout: Int = 1000): String {
        val connection = openConnections[deviceName]
            ?: return errorObj("No open connection for $deviceName. Claim an interface first.").toString()

        // Find first OUT bulk endpoint from all claimed interfaces
        val targetEndpoints = mutableListOf<UsbEndpoint>()
        for ((key, iface) in claimedInterfaces) {
            if (key.startsWith("$deviceName:")) {
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                        targetEndpoints.add(ep)
                    }
                }
            }
        }

        if (targetEndpoints.isEmpty()) {
            return errorObj("No OUT bulk endpoint found on any claimed interface for $deviceName").toString()
        }

        val data = Base64.decode(dataBase64, Base64.NO_WRAP)
        val results = JSONArray()
        var totalTransferred = 0

        for (ep in targetEndpoints) {
            try {
                val chunk = if (ep.maxPacketSize > 0 && data.size > ep.maxPacketSize) {
                    // Split into max-packet-size chunks
                    val chunks = data.size / ep.maxPacketSize + (if (data.size % ep.maxPacketSize != 0) 1 else 0)
                    for (c in 0 until chunks) {
                        val start = c * ep.maxPacketSize
                        val end = minOf(start + ep.maxPacketSize, data.size)
                        val buf = data.copyOfRange(start, end)
                        val t = connection.bulkTransfer(ep, buf, buf.size, timeout)
                        if (t >= 0) totalTransferred += t
                    }
                    data // mark done
                } else {
                    val t = connection.bulkTransfer(ep, data, data.size, timeout)
                    if (t >= 0) totalTransferred += t
                }
                results.put(JSONObject().apply {
                    put("endpoint", ep.endpointNumber)
                    put("transferred", totalTransferred)
                })
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("endpoint", ep.endpointNumber)
                    put("error", e.message)
                })
            }
        }

        return JSONObject().apply {
            put("success", totalTransferred >= data.size)
            put("total_transferred", totalTransferred)
            put("total_requested", data.size)
            put("endpoint_results", results)
        }.toString()
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private fun findDeviceByName(name: String): UsbDevice? {
        val mgr = usbManager ?: return null
        return mgr.deviceList?.values?.find { it.deviceName == name }
    }

    private fun findEndpointOnClaimedInterfaces(deviceName: String, address: Int): UsbEndpoint? {
        for ((key, iface) in claimedInterfaces) {
            if (key.startsWith("$deviceName:")) {
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.endpointNumber == address) return ep
                }
            }
        }
        return null
    }

    private fun errorObj(message: String): JSONObject = JSONObject().apply {
        put("success", false)
        put("error", message)
    }

    // ─── Cleanup ─────────────────────────────────────────────────────

    fun shutdown() {
        // Release all connections
        for (deviceName in openConnections.keys) {
            releaseInterface(deviceName)
        }
        openConnections.clear()
        claimedInterfaces.clear()
        permittedDeviceNames.clear()

        try {
            permissionReceiver?.let { appContext?.unregisterReceiver(it) }
        } catch (e: Exception) { /* best-effort */ }
        permissionReceiver = null
        usbManager = null
        appContext = null
        Log.i(TAG, "UsbHostManager shutdown")
    }
}
