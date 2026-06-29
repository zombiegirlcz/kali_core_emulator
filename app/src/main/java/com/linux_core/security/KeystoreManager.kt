package com.linux_core.security

import android.util.Base64
import android.util.Log
import java.security.SecureRandom

/**
 * Wraps local secrets (UUID tokens, profile blobs) with the TEE-backed AES-GCM-256 key
 * from [AttestationKeyManager].
 *
 * Encrypted blob format (Base64-encoded when crossing JNI/HTTP boundary):
 *   [0..11]  : 12-byte IV
 *   [12..]   : ciphertext + GCM tag (16 bytes)
 */
class KeystoreManager {

    data class Encrypted(val iv: ByteArray, val payload: ByteArray) {
        fun toBase64(): String = Base64.encodeToString(iv + payload, Base64.NO_WRAP)
        override fun equals(other: Any?): Boolean = other is Encrypted && iv.contentEquals(other.iv) && payload.contentEquals(other.payload)
        override fun hashCode(): Int = 31 * iv.contentHashCode() + payload.contentHashCode()
    }

    fun encrypt(plaintext: ByteArray): Result<Encrypted> = runCatching {
        val att = CertificateManager.attestation()
            ?: throw IllegalStateException("Attestation disabled")
        val cipher = att.cipherForEncrypt().getOrThrow()
        val iv = cipher.iv
        require(iv.size == IV_SIZE) { "Unexpected IV size: ${iv.size}" }
        val ct = cipher.doFinal(plaintext)
        Encrypted(iv, ct)
    }.onFailure { Log.w(TAG, "encrypt failed: ${it.message}") }

    fun decrypt(blob: Encrypted): Result<ByteArray> = runCatching {
        val att = CertificateManager.attestation()
            ?: throw IllegalStateException("Attestation disabled")
        val cipher = att.cipherForDecrypt(blob.iv).getOrThrow()
        cipher.doFinal(blob.payload)
    }.onFailure { Log.w(TAG, "decrypt failed: ${it.message}") }

    fun encryptString(plain: String): Result<String> = encrypt(plain.toByteArray(Charsets.UTF_8))
        .map { it.toBase64() }

    fun decryptString(b64: String): Result<String> = runCatching {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        require(raw.size > IV_SIZE) { "blob too small" }
        val iv = raw.copyOfRange(0, IV_SIZE)
        val payload = raw.copyOfRange(IV_SIZE, raw.size)
        val pt = decrypt(Encrypted(iv, payload)).getOrThrow()
        String(pt, Charsets.UTF_8)
    }

    /** Helper used by [com.linux_core.core.LocalApiServer] to wrap/unwrap the api-security token. */
    fun rotateTokenIfNeeded(plain: String): Result<Encrypted> = encrypt(plain.toByteArray(Charsets.UTF_8))

    companion object {
        private const val TAG = "KeystoreManager"
        const val IV_SIZE = 12
        fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
    }
}
