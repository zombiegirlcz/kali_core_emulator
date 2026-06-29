package com.linux_core.security

import android.content.Context
import android.util.Log
import com.linux_core.BuildConfig
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Facade for the Certificate & Attestation module.
 *
 * Provides lazy, process-wide access to three subsystems:
 *  - [SslContextFactory] – loads bundled PKCS#12 trust material for internal OkHttp clients.
 *  - [RootCaInstaller]   – manages the MITM Root CA (debug-only system install; production in-process only).
 *  - [AttestationKeyManager] – TEE/StrongBox-backed EC P-256 key for signing/verification.
 *  - [KeystoreManager]   – AES-GCM-256 encryption for local secrets.
 *
 * All subsystems are gated by the corresponding BuildConfig flag. If [BuildConfig.ENABLE_MITM]
 * or [BuildConfig.ENABLE_ATTESTATION] is false, the related manager returns a no-op stub.
 */
object CertificateManager {
    private const val TAG = "CertificateManager"
    private val initialised = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var _sslContextFactory: SslContextFactory? = null
    @Volatile private var _rootCaInstaller: RootCaInstaller? = null
    @Volatile private var _attestation: AttestationKeyManager? = null
    @Volatile private var _keystore: KeystoreManager? = null

    /**
     * Idempotent initialisation. Must be called from [android.app.Application.onCreate]
     * or from any activity/service that needs the security subsystem.
     */
    fun init(context: Context) {
        if (initialised.getAndSet(true)) return
        appContext = context.applicationContext

        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not register BouncyCastle provider: ${t.message}")
        }

        if (BuildConfig.ENABLE_MITM) {
            _rootCaInstaller = RootCaInstaller(appContext!!)
            _sslContextFactory = SslContextFactory(appContext!!)
        } else {
            Log.i(TAG, "ENABLE_MITM=false – Root CA + SSL context disabled.")
        }

        if (BuildConfig.ENABLE_ATTESTATION) {
            _attestation = AttestationKeyManager(appContext!!)
            _keystore = KeystoreManager()
        } else {
            Log.i(TAG, "ENABLE_ATTESTATION=false – hardware attestation disabled.")
        }

        Log.i(TAG, "CertificateManager initialised (mitm=${BuildConfig.ENABLE_MITM}, " +
                "attestation=${BuildConfig.ENABLE_ATTESTATION}).")
    }

    fun rootCa(): RootCaInstaller? = _rootCaInstaller
    fun ssl(): SslContextFactory? = _sslContextFactory
    fun attestation(): AttestationKeyManager? = _attestation
    fun keystore(): KeystoreManager? = _keystore

    fun requireContext(): Context =
        appContext ?: throw IllegalStateException("CertificateManager not initialised")
}
