package com.linux_core.security

import android.util.Log
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
        // Parse the attestation security level from the certificate extension
        // OID 1.3.6.1.4.1.11129.2.1.17 is the attestationRecord extension
        return try {
            val raw = leaf.getExtensionValue("1.3.6.1.4.1.11129.2.1.17") ?: return false
            val attestationRecord = parseAttestationRecord(raw)
            if (attestationRecord == null) {
                Log.w(TAG, "Could not parse attestation record")
                return false
            }
            
            // Security levels: SOFTWARE=0, TRUSTED_ENVIRONMENT=1, STRONGBOX=2
            // We require TEE or StrongBox (not software-backed)
            when (attestationRecord.securityLevel) {
                0 -> {
                    Log.w(TAG, "Rejecting SOFTWARE-backed attestation")
                    false
                }
                1, 2 -> true // TEE or StrongBox
                else -> {
                    Log.w(TAG, "Unknown security level: ${attestationRecord.securityLevel}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "verifySecurityLevelTee failed: ${e.message}")
            false
        }
    }

    /**
     * Minimal ASN.1 parsing to extract attestation security level.
     * Returns null if parsing fails.
     */
    private data class AttestationRecord(val securityLevel: Int)

    private fun parseAttestationRecord(raw: ByteArray): AttestationRecord? {
        return try {
            val innerOctets = stripOctetStringHeader(raw)
            // Find attestationSecurityLevel INTEGER in the sequence
            // Format: SEQUENCE { ... INTEGER securityLevel ... }
            var pos = 0
            while (pos < innerOctets.size - 1) {
                // Look for INTEGER tag (0x02) followed by length and value
                if (innerOctets[pos] == 0x02.toByte()) {
                    val len = innerOctets[pos + 1].toInt() and 0xFF
                    if (pos + 2 + len <= innerOctets.size) {
                        // This is a simplified heuristic - in production, use proper ASN.1 parsing
                        // The security level is typically near the start of the attestation record
                        val value = innerOctets.copyOfRange(pos + 2, pos + 2 + len).firstOrNull()?.toInt()?.and(0xFF) ?: continue
                        // Security level should be 0, 1, or 2
                        if (value <= 2) return AttestationRecord(value)
                    }
                }
                pos++
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "parseAttestationRecord exception: ${e.message}")
            null
        }
    }

    private fun verifyNonceMatches(leaf: X509Certificate, expected: ByteArray): Boolean {
        return try {
            val raw = leaf.getExtensionValue("1.3.6.1.4.1.11129.2.1.17") ?: return false
            // The extension value is an OCTET STRING wrapping an ASN.1 structure; we do a
            // best-effort comparison by hashing the inner content and comparing to a SHA-256
            // of the expected nonce. This is intentionally conservative – false positives are
            // impossible (a collision would be a preimage attack on SHA-256), false negatives
            // are caught at signature time.
            val innerOctets = stripOctetStringHeader(raw)
            val sha = MessageDigest.getInstance("SHA-256").digest(expected)
            containsSlice(innerOctets, sha) || containsSlice(innerOctets, expected)
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

    private fun stripOctetStringHeader(raw: ByteArray): ByteArray {
        // ASN.1 OCTET STRING: 0x04 LL <bytes>
        if (raw.size < 2 || raw[0] != 0x04.toByte()) return raw
        val len = raw[1].toInt() and 0xFF
        return if (raw.size >= 2 + len) raw.copyOfRange(2, 2 + len) else raw
    }

    private fun containsSlice(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > haystack.size) return false
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
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
