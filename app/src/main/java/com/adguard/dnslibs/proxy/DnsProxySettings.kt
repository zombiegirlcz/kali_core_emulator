package com.adguard.dnslibs.proxy

import java.util.ArrayList

class DnsProxySettings {
    enum class BlockingMode(val code: Int) {
        REFUSED(4),
        NXDOMAIN(1),
        ADDRESS(2),
        UNSPECIFIED_ADDRESS(5);

        companion object {
            @JvmStatic
            fun fromCode(code: Int): BlockingMode {
                for (m in values()) {
                    if (m.code == code) {
                        return m
                    }
                }
                throw IllegalArgumentException("code is out of range")
            }
        }
    }

    @JvmField var upstreams: List<UpstreamSettings>? = ArrayList()
    @JvmField var fallbacks: List<UpstreamSettings>? = ArrayList()
    @JvmField var fallbackDomains: MutableList<String> = ArrayList()
    @JvmField var detectSearchDomains: Boolean = false
    @JvmField var dns64: Dns64Settings? = null
    @JvmField var blockedResponseTtlSecs: Long = 0
    @JvmField var filterParams: List<FilterParams>? = ArrayList()
    @JvmField var listeners: List<ListenerSettings>? = ArrayList()
    @JvmField var outboundProxy: OutboundProxySettings? = null
    @JvmField var ipv6Available: Boolean = true
    @JvmField var blockIpv6: Boolean = false
    @JvmField var adblockRulesBlockingMode: BlockingMode = BlockingMode.ADDRESS
    @JvmField var hostsRulesBlockingMode: BlockingMode = BlockingMode.ADDRESS
    @JvmField var customBlockingIpv4: String? = null
    @JvmField var customBlockingIpv6: String? = null
    @JvmField var dnsCacheSize: Long = 1000
    @JvmField var optimisticCache: Boolean = false
    @JvmField var enableDNSSECOK: Boolean = false
    @JvmField var enableRetransmissionHandling: Boolean = false
    @JvmField var blockEch: Boolean = false
    @JvmField var blockH3Alpn: Boolean = false
    @JvmField var enableParallelUpstreamQueries: Boolean = false
    @JvmField var enableFallbackOnUpstreamsFailure: Boolean = true
    @JvmField var enableServfailOnUpstreamsFailure: Boolean = false
    @JvmField var enableHttp3: Boolean = false
    @JvmField var enablePostQuantumCryptography: Boolean = false
    @JvmField var upstreamTimeoutMs: Long = 5000

    // Backward compatibility fields
    @JvmField var upstreamSettings: List<Any> = ArrayList()
    @JvmField var isDetectSearchDomains: Boolean = false
    @JvmField var isIpv6Available: Boolean = true
    @JvmField var blockMode: Int = 0
}
