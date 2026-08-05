package com.adguard.corelibs.network

class OutboundProxyConfig {
    @JvmField var host: String? = null
    @JvmField var port: Int = 0
    @JvmField var protocol: Int = 0
    @JvmField var username: String? = null
    @JvmField var password: String? = null
}
