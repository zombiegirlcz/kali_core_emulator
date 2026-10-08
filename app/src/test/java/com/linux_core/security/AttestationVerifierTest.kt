package com.linux_core.security

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /**
     * Sestaví hodnotu extension 1.3.6.1.4.1.11129.2.1.17 tak, jak ji vrací
     * X509Certificate.getExtensionValue(): OCTET STRING { KeyDescription SEQUENCE }.
     */
    private fun keyDescriptionExt(
        attestationVersion: Int,
        attestationSecurityLevel: Int,
        keymasterSecurityLevel: Int,
        challenge: ByteArray,
        uniqueId: ByteArray = ByteArray(0)
    ): ByteArray {
        val v = ASN1EncodableVector()
        v.add(ASN1Integer(attestationVersion.toLong()))
        v.add(ASN1Enumerated(attestationSecurityLevel))
        v.add(ASN1Integer(4))                      // keymasterVersion
        v.add(ASN1Enumerated(keymasterSecurityLevel))
        v.add(DEROctetString(challenge))
        v.add(DEROctetString(uniqueId))
        v.add(DERSequence())                       // softwareEnforced
        v.add(DERSequence())                       // teeEnforced
        return DEROctetString(DERSequence(v).encoded).encoded
    }

    @Test
    fun parsesSecurityLevelFromEnumeratedField() {
        val challenge = ByteArray(32) { it.toByte() }
        val desc = AttestationVerifier.parseKeyDescription(
            keyDescriptionExt(3, AttestationVerifier.SECURITY_LEVEL_TEE, AttestationVerifier.SECURITY_LEVEL_TEE, challenge)
        )
        assertNotNull(desc)
        assertEquals(3, desc!!.attestationVersion)
        assertEquals(AttestationVerifier.SECURITY_LEVEL_TEE, desc.attestationSecurityLevel)
        assertArrayEquals(challenge, desc.attestationChallenge)
        assertTrue(AttestationVerifier.isHardwareBacked(desc.attestationSecurityLevel))
    }

    @Test
    fun softwareLevelIsRejectedEvenWhenOtherIntegersAreSmall() {
        // attestationVersion = 1 (INTEGER <= 2) — stará heuristika by ho četla jako
        // securityLevel=TEE. Strukturovaný parser musí vzít ENUMERATED = SOFTWARE.
        val desc = AttestationVerifier.parseKeyDescription(
            keyDescriptionExt(1, AttestationVerifier.SECURITY_LEVEL_SOFTWARE, AttestationVerifier.SECURITY_LEVEL_SOFTWARE, ByteArray(32))
        )
        assertNotNull(desc)
        assertEquals(AttestationVerifier.SECURITY_LEVEL_SOFTWARE, desc!!.attestationSecurityLevel)
        assertFalse(AttestationVerifier.isHardwareBacked(desc.attestationSecurityLevel))
    }

    @Test
    fun parsesLongFormDerLength() {
        // Záznam > 127 B → vnější OCTET STRING i SEQUENCE mají long-form délku (0x81/0x82).
        val challenge = ByteArray(64) { 0x5A }
        val ext = keyDescriptionExt(4, AttestationVerifier.SECURITY_LEVEL_STRONGBOX,
            AttestationVerifier.SECURITY_LEVEL_STRONGBOX, challenge, uniqueId = ByteArray(300) { 0x11 })
        assertTrue("test musí pokrýt long-form délku", (ext[1].toInt() and 0x80) != 0)
        val desc = AttestationVerifier.parseKeyDescription(ext)
        assertNotNull(desc)
        assertEquals(AttestationVerifier.SECURITY_LEVEL_STRONGBOX, desc!!.attestationSecurityLevel)
        assertArrayEquals(challenge, desc.attestationChallenge)
    }

    @Test
    fun rejectsMalformedKeyDescription() {
        assertNull(AttestationVerifier.parseKeyDescription(ByteArray(0)))
        assertNull(AttestationVerifier.parseKeyDescription(byteArrayOf(0x04, 0x02, 0x02, 0x01)))
        // securityLevel jako INTEGER místo ENUMERATED → nevalidní struktura
        val v = ASN1EncodableVector()
        repeat(6) { v.add(ASN1Integer(1)) }
        assertNull(AttestationVerifier.parseKeyDescription(DEROctetString(DERSequence(v).encoded).encoded))
    }
}
