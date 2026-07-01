package com.linux_core.security

import org.bouncycastle.asn1.x500.X500Name
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
        serial: Long,
        sanDns: List<String> = emptyList(),
        sanIp: List<String> = emptyList()
    ): X509Certificate {
        val issuer: X500Name = X500Name.getInstance(caCert.subjectX500Principal.encoded)
        val subject = if (sanDns.isNotEmpty()) {
            X500Name("CN=${sanDns.first()}")
        } else {
            X500Name.getInstance(template.subjectX500Principal.encoded)
        }
        val now = Date()
        val oneDay = 1000L * 60 * 60 * 24
        val notBefore = Date(now.time - oneDay)
        val notAfter = Date(now.time + oneDay * 30)

        val builder: org.bouncycastle.cert.X509v3CertificateBuilder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            caCert,
            BigInteger.valueOf(serial),
            notBefore,
            notAfter,
            subject,
            template.publicKey
        )

        if (sanDns.isNotEmpty() || sanIp.isNotEmpty()) {
            val gnBuilder = org.bouncycastle.asn1.x509.GeneralNamesBuilder()
            sanDns.forEach { gnBuilder.addName(org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.dNSName, it)) }
            sanIp.forEach { gnBuilder.addName(org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.iPAddress, it)) }
            val generalNames = gnBuilder.build()
            builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false, generalNames)
        }

        val signer: org.bouncycastle.operator.ContentSigner = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(SIGNER_ALGO)
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            .build(caPrivateKey)

        val holder = builder.build(signer)
        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            .getCertificate(holder)
    }
}
