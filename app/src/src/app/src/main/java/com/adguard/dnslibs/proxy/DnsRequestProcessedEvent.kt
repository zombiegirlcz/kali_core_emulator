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
    @JvmField var filterId: Int = 0
    @JvmField var rule: String? = null
    @JvmField var cached: Boolean = false
    @JvmField var dnssec: Boolean = false
    @JvmField var upstream: String? = null
    @JvmField var time: String? = null
    @JvmField var clientIp: String? = null
    @JvmField var clientProto: String? = null
    @JvmField var responseCode: String? = null
    @JvmField var cacheHit: Boolean = false
    @JvmField var dnssecResult: String? = null
    @JvmField var blocklistId: Int = 0
    @JvmField var filterName: String? = null
    @JvmField var ruleText: String? = null
    @JvmField var action: Int = 0
    @JvmField var upstreamAddress: String? = null
    @JvmField var connectionTimeMs: Long = 0
    @JvmField var ecs: String? = null
    @JvmField var filteringAction: Any? = null
    @JvmField var certificate: String? = null
    @JvmField var rcode: Int = 0
    @JvmField var serverIp: String? = null
    @JvmField var blockingReason: DnsBlockingReason? = null
}
