package com.adguard.dnslibs.proxy

class DnsRequestProcessedEvent {
    var domain: String? = null
    var type: String? = null
    var answer: String? = null
    var filterId: Int = 0
    var rule: String? = null
}
