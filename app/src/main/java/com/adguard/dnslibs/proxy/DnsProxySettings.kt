package com.adguard.dnslibs.proxy

import java.util.ArrayList

class DnsProxySettings {
    var upstreamSettings: List<Any> = ArrayList()
    var fallbackDomains: MutableList<String> = ArrayList()
    var isDetectSearchDomains: Boolean = false
    var isIpv6Available: Boolean = true
    var blockMode: Int = 0

    enum class BlockingMode(val value: Int) {
        REFUSED(0),
        NXDOMAIN(1),
        ADDRESS(2)
    }
}
