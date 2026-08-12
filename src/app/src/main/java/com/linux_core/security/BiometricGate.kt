package com.linux_core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin wrapper around [BiometricPrompt] that gates AndroidKeyStore operations.
 *
 * Authenticated operations are valid for the duration specified by
 * [AttestationKeyManager.AUTH_VALIDITY_SECONDS] thanks to the per-key
 * `setUserAuthenticationValidityDurationSeconds` set in [AttestationKeyManager].
 */
class BiometricGate {

    fun capability(context: Context): Int =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

    fun canAuthenticate(context: Context): Boolean =
        capability(context) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Launches the system biometric prompt. The result is delivered on the main thread
     * by [BiometricPrompt] but a CompletableFuture is returned for ergonomic awaiting.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock NetHunter",
        subtitle: String = "Authenticate to sign API requests"
    ): java.util.concurrent.CompletableFuture<BiometricPrompt.AuthenticationResult> {
        val future = java.util.concurrent.CompletableFuture<BiometricPrompt.AuthenticationResult>()
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    future.complete(result)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    future.completeExceptionally(BiometricException(errorCode, errString.toString()))
                }
                override fun onAuthenticationFailed() {
                    // Single failed attempt – the prompt stays open. We do not fail-fast.
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
        return future
    }

    class BiometricException(val code: Int, message: String) : RuntimeException(message)
}
