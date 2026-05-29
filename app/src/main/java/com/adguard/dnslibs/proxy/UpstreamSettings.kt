package com.adguard.dnslibs.proxy

import java.util.ArrayList

class UpstreamSettings {
    @JvmField var address: String? = null
    @JvmField var bootstrap: List<String>? = ArrayList()
    @JvmField var serverIp: ByteArray? = null
    @JvmField var id: Int = 0
    @JvmField var outboundInterfaceName: String? = null
    @JvmField var fingerprints: List<String>? = ArrayList()

    constructor()

    constructor(address: String?, bootstrap: List<String>?, serverIp: ByteArray?, id: Int) {
        this.address = address
        this.bootstrap = bootstrap
        this.serverIp = serverIp
        this.id = id
    }
}

class FilteringLogAction {
    class RuleTemplate
    enum class Option(val value: Int) {
        NONE(0)
    }
}

class DnsProxyInitException(initResult: DnsProxy.InitResult) : RuntimeException(initResult.description) {
    @JvmField val code: DnsProxy.InitErrorCode = initResult.code

    fun getCode(): DnsProxy.InitErrorCode = code
}
