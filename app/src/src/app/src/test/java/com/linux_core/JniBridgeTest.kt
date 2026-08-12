package com.linux_core

import com.adguard.dnslibs.proxy.CertificateVerificationEvent
import com.adguard.dnslibs.proxy.DnsProxySettings
import com.adguard.dnslibs.proxy.DnsRequestProcessedEvent
import com.adguard.dnslibs.proxy.UpstreamSettings
import com.adguard.corelibs.tcpip.ConnectionRequestResult
import com.adguard.corelibs.tcpip.TcpIpConnectionInfo
import com.adguard.corelibs.network.Protocol
import org.junit.Assert.*
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.io.ByteArrayInputStream
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore

class JniBridgeTest {

    @Test
    fun testCertificateVerificationEventJniProperties() {
        val certBytes = byteArrayOf(1, 2, 3)
        val chainList = listOf(certBytes, byteArrayOf(4, 5))
        val event = CertificateVerificationEvent()
        event.certificate = certBytes
        event.domain = "example.com"
        event.chain = chainList

        assertEquals(certBytes, event.certificate)
        assertEquals("example.com", event.domain)
        assertEquals(certBytes, event.getCertificate())
        assertEquals(chainList, event.chain)
        assertEquals(chainList, event.getChain())
    }

    @Test
    fun testConnectionRequestResultProperties() {
        val result = ConnectionRequestResult()
        assertNull(result.redirectAddress)
        assertFalse(result.isForceDirectConnection)
        
        val reject = ConnectionRequestResult.REJECT
        assertNotNull(reject)
    }

    @Test
    fun testProtocolEnum() {
        assertEquals(Protocol.TCP, Protocol.getByCode(Protocol.TCP.code))
        assertEquals(Protocol.UDP, Protocol.getByCode(Protocol.UDP.code))
        assertEquals(Protocol.UNKNOWN, Protocol.getByCode(999))
    }
}
