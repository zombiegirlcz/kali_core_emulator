package com.adguard.dnslibs.proxy

class CertificateVerificationEvent {
    @JvmField var certificate: ByteArray? = null
    @JvmField var chain: List<ByteArray>? = null
    @JvmField var domain: String? = null

    fun getCertificate(): ByteArray? {
        return certificate
    }

    fun getChain(): List<ByteArray>? {
        return chain
    }
}
