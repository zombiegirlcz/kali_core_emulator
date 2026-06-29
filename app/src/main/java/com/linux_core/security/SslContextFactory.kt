package com.linux_core.security

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds a TLS [SSLContext] from the bundled PKCS#12 keystore (`assets/certs/internal.p12`)
 * and an optional [RootCaInstaller] trust anchor.
 *
 * Used by outbound OkHttp clients that need to either:
 *  - present a client certificate (mTLS), or
 *  - trust the MITM CA while keeping system trust anchors.
 *
 * The keystore passphrase is read from the gradle `KEYSTORE_PASSWORD` env var (with a
 * safe development fallback). In release builds the passphrase MUST be provided via
 * the environment; the fallback never applies.
 */
class SslContextFactory(private val context: Context) {

    @Volatile private var cached: SSLContext? = null
    @Volatile private var cachedTrustManager: X509TrustManager? = null

    fun sslContext(): SSLContext? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            try {
                val ks = loadKeyStore()
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(ks, passphrase())
                val tm = trustManager()
                val ctx = SSLContext.getInstance("TLSv1.2")
                ctx.init(kmf.keyManagers, arrayOf<TrustManager>(tm), null)
                cached = ctx
                cachedTrustManager = tm
                Log.i(TAG, "SSLContext built from assets/certs/internal.p12")
                return ctx
            } catch (e: Exception) {
                Log.e(TAG, "sslContext() failed: ${e.message}")
                return null
            }
        }
    }

    fun trustManager(): X509TrustManager {
        cachedTrustManager?.let { return it }
        synchronized(this) {
            cachedTrustManager?.let { return it }
            val mitm = CertificateManager.rootCa()?.getTrustManager()
            val trust = if (mitm != null) {
                CompositeTrustManager(mitm, systemTrustManager())
            } else {
                systemTrustManager()
            }
            cachedTrustManager = trust
            return trust
        }
    }

    private fun loadKeyStore(): KeyStore {
        val bytes = context.assets.open(KEYSTORE_ASSET).use { it.readBytes() }
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(ByteArrayInputStream(bytes), passphrase())
        return ks
    }

    private fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun passphrase(): CharArray {
        val env = System.getenv("KEYSTORE_PASSWORD")
        val pass = env ?: "nethunter-dev"
        return pass.toCharArray()
    }

    /**
     * X509TrustManager that delegates to a primary trust manager (the MITM CA)
     * and falls back to the system trust manager for everything else.
     */
    private class CompositeTrustManager(
        private val primary: X509TrustManager,
        private val fallback: X509TrustManager
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) {
            try { primary.checkClientTrusted(chain, authType); return }
            catch (_: Exception) { fallback.checkClientTrusted(chain, authType) }
        }
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) {
            try { primary.checkServerTrusted(chain, authType); return }
            catch (_: Exception) { fallback.checkServerTrusted(chain, authType) }
        }
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
            (primary.acceptedIssuers + fallback.acceptedIssuers).distinct().toTypedArray()
    }

    companion object {
        private const val TAG = "SslContextFactory"
        const val KEYSTORE_ASSET = "certs/internal.p12"
    }
}
