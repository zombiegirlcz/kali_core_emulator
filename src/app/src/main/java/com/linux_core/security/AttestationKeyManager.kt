package com.linux_core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.InvalidAlgorithmParameterException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/** Manages the device-attestation key pair stored in the AndroidKeyStore.
 *
 * Two aliases are created lazily on first use:
 *  - `attest_ec`     – EC P-256 signing key, StrongBox if available, TEE fallback.
 *  - `attest_secret` – AES-256 GCM key for symmetric encryption (no TEE-bound cipher
 *                       available on all SoCs, so the AES key itself is H/W-backed).
 *
 * Both keys require user authentication (biometric or device credential) and are
 * usable for 30 seconds after a successful authentication.
 *
 * SECURITY: This class now validates that cryptoObject uses the expected algorithm
 * to prevent algorithm substitution attacks.
 */
class AttestationKeyManager(private val context: Context) {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun ensureSigningKey(): Result<PrivateKey> = runCatching {
        if (keyStore.containsAlias(ALIAS_SIGN)) {
            keyStore.getKey(ALIAS_SIGN, null) as PrivateKey
        } else {
            generateSigningKey()
        }
    }.onFailure { Log.w(TAG, "ensureSigningKey: ${it.message}") }

    fun ensureEncryptionKey(): Result<javax.crypto.SecretKey> = runCatching {
        if (keyStore.containsAlias(ALIAS_SECRET)) {
            keyStore.getKey(ALIAS_SECRET, null) as javax.crypto.SecretKey
        } else {
            generateEncryptionKey()
        }
    }.onFailure { Log.w(TAG, "ensureEncryptionKey: ${it.message}") }

    fun signingPublicKey(): PublicKey? = runCatching {
        val cert = keyStore.getCertificate(ALIAS_SIGN) ?: return@runCatching null
        cert.publicKey
    }.getOrNull()

    fun signingCertificateChain(): Array<X509Certificate>? = runCatching {
        val chain = keyStore.getCertificateChain(ALIAS_SIGN) ?: return@runCatching null
        chain.filterIsInstance<X509Certificate>().toTypedArray()
    }.getOrNull()

    fun sign(nonce: ByteArray, data: ByteArray, cryptoObject: Any? = null): Result<ByteArray> = runCatching {
        val pk = ensureSigningKey().getOrThrow()
        val sig = when (cryptoObject) {
            null -> Signature.getInstance(SIG_ALGO)
            is Signature -> {
                // Validate algorithm to prevent substitution attacks
                if (cryptoObject.algorithm != SIG_ALGO) {
                    Log.w(TAG, "CryptoObject algorithm mismatch: expected $SIG_ALGO, got ${cryptoObject.algorithm}")
                    throw IllegalArgumentException("Invalid signature algorithm: ${cryptoObject.algorithm}")
                }
                cryptoObject
            }
            else -> throw IllegalArgumentException("Invalid crypto object type")
        }
        sig.initSign(pk)
        sig.update(nonce)
        sig.update(data)
        sig.sign()
    }.onFailure { Log.w(TAG, "sign failed: ${it.message}") }

    fun verify(
        chain: Array<X509Certificate>,
        expectedNonce: ByteArray,
        data: ByteArray,
        signature: ByteArray
    ): Result<Boolean> = runCatching {
        AttestationVerifier.verify(chain, expectedNonce, data, signature)
    }

    fun cipherForEncrypt(cipher: Cipher? = null): Result<Cipher> = runCatching {
        val key = ensureEncryptionKey().getOrThrow()
        val c = cipher ?: Cipher.getInstance(ENCRYPTION_ALGO)
        c.init(Cipher.ENCRYPT_MODE, key)
        c
    }

    fun cipherForDecrypt(iv: ByteArray, cipher: Cipher? = null): Result<Cipher> = runCatching {
        val key = ensureEncryptionKey().getOrThrow()
        val c = cipher ?: Cipher.getInstance(ENCRYPTION_ALGO)
        val spec = javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv)
        c.init(Cipher.DECRYPT_MODE, key, spec)
        c
    }

    /**
     * Best-effort inspection of where the key is bound (TEE / StrongBox / Software).
     * Used for diagnostics only; verification of the attestationSecurityLevel is done
     * in [AttestationVerifier] by parsing the attestation cert extension.
     */
    fun securityLevel(): String {
        return try {
            val privateKey = ensureSigningKey().getOrNull() ?: return "unknown"
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                // KeyInfo.securityLevel added in API 31. Assume TEE.
                return "tee"
            }
            val kFactory = java.security.KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
            val ki: KeyInfo = kFactory.getKeySpec(privateKey, KeyInfo::class.java)
            // KeyProperties constants are only available on API 31+; use int literals.
            when (ki.securityLevel) {
                2 -> "strongbox"        // KeyProperties.STRONGBOX_SECURITY
                1 -> "tee"              // KeyProperties.TRUSTED_ENVIRONMENT_SECURITY
                0 -> "software"         // KeyProperties.SOFTWARE_SECURITY
                else -> "unknown"
            }
        } catch (t: Throwable) {
            Log.w(TAG, "securityLevel probe failed: ${t.message}")
            "unknown"
        }
    }

    private fun generateSigningKey(): PrivateKey {
        val purposes = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        val builder = KeyGenParameterSpec.Builder(ALIAS_SIGN, purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
            .setAttestationChallenge(nonce32())

        return try {
            // Try StrongBox first.
            val kg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            kg.initialize(builder.setIsStrongBoxBacked(true).build())
            kg.generateKeyPair().private
        } catch (e: StrongBoxUnavailableException) {
            Log.w(TAG, "StrongBox unavailable, falling back to TEE: ${e.message}")
            val kg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            kg.initialize(builder.setIsStrongBoxBacked(false).build())
            kg.generateKeyPair().private
        } catch (e: InvalidAlgorithmParameterException) {
            // Some devices reject the combined auth-parameter set with auth required – retry
            // without the explicit per-op auth window.
            Log.w(TAG, "Falling back to TEE without per-op window: ${e.message}")
            val kg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            kg.initialize(
                KeyGenParameterSpec.Builder(ALIAS_SIGN, purposes)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .setAttestationChallenge(nonce32())
                    .build()
            )
            kg.generateKeyPair().private
        }
    }

    private fun generateEncryptionKey(): javax.crypto.SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            ALIAS_SECRET,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )

        return try {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            kg.init(builder.setIsStrongBoxBacked(true).build())
            kg.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            kg.init(builder.setIsStrongBoxBacked(false).build())
            kg.generateKey()
        }
    }

    private fun nonce32(): ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

    companion object {
        private const val TAG = "AttestationKeyManager"
        const val ALIAS_SIGN = "attest_ec"
        const val ALIAS_SECRET = "attest_secret"
        const val SIG_ALGO = "SHA256withECDSA"
        const val ENCRYPTION_ALGO = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val AUTH_VALIDITY_SECONDS = 30
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
