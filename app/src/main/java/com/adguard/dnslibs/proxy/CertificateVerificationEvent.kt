package com.adguard.dnslibs.proxy

import java.security.cert.X509Certificate

class CertificateVerificationEvent {
    @JvmField var certificate: X509Certificate? = null
    @JvmField var domain: String? = null
}
