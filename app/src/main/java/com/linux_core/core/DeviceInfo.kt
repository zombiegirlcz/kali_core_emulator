package com.linux_core.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import org.json.JSONObject

/**
 * Device info JSONy sdílené HTTP endpointy (/battery, /wifi, /location)
 * a Binder bridgem (ICoreBridge.getBattery/getWifi/getLocation).
 * Těla přesunuta 1:1 z LocalApiServer.handleBattery/handleWifi/handleLocation.
 */
object DeviceInfo {

    fun batteryJson(ctx: Context): String {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = ctx.registerReceiver(null, filter)
            ?: return """{"error":"Could not query battery state"}"""

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

        return JSONObject().apply {
            put("percentage", percentage)
            put("temperature", temperature)
            put("voltage", voltage)
            put("status", status)
            put("health", health)
            put("plugged", plugged)
        }.toString()
    }

    fun wifiJson(ctx: Context): String {
        return try {
            val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            JSONObject().apply {
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
        } catch (e: Exception) {
            """{"error":"${e.message}"}"""
        }
    }

    private fun formatIpv4(value: Int): String {
        return "${value and 0xFF}.${(value shr 8) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 24) and 0xFF}"
    }

    fun locationJson(ctx: Context): String {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var location: Location? = null
            try {
                location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (_: SecurityException) {}

            JSONObject().apply {
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
        } catch (e: Exception) {
            """{"error":"${e.message}"}"""
        }
    }
}
