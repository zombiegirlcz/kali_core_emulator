package com.adguard.dnslibs.proxy

interface DnsProxyEvents {
    fun onRequestProcessed(event: DnsRequestProcessedEvent)
}
