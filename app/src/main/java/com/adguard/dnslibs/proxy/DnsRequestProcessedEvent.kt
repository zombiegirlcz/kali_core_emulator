package com.adguard.dnslibs.proxy

import java.util.ArrayList

class DnsRequestProcessedEvent {
    @JvmField var domain: String? = null
    @JvmField var type: String? = null
    @JvmField var startTime: Long = 0
    @JvmField var elapsed: Int = 0
    @JvmField var status: String? = null
    @JvmField var answer: String? = null
    @JvmField var originalAnswer: String? = null
    @JvmField var upstreamId: java.lang.Integer? = null
    @JvmField var bytesSent: Int = 0
    @JvmField var bytesReceived: Int = 0
    @JvmField var rules: List<String>? = ArrayList()
    @JvmField var filterListIds: IntArray? = null
    @JvmField var whitelist: Boolean = false
    @JvmField var error: String? = null

    // Backward compatibility / extra fields just in case
    @JvmField var filterId: Int = 0
    @JvmField var rule: String? = null
    @JvmField var cached: Boolean = false
    @JvmField var dnssec: Boolean = false
    @JvmField var upstream: String? = null
}
