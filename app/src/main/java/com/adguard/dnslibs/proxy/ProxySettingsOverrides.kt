package com.adguard.dnslibs.proxy

class ProxySettingsOverrides {
    @JvmField var blockEch: Boolean? = null
    @JvmField var blockH3Alpn: Boolean? = null
    @JvmField var upstreamTimeoutMs: Long? = null
    @JvmField var fallbackTimeoutMs: Long? = null
}
