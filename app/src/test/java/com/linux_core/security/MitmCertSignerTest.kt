package com.linux_core.security

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

/**
 * Smoke tests for [MitmCertSigner] that do not require the device AndroidKeyStore.
 * Verifies the cert produced is parseable and carries the issuer of the supplied CA.
 */
class MitmCertSignerTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupProvider() {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun signProducesValidCert() {
        val ca = TestCerts.selfSigned(
            "CN=NetHunter Test CA",
            notBeforeOffsetMs = -60_000L,
            notAfterOffsetMs = 60_000L * 60 * 24 * 365
        )
        val leaf = TestCerts.selfSigned(
            "CN=leaf.example.com",
            notBeforeOffsetMs = -60_000L,
            notAfterOffsetMs = 60_000L * 60 * 24 * 30
        )
        val signed = MitmCertSigner.sign(ca.cert, ca.private, leaf.cert, 42L)
        assertEquals("CN=NetHunter Test CA", signed.issuerX500Principal.name)
        assertTrue("signed cert must encode to non-empty bytes", signed.encoded.isNotEmpty())
    }
}
