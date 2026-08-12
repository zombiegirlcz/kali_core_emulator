package com.adguard.dnslibs.proxy

class ListenerSettings {
    enum class Protocol {
        UDP,
        TCP,
        TLS,
        HTTPS,
        QUIC
    }

    @JvmField var address: String? = null
    @JvmField var port: Int = 0
    @JvmField var protocol: Protocol? = null
    @JvmField var persistent: Boolean = false
    @JvmField var idleTimeoutMs: Long = 0
    @JvmField var settingsOverrides: ProxySettingsOverrides? = null
}
