package com.adguard.dnslibs.proxy

interface DnsProxyEvents {
    fun onRequestProcessed(event: DnsRequestProcessedEvent)
    fun onCertificateVerification(event: CertificateVerificationEvent): String? = null
}
