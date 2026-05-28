package com.adguard.dnslibs.proxy

class UpstreamSettings

class FilteringLogAction {
    class RuleTemplate
    enum class Option(val value: Int) {
        NONE(0)
    }
}

class DnsProxyInitException(val initResult: DnsProxy.InitResult) : Exception("DNS Proxy initialization failed")
