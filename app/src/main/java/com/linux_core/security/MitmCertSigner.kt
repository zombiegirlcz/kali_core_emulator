package com.linux_core.security

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Helper to re-sign captured server certificates with the MITM CA so the Android
 * TLS stack accepts them when inspecting tunneled traffic.
 *
 * Uses BouncyCastle; only called from [RootCaInstaller] which is gated by
 * BuildConfig.ENABLE_MITM.
 */
internal object MitmCertSigner {

    private const val SIGNER_ALGO = "SHA256WithRSA"

    fun sign(
        caCert: X509Certificate,
        caPrivateKey: PrivateKey,
        template: X509Certificate,
        serial: Long
    ): X509Certificate {
        val issuer: X500Name = X500Name.getInstance(caCert.subjectX500Principal.encoded)
        val now = Date()
        val oneDay = 1000L * 60 * 60 * 24
        val notBefore = Date(now.time - oneDay)
        val notAfter = Date(now.time + oneDay * 30)

        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            caCert,
            BigInteger.valueOf(serial),
            notBefore,
            notAfter,
            template.subjectX500Principal.let { X500Name.getInstance(it.encoded) },
            template.publicKey
        )

        val signer: ContentSigner = JcaContentSignerBuilder(SIGNER_ALGO)
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            .build(caPrivateKey)

        val holder = builder.build(signer)
        return JcaX509CertificateConverter()
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            .getCertificate(holder)
    }
}
