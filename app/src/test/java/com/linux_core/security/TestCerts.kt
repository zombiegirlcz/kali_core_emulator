package com.linux_core.security

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Tiny helper that produces self-signed certificates for unit tests. Using BC
 * keeps the test self-contained (no AndroidKeyStore, no Robolectric).
 */
object TestCerts {

    data class CertKey(val cert: X509Certificate, val private: PrivateKey, val public: KeyPair)

    fun selfSigned(
        subject: String,
        notBeforeOffsetMs: Long = -60_000L,
        notAfterOffsetMs: Long = 60_000L * 60 * 24 * 365
    ): CertKey {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()
        val now = Date()
        val builder = JcaX509v3CertificateBuilder(
            X500Name(subject),
            BigInteger.valueOf(System.nanoTime()),
            Date(now.time + notBeforeOffsetMs),
            Date(now.time + notAfterOffsetMs),
            X500Name(subject),
            pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA").setProvider("BC").build(pair.private)
        val cert = JcaX509CertificateConverter().setProvider("BC")
            .getCertificate(builder.build(signer))
        return CertKey(cert, pair.private, pair)
    }
}
