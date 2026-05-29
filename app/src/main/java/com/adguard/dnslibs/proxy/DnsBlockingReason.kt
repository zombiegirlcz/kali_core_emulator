package com.adguard.dnslibs.proxy

enum class DnsBlockingReason {
    NONE,
    FILTERED,
    BLOCKED_BY_FILTER,
    CUSTOM,
    BLOCKED_BY_HOSTS,
    BLOCKED_BY_RESPONSE
}
