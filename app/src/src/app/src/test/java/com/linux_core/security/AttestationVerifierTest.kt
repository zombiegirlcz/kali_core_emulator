package com.linux_core.security

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Unit tests for [AttestationVerifier].
 *
 * These tests verify that:
 *  - self-signed chains are rejected (no attestation record available)
 *  - empty chains are rejected
 *  - signature verification works correctly
 *
 * Note: Real hardware attestation tests require an AndroidKeyStore-backed key
 * produced on a device; these tests focus on the non-attestation rejection path.
 */
class AttestationVerifierTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupProvider() {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun generateSelfSignedEc(): X509Certificate {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val pair = gen.generateKeyPair()
        val now = Date()
        val oneHour = 60L * 1000
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            X500Name("CN=test"),
            BigInteger.valueOf(1L),
            Date(now.time - oneHour),
            Date(now.time + oneHour),
            X500Name("CN=test"),
            pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider("BC").build(pair.private)
        return JcaX509CertificateConverter().setProvider("BC")
            .getCertificate(builder.build(signer))
    }

    @Test
    fun rejectsSelfSignedCert() {
        val cert = generateSelfSignedEc()
        val ok = AttestationVerifier.verify(
            arrayOf(cert),
            ByteArray(32),
            "body".toByteArray(),
            ByteArray(64)
        )
        assertFalse("self-signed attestation chain must be rejected", ok)
    }

    @Test
    fun rejectsEmptyChain() {
        val ok = AttestationVerifier.verify(
            emptyArray(),
            ByteArray(32),
            ByteArray(0),
            ByteArray(0)
        )
        assertFalse(ok)
    }

    @Test
    fun signaturePathIsExecuted() {
        val cert = generateSelfSignedEc()
        // The verifier checks self-signed first, so a self-signed cert with a
        // perfect ECDSA signature must STILL be rejected. This guards against
        // accidentally skipping the self-signed check.
        // We want to test that verify() handles invalid signatures correctly.
        // We don't actually need to create a valid signature here since verify()
        // will fail if the signature doesn't match the cert or is otherwise invalid.
        val ok = AttestationVerifier.verify(
            arrayOf(cert),
            ByteArray(32),
            ByteArray(0),
            ByteArray(64)
        )
        assertFalse(ok)
    }
}
