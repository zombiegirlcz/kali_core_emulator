package com.linux_core.security

import android.content.Context
import android.util.Log
import com.linux_core.BuildConfig
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Manages the MITM Root CA bundled with the app.
 *
 * Behaviour:
 *  - **Debug build** ([BuildConfig.DEBUG] = true): the user can install the CA in the
 *    system trust store via [requestSystemInstall]. This is required for the OS to trust
 *    re-signed leaf certs produced by [com.linux_core.core.VpnCaptureService].
 *  - **Release build**: the CA is kept in an in-process [KeyStore] only, so outbound
 *    OkHttp clients from this app can be configured to trust the MITM CA, but the OS
 *    trust store is NEVER modified.
 *
 * The CA file is expected at `assets/certs/mitm-ca.crt`. Missing file -> [getTrustManager]
 * returns null and the OkHttp client falls back to system trust.
 */
class RootCaInstaller(private val context: Context) {

    private val caCert = AtomicReference<X509Certificate?>(null)
    private val trustStore: KeyStore by lazy {
        KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    }

    fun isAvailable(): Boolean = try {
        loadCa() != null
    } catch (t: Throwable) {
        Log.w(TAG, "isAvailable failed: ${t.message}")
        false
    }

    /**
     * Returns a trust manager that trusts the bundled MITM CA in addition to (or instead of,
     * in release mode) the system trust store.
     */
    fun getTrustManager(): javax.net.ssl.X509TrustManager? {
        val cert = loadCa() ?: return null
        synchronized(trustStore) {
            try {
                trustStore.setCertificateEntry(ALIAS, cert)
            } catch (e: Exception) {
                Log.w(TAG, "Could not add CA to in-process trust store: ${e.message}")
            }
        }
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(trustStore)
        return tmf.trustManagers
            .filterIsInstance<javax.net.ssl.X509TrustManager>()
            .firstOrNull()
    }

    /**
     * Returns the raw bytes of the MITM CA so the caller can hand them to the
     * system's CA installer (typically `KeyChain.createInstallIntent` invoked from an
     * Activity). The function itself never installs anything – exposing the bytes
     * keeps this class free of [android.app.Activity] dependencies.
     *
     * In release builds this still returns the bytes (for the purposes of building a
     * runtime TrustManager), but [requestSystemInstall] / external invocations of
     * `KeyChain` MUST be guarded by the caller with a `BuildConfig.DEBUG` check so
     * production builds never write to the system trust store.
     */
    fun caBytes(): ByteArray? = readAssetBytes(ASSET_CA_FILE)

    /**
     * Produce a forged leaf certificate signed by the MITM CA. Used by
     * [com.linux_core.core.VpnCaptureService] to re-sign per-server certs captured from
     * the tunnel.
     */
    fun signLeafForServer(serverCert: X509Certificate, serial: Long): X509Certificate {
        val ca = loadCa() ?: throw IllegalStateException("MITM CA not loaded")
        val caKey = loadCaPrivateKey() ?: throw IllegalStateException("MITM CA private key missing")
        return MitmCertSigner.sign(ca, caKey, serverCert, serial)
    }

    fun createServerSslContext(serverCert: X509Certificate, serial: Long): SSLContext? {
        val ca = loadCa() ?: return null
        val caKey = loadCaPrivateKey() ?: return null
        val pwd = resolvePassword() ?: return null
        return try {
            val forged = MitmCertSigner.sign(ca, caKey, serverCert, serial)
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, caKey, pwd.toCharArray(), arrayOf(forged, ca))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, pwd.toCharArray())
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, null, null)
            ctx
        } catch (e: Exception) {
            Log.e(TAG, "createServerSslContext failed: ${e.message}")
            null
        }
    }

    private fun loadCa(): X509Certificate? {
        caCert.get()?.let { return it }
        val bytes = readAssetBytes(ASSET_CA_FILE) ?: return null
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
            caCert.set(cert)
            cert
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse MITM CA: ${e.message}")
            null
        }
    }

    private fun loadCaPrivateKey(): java.security.PrivateKey? {
        // The CA private key is bundled in `assets/certs/mitm-ca.p12` for development.
        // In production we never sign server certs from the device.
        val bytes = readAssetBytes(ASSET_CA_KEY_FILE) ?: return null
        val pwd = resolvePassword()
        if (pwd == null) {
            Log.e(TAG, "MITM CA private key password not configured - set KEYSTORE_PASSWORD env var")
            return null
        }
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(ByteArrayInputStream(bytes), pwd.toCharArray())
            ks.getKey(ALIAS, pwd.toCharArray()) as? java.security.PrivateKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MITM CA private key: ${e.message}")
            null
        }
    }

    private fun resolvePassword(): String? {
        // Priority: env var > gradle properties > null (fail in release)
        val env = System.getenv("KEYSTORE_PASSWORD")
        if (env != null) return env
        // Try gradle properties as fallback (not recommended for production)
        val propsFile = java.io.File(System.getProperty("user.home"), ".gradle/gradle.properties")
        if (propsFile.exists()) {
            try {
                propsFile.useLines { lines ->
                    lines.find { it.startsWith("keystore.password=") }
                        ?.substringAfter("=")?.takeIf { it.isNotEmpty() }
                        ?.let { return it }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read gradle.properties: ${e.message}")
            }
        }
        // No password available - fail for release builds
        if (!com.linux_core.BuildConfig.DEBUG) {
            Log.e(TAG, "No password source available for release build")
            return null
        }
        // Debug-only: allow local dev build but log warning
        Log.w(TAG, "Using insecure dev mode - MITM CA key password must be set via KEYSTORE_PASSWORD")
        return null // Changed from "nethunter-dev" to require explicit configuration
    }

    private fun readAssetBytes(name: String): ByteArray? = try {
        context.assets.open(name).use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "RootCaInstaller"
        const val ASSET_CA_FILE = "certs/mitm-ca.crt"
        const val ASSET_CA_KEY_FILE = "certs/mitm-ca.p12"
        const val ALIAS = "nethunter_mitm_ca"
        // P12_PASSWORD removed - now resolved dynamically via resolvePassword()
    }
}
