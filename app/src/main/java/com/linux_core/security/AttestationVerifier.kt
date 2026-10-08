package com.linux_core.security

import android.util.Log
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertPath
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Verifies an attestation certificate chain produced by the Android key-store.
 *
 *  - Validates the chain against the Google Hardware Attestation Root CA loaded
 *    from `assets/certs/google_attestation_root.der`. (If the file is missing, the
 *    chain check is skipped and only the leaf contents + signature are checked –
 *    sufficient to enforce the "TEE/StrongBox" requirement locally.)
 *  - Parses the `attestationSecurityLevel` field from the attestation extension to
 *    reject SOFTWARE-bound keys.
 *  - Verifies the [expectedNonce] matches the `attestationChallenge` extension on
 *    the leaf certificate.
 *  - Verifies the leaf certificate was issued within the last [MAX_AGE_MILLIS].
 *  - Performs a [Signature] verification of the [signature] over (nonce || data)
 *    using the public key of the leaf certificate.
 */
object AttestationVerifier {

    private const val TAG = "AttestationVerifier"
    private const val MAX_AGE_MILLIS = 60L * 1000  // 60 s replay window
    private const val ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"

    internal const val SECURITY_LEVEL_SOFTWARE = 0
    internal const val SECURITY_LEVEL_TEE = 1
    internal const val SECURITY_LEVEL_STRONGBOX = 2

    private val rootCert: X509Certificate? by lazy { loadRootCert() }

    fun verify(
        chain: Array<X509Certificate>,
        expectedNonce: ByteArray,
        data: ByteArray,
        signature: ByteArray,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (chain.isEmpty()) {
            Log.w(TAG, "verify: empty chain"); return false
        }
        val leaf = chain[0]

        if (!verifyFreshness(leaf, now)) {
            Log.w(TAG, "verify: cert too old or not yet valid"); return false
        }

        if (rootCert != null) {
            try {
                val cf = CertificateFactory.getInstance("X.509")
                val cp: CertPath = cf.generateCertPath(chain.toList())
                val anchor = TrustAnchor(rootCert, null)
                val params = PKIXParameters(setOf(anchor))
                // Note: Revocation checking requires network access for OCSP/CRL
                // We enable it but catch network failures gracefully
                params.isRevocationEnabled = true
                try {
                    params.date = Date(now)
                } catch (_: Exception) { /* Some systems don't support this */ }
                val validator = java.security.cert.CertPathValidator.getInstance("PKIX")
                validator.validate(cp, params)
            } catch (e: Exception) {
                Log.w(TAG, "verify: chain validation failed: ${e.message}")
                return false
            }
        } else {
            // No Google root cert available - this is mandatory when attestation is enabled
            Log.e(TAG, "Google attestation root certificate missing - verification cannot proceed")
            return false
        }

        if (!verifySecurityLevelTee(leaf)) {
            Log.w(TAG, "verify: leaf is not TEE-backed"); return false
        }

        if (!verifyNonceMatches(leaf, expectedNonce)) {
            Log.w(TAG, "verify: nonce mismatch"); return false
        }

        if (!verifySignature(leaf, expectedNonce, data, signature)) {
            Log.w(TAG, "verify: signature invalid"); return false
        }

        return true
    }

    private fun verifyFreshness(leaf: X509Certificate, now: Long): Boolean {
        val notBefore = leaf.notBefore.time
        return now in (notBefore - 5_000)..(notBefore + MAX_AGE_MILLIS)
    }

    private fun verifySecurityLevelTee(leaf: X509Certificate): Boolean {
        // OID 1.3.6.1.4.1.11129.2.1.17 = KeyDescription (attestation record)
        return try {
            val raw = leaf.getExtensionValue(ATTESTATION_OID) ?: return false
            val desc = parseKeyDescription(raw)
            if (desc == null) {
                Log.w(TAG, "Could not parse attestation record")
                return false
            }
            isHardwareBacked(desc.attestationSecurityLevel)
        } catch (e: Exception) {
            Log.w(TAG, "verifySecurityLevelTee failed: ${e.message}")
            false
        }
    }

