package com.adguard.dnslibs.proxy

import java.util.ArrayList

class OutboundProxySettings {
    enum class Protocol {
        HTTP_CONNECT,
        HTTPS_CONNECT,
        SOCKS4,
        SOCKS5,
        SOCKS5_UDP
    }

    class AuthInfo(
        @JvmField val username: String,
        @JvmField val password: String
    )

    @JvmField var protocol: Protocol? = null
    @JvmField var address: String? = null
    @JvmField var port: Int = 0
    @JvmField var bootstrap: List<String>? = ArrayList()
    @JvmField var serverIp: ByteArray? = null
    @JvmField var authInfo: AuthInfo? = null
    @JvmField var trustAnyCertificate: Boolean = false
    @JvmField var ignoreIfUnavailable: Boolean = false
}
