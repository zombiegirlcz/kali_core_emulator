package com.linux_core.core

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

data class IpInfo(
    val countryCode: String?,
    val countryName: String?,
    val regionName: String?,
    val cityName: String?,
    val zipCode: String?,
    val isProxy: Boolean,
    val flagEmoji: String,
    val isp: String? = null,
    val org: String? = null,
    val asn: String? = null
)

object IpInfoResolver {
    private const val TAG = "IpInfoResolver"
    private val client = OkHttpClient()
    private val cache = ConcurrentHashMap<String, IpInfo>()

    fun getCached(ip: String): IpInfo? {
        val cleanIp = ip.trim().substringBefore(":")
        if (isPrivateIp(cleanIp)) return localIpInfo
        return cache[cleanIp]
    }

    // Local / private IP info fallback
    private val localIpInfo = IpInfo(
        countryCode = "LOCAL",
        countryName = "Místní síť",
        regionName = "Privátní IP rozsah",
        cityName = "Loopback / LAN",
        zipCode = "",
        isProxy = false,
        flagEmoji = "🏠"
    )

    fun getFlagEmoji(countryCode: String?): String {
        if (countryCode == null || countryCode.length != 2) return "🌐"
        val codeUpper = countryCode.uppercase()
        return try {
            val firstChar = Character.codePointAt(codeUpper, 0) - 0x41 + 0x1F1E6
            val secondChar = Character.codePointAt(codeUpper, 1) - 0x41 + 0x1F1E6
            String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
        } catch (e: Exception) {
            "🌐"
        }
    }

    fun isPrivateIp(ip: String): Boolean {
        if (ip == "172.18.11.218" || ip == "127.0.0.1" || ip == "localhost" || ip.startsWith("::1") || ip.startsWith("fe80")) {
            return true
        }
        return try {
            val address = InetAddress.getByName(ip)
            address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress
        } catch (e: Exception) {
            // If it's a hostname or malformed, check prefixes as fallback
            ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172.")
        }
    }

    fun resolve(ip: String, onResolved: (IpInfo) -> Unit) {
        val cleanIp = ip.trim().substringBefore(":") // strip port if present
        
        if (isPrivateIp(cleanIp)) {
            onResolved(localIpInfo)
            return
        }

        val cached = cache[cleanIp]
        if (cached != null) {
            onResolved(cached)
            return
        }

        // Fetch asynchronously from freeipapi
        val url = "https://freeipapi.com/api/json/$cleanIp"
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to resolve IP $cleanIp: ${e.message}")
                // Fallback to empty info with globe flag so we don't block
                val fallback = IpInfo(null, "Neznámá země", null, null, null, false, "🌐")
                onResolved(fallback)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "Unsuccessful response code resolving IP $cleanIp: ${resp.code}")
                        val fallback = IpInfo(null, "Neznámá země", null, null, null, false, "🌐")
                        onResolved(fallback)
                        return
                    }

                    val bodyString = resp.body?.string()
                    if (bodyString.isNullOrEmpty()) {
                        val fallback = IpInfo(null, "Neznámá země", null, null, null, false, "🌐")
                        onResolved(fallback)
                        return
                    }

                    try {
                        val json = JSONObject(bodyString)
                        val countryCode = if (json.isNull("countryCode")) null else json.optString("countryCode")
                        val countryName = if (json.isNull("countryName")) null else json.optString("countryName")
                        val regionName = if (json.isNull("regionName")) null else json.optString("regionName")
                        val cityName = if (json.isNull("cityName")) null else json.optString("cityName")
                        val zipCode = if (json.isNull("zipCode")) null else json.optString("zipCode")
                        val isProxy = json.optBoolean("isProxy", false)
                        val emoji = getFlagEmoji(countryCode)

                        val info = IpInfo(
                            countryCode = countryCode,
                            countryName = countryName,
                            regionName = regionName,
                            cityName = cityName,
                            zipCode = zipCode,
                            isProxy = isProxy,
                            flagEmoji = emoji
                        )

                        cache[cleanIp] = info
                        onResolved(info)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing JSON response for IP $cleanIp: ${e.message}")
                        val fallback = IpInfo(null, "Neznámá země", null, null, null, false, "🌐")
                        onResolved(fallback)
                    }
                }
            }
        })
    }
}
