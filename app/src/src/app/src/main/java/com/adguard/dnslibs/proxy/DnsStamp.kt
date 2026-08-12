package com.adguard.dnslibs.proxy

class DnsStamp {
    enum class ProtoType {
        PLAIN_DNS,
        DNS_OVER_TLS,
        DNS_OVER_HTTPS,
        DNS_OVER_QUIC,
        DNSCRYPT
    }

    class InformalProperties {
        @JvmField var dnssec: Boolean = false
        @JvmField var noLog: Boolean = false
        @JvmField var noFilter: Boolean = false
    }

    @JvmField var protoType: ProtoType = ProtoType.PLAIN_DNS
    @JvmField var props: InformalProperties? = null
    @JvmField var serverAddress: String? = null
    @JvmField var providerName: String? = null
    @JvmField var path: String? = null
}
