package com.adguard.corelibs.proxy

class RequestProcessedEvent {
    class AppliedRules

    @JvmField var requestUrl: String? = null
    @JvmField var method: String? = null
    @JvmField var statusCode: Int = 0
    @JvmField var rules: List<Any>? = null
}
