package com.linux_core.security

import org.bouncycastle.asn1.x500.X500Name
import java.math.BigInteger
import java.net.InetAddress
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.regex.Pattern

/**
 * Helper to re-sign captured server certificates with the MITM CA so the Android
 * TLS stack accepts them when inspecting tunneled traffic.
 *
 * Uses BouncyCastle; only called from [RootCaInstaller] which is gated by
 * BuildConfig.ENABLE_MITM.
 */
internal object MitmCertSigner {

    fun signWithPublicKey(
        caCert: X509Certificate,
        caPrivateKey: PrivateKey,
        subjectPublicKey: java.security.PublicKey,
        serial: Long,
        cn: String,
        sanDns: List<String> = emptyList()
    ): X509Certificate {
        val issuer = X500Name.getInstance(caCert.subjectX500Principal.encoded)
        val subject = X500Name("CN=$cn")
        val now = Date()
        val oneDay = 1000L * 60 * 60 * 24
        val notBefore = Date(now.time - oneDay)
        val notAfter = Date(now.time + oneDay * 30)

        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            caCert, randomSerial(serial), notBefore, notAfter, subject, subjectPublicKey
        )

        sanDns.forEach { validateDnsName(it) }
        if (sanDns.isNotEmpty()) {
            val gnBuilder = org.bouncycastle.asn1.x509.GeneralNamesBuilder()
            sanDns.forEach { gnBuilder.addName(org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.dNSName, it)) }
            builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false, gnBuilder.build())
        }

        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(SIGNER_ALGO)
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            .build(caPrivateKey)
        val holder = builder.build(signer)
        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            .getCertificate(holder)
    }

    private const val SIGNER_ALGO = "SHA256WithRSA"

    private val serialRng = SecureRandom()

    /**
     * Sériové číslo certifikátu: 159 náhodných bitů z [SecureRandom] (kladné, max 20 oktetů
     * dle RFC 5280 §4.1.2.2). Volající historicky předávají `System.currentTimeMillis()` —
     * MITM sessiony běží souběžně, takže dva leafy ze stejné ms by měly stejný serial pod
     * stejným issuerem. Parametr [hint] se proto nepoužívá jako serial, jen zůstává kvůli
     * API kompatibilitě volajících.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun randomSerial(hint: Long): BigInteger {
        var s: BigInteger
        do { s = BigInteger(159, serialRng) } while (s.signum() == 0)
        return s
    }
    private const val MAX_HOSTNAME_LENGTH = 253
    private val HOSTNAME_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)*$"
    )

    private fun validateDnsName(name: String) {
        require(name.length <= MAX_HOSTNAME_LENGTH) {
            "DNS name exceeds max length ($MAX_HOSTNAME_LENGTH): $name"
        }
        require(HOSTNAME_PATTERN.matcher(name).matches()) {
            "Invalid DNS name: $name"
        }
    }

    private fun validateIpAddress(ip: String) {
        try {
            val addr = InetAddress.getByName(ip)
            require(addr is java.net.Inet4Address || addr is java.net.Inet6Address) {
                "Invalid IP address: $ip"
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid IP address: $ip", e)
        }
    }

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
            randomSerial(serial),
            notBefore,
            notAfter,
            subject,
            template.publicKey
        )

        sanDns.forEach { validateDnsName(it) }
        sanIp.forEach { validateIpAddress(it) }

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

    /**
     * Generate a self-signed X.509v3 CA certificate for [keyPair]
     * (basicConstraints CA:true, pathlen 0; keyUsage keyCertSign|cRLSign;
     * subjectKeyIdentifier). Used by [RootCaInstaller] to mint a device-local MITM
     * root when no CA keystore is bundled — the historical bundled dev key was
     * gitignored and therefore absent from every build.
     */
    fun createSelfSignedCa(
        keyPair: java.security.KeyPair,
        cn: String,
        validityDays: Long = 3650
    ): X509Certificate {
        val name = X500Name("CN=$cn, O=NetHunter-Dev, C=CZ")
        val now = Date()
        val oneDay = 1000L * 60 * 60 * 24
        val notBefore = Date(now.time - oneDay)
        val notAfter = Date(now.time + oneDay * validityDays)
        val serial = randomSerial(now.time)

        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            name, serial, notBefore, notAfter, name, keyPair.public
        )
        val extUtils = org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
            org.bouncycastle.asn1.x509.BasicConstraints(0)
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.keyUsage, true,
            org.bouncycastle.asn1.x509.KeyUsage(
                org.bouncycastle.asn1.x509.KeyUsage.keyCertSign or org.bouncycastle.asn1.x509.KeyUsage.cRLSign
            )
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectKeyIdentifier, false,
            extUtils.createSubjectKeyIdentifier(keyPair.public)
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(SIGNER_ALGO)
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            .build(keyPair.private)
        val holder = builder.build(signer)
        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            .getCertificate(holder)
    }
}
