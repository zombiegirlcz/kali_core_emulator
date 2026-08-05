package com.adguard.dnslibs.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.util.ArrayList

object DnsNetworkUtils {
    @JvmStatic
    fun getDNSSearchDomains(context: Context): List<String> {
        val domainsList = ArrayList<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val network = cm.activeNetwork
                if (network != null) {
                    val lp = cm.getLinkProperties(network)
                    val domainsStr = lp?.domains
                    if (!domainsStr.isNullOrEmpty()) {
                        val parts = domainsStr.split("[,\\s]+".toRegex())
                        for (part in parts) {
                            val trimmed = part.trim()
                            if (trimmed.isNotEmpty()) {
                                domainsList.add(trimmed)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DnsNetworkUtils", "Failed to retrieve DNS search domains", e)
        }
        return domainsList
    }
}