    /** SecurityLevel: SOFTWARE=0, TRUSTED_ENVIRONMENT=1, STRONGBOX=2 — vyžadujeme TEE/StrongBox. */
    internal fun isHardwareBacked(securityLevel: Int): Boolean = when (securityLevel) {
        SECURITY_LEVEL_SOFTWARE -> {
            Log.w(TAG, "Rejecting SOFTWARE-backed attestation")
            false
        }
        SECURITY_LEVEL_TEE, SECURITY_LEVEL_STRONGBOX -> true
        else -> {
            Log.w(TAG, "Unknown security level: $securityLevel")
            false
        }
    }

    /**
     * Položky KeyDescription, které ověřujeme. Strukturované ASN.1 parsování (BouncyCastle),
     * ne heuristika hledající bajt 0x02 — `attestationSecurityLevel` je ENUMERATED (0x0A)
     * na pevné pozici 1 v SEQUENCE.
     */
    internal class KeyDescription(
        val attestationVersion: Int,
        val attestationSecurityLevel: Int,
        val keymasterSecurityLevel: Int,
        val attestationChallenge: ByteArray
    )

    /**
     * Parsuje hodnotu extension 1.3.6.1.4.1.11129.2.1.17, jak ji vrací
     * [X509Certificate.getExtensionValue] (tj. DER OCTET STRING obalující KeyDescription):
     *
     * ```
     * KeyDescription ::= SEQUENCE {
     *   attestationVersion        INTEGER,
     *   attestationSecurityLevel  SecurityLevel,   -- ENUMERATED
     *   keymasterVersion          INTEGER,
     *   keymasterSecurityLevel    SecurityLevel,   -- ENUMERATED
     *   attestationChallenge      OCTET STRING,
     *   uniqueId                  OCTET STRING,
     *   softwareEnforced          AuthorizationList,
     *   teeEnforced               AuthorizationList }
     * ```
     * DER délky (short i long form) řeší BouncyCastle. Vrací null při jakékoli odchylce.
     */
    internal fun parseKeyDescription(extensionValue: ByteArray): KeyDescription? {
        return try {
            val octets = ASN1OctetString.getInstance(ASN1Primitive.fromByteArray(extensionValue)).octets
            val seq = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(octets))
            if (seq.size() < 6) return null
            KeyDescription(
                attestationVersion = ASN1Integer.getInstance(seq.getObjectAt(0)).intValueExact(),
                attestationSecurityLevel = ASN1Enumerated.getInstance(seq.getObjectAt(1)).intValueExact(),
                keymasterSecurityLevel = ASN1Enumerated.getInstance(seq.getObjectAt(3)).intValueExact(),
                attestationChallenge = ASN1OctetString.getInstance(seq.getObjectAt(4)).octets
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseKeyDescription failed: ${e.message}")
            null
        }
    }

    private fun verifyNonceMatches(leaf: X509Certificate, expected: ByteArray): Boolean {
        return try {
            val raw = leaf.getExtensionValue(ATTESTATION_OID) ?: return false
            val challenge = parseKeyDescription(raw)?.attestationChallenge ?: return false
            // attestationChallenge musí být přesně nonce, nebo jeho SHA-256 (ne jen
            // "někde v záznamu" jako dřív) — porovnání v konstantním čase.
            val sha = MessageDigest.getInstance("SHA-256").digest(expected)
            MessageDigest.isEqual(challenge, expected) || MessageDigest.isEqual(challenge, sha)
        } catch (t: Throwable) {
            Log.w(TAG, "verifyNonceMatches parse failed: ${t.message}")
            false
        }
    }

    private fun verifySignature(
        leaf: X509Certificate,
        nonce: ByteArray,
        data: ByteArray,
        signature: ByteArray
    ): Boolean = try {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(leaf.publicKey)
        sig.update(nonce)
        sig.update(data)
        sig.verify(signature)
    } catch (t: Throwable) {
        Log.w(TAG, "verifySignature failed: ${t.message}")
        false
    }

    private fun loadRootCert(): X509Certificate? = try {
        val stream = AttestationVerifier::class.java.classLoader
            ?.getResourceAsStream("certs/google_attestation_root.der")
            ?: return null
        stream.use {
            val cf = CertificateFactory.getInstance("X.509")
            cf.generateCertificate(it) as X509Certificate
        }
    } catch (t: Throwable) {
        null
    }
}
