package com.adguard.dnslibs.proxy

class DnsMessageInfo(
    @JvmField var transparent: Boolean = false,
    @JvmField var sourcePort: Int = 0
) {
    @JvmField var sourceAddress: String? = null
    @JvmField var socketFd: Int = 0
    @JvmField var protocol: Int = 0
}
