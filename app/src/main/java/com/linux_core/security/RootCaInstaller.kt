package com.linux_core.security

import android.content.Context
import android.util.Log
import com.linux_core.BuildConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
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
 *    re-signed leaf certs produced by [com.linux_core.core.vpn.VpnCaptureService].
 *  - **Release build**: the CA is kept in an in-process [KeyStore] only, so outbound
 *    OkHttp clients from this app can be configured to trust the MITM CA, but the OS
 *    trust store is NEVER modified.
 *
 * The CA file is expected at `assets/certs/mitm-ca.crt`. Missing file -> [getTrustManager]
 * returns null and the OkHttp client falls back to system trust.
 */
class RootCaInstaller(private val context: Context) {

    private val caCert = AtomicReference<X509Certificate?>(null)
    private val caKey = AtomicReference<PrivateKey?>(null)
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
    fun caBytes(): ByteArray? = try {
        ensureMaterial()?.first?.encoded ?: readAssetBytes(ASSET_CA_FILE)
    } catch (e: Exception) {
        Log.w(TAG, "caBytes failed: ${e.message}")
        readAssetBytes(ASSET_CA_FILE)
    }

    /**
     * Produce a forged leaf certificate signed by the MITM CA. Used by
     * [com.linux_core.core.vpn.VpnCaptureService] to re-sign per-server certs captured from
     * the tunnel.
     */
    fun signLeafForServer(serverCert: X509Certificate, serial: Long, sanDns: List<String> = emptyList(), sanIp: List<String> = emptyList()): X509Certificate {
        val ca = loadCa() ?: throw IllegalStateException("MITM CA not loaded")
        val caKey = loadCaPrivateKey() ?: throw IllegalStateException("MITM CA private key missing")
        return MitmCertSigner.sign(ca, caKey, serverCert, serial, sanDns, sanIp)
    }

    fun createServerSslContext(serverCert: X509Certificate, serial: Long, sanDns: List<String> = emptyList(), sanIp: List<String> = emptyList()): SSLContext? {
        val ca = loadCa() ?: return null
        val caKey = loadCaPrivateKey() ?: return null
        val pwd = resolvePassword() ?: return null
        return try {
            // Vlastni keypair — cert ma nas public key, private key sedi
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048)
            val keyPair = keyGen.generateKeyPair()

            val cn = sanDns.firstOrNull() ?: serverCert.subjectX500Principal.name
            val forged = MitmCertSigner.signWithPublicKey(
                ca, caKey, keyPair.public, serial,
                cn = cn, sanDns = sanDns
            )
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, keyPair.private, pwd.toCharArray(), arrayOf(forged, ca))
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

    /**
     * Capture-only SSL context: generuje vlastni RSA klic, podepise certifikat CA,
     * bez pripojeni k realnemu serveru. Desifrovana data jdou jen do local logu.
     */
    fun createCaptureOnlySslContext(sni: String): SSLContext? {
        val ca = loadCa() ?: return null
        val caKey = loadCaPrivateKey() ?: return null
        val pwd = resolvePassword() ?: return null
        return try {
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048)
            val keyPair = keyGen.generateKeyPair()

            val forged = MitmCertSigner.signWithPublicKey(
                ca, caKey, keyPair.public, System.currentTimeMillis(),
                cn = sni, sanDns = listOf(sni)
            )

            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, keyPair.private, pwd.toCharArray(), arrayOf(forged, ca))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, pwd.toCharArray())
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, null, null)
            ctx
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureOnlySslContext failed: ${e.message}")
            null
        }
    }

    private fun loadCa(): X509Certificate? = ensureMaterial()?.first

    private fun loadCaPrivateKey(): PrivateKey? = ensureMaterial()?.second

    /**
     * Resolve the MITM CA cert+key pair, in priority order:
     *   1. in-memory cache
     *   2. generated keystore at filesDir/certs/mitm-ca.p12 (survives app updates)
     *   3. bundled asset keystore certs/mitm-ca.p12 (back-compat; uses the cert inside it)
     *   4. generate a fresh self-signed CA and persist it as (2)
     *
     * The device-local CA replaces the historical bundled dev key, which was gitignored
     * (`assets/certs/mitm-ca.p12` / `mitm-ca.key`) and therefore absent from every build —
     * so every full MITM session fell back to passthrough. The cert is served to the user
     * via `GET /vpn/mitm/ca` (`vpn-cli mitm ca`) for installation into the trust store.
     *
     * Both the cert and key are always generated together, so the cert the user installs
     * always matches the key used to forge leaf certs. Release builds without a keystore
     * password do not generate (returns null → passthrough), preserving the production
     * stance of never signing on-device.
     */
    private fun ensureMaterial(): Pair<X509Certificate, PrivateKey>? {
        caCert.get()?.let { c -> caKey.get()?.let { k -> return c to k } }
        synchronized(materialLock) {
            caCert.get()?.let { c -> caKey.get()?.let { k -> return c to k } }
            val pwd = resolvePassword()
            loadFromP12Stream(generatedP12File().takeIf { it.exists() }?.inputStream(), pwd)
                ?.let { return cacheMaterial(it) }
            loadFromP12Stream(readAssetBytes(ASSET_CA_KEY_FILE)?.let { ByteArrayInputStream(it) }, pwd)
                ?.let { return cacheMaterial(it) }
            return generateAndPersist()?.let { cacheMaterial(it) }
        }
    }

    private fun cacheMaterial(pair: Pair<X509Certificate, PrivateKey>): Pair<X509Certificate, PrivateKey> {
        caCert.set(pair.first)
        caKey.set(pair.second)
        return pair
    }

    private fun loadFromP12Stream(stream: java.io.InputStream?, pwd: String?): Pair<X509Certificate, PrivateKey>? {
        if (stream == null || pwd == null) return null
        return try {
            stream.use {
                val ks = KeyStore.getInstance("PKCS12")
                ks.load(it, pwd.toCharArray())
                val key = ks.getKey(ALIAS, pwd.toCharArray()) as? PrivateKey ?: return null
                val cert = ks.getCertificate(ALIAS) as? X509Certificate ?: return null
                cert to key
            }
        } catch (e: Exception) {
            Log.w(TAG, "PKCS12 load failed: ${e.message}")
            null
        }
    }

    private fun generateAndPersist(): Pair<X509Certificate, PrivateKey>? {
        val pwd = resolvePassword()
        if (pwd == null) {
            Log.e(TAG, "Cannot generate MITM CA: no keystore password (release build without KEYSTORE_PASSWORD)")
            return null
        }
        return try {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val cert = MitmCertSigner.createSelfSignedCa(kp, CA_CN, validityDays = 3650)

            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, kp.private, pwd.toCharArray(), arrayOf<java.security.cert.Certificate>(cert))
            val dir = File(context.filesDir, "certs").apply { mkdirs() }
            File(dir, "mitm-ca.p12").outputStream().use { ks.store(it, pwd.toCharArray()) }
            // Also drop a DER copy for debugging / manual export; the API serves cert.encoded directly.
            try { File(dir, "mitm-ca.crt").writeBytes(cert.encoded) } catch (_: Exception) {}
            Log.i(TAG, "Generated self-signed MITM CA (CA:true) at filesDir/certs/mitm-ca.p12 — install via GET /vpn/mitm/ca")
            cert to kp.private
        } catch (e: Exception) {
            Log.e(TAG, "generateAndPersist MITM CA failed: ${e.message}", e)
            null
        }
    }

    private fun generatedP12File(): File = File(File(context.filesDir, "certs"), "mitm-ca.p12")

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
        // Debug-only: fallback na heslo pouzite pri generovani P12 (assets/certs/mitm-ca.p12)
        Log.w(TAG, "MITM CA: using debug fallback password for local P12")
        return "nethunter-dev"
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
        private const val CA_CN = "NetHunter MITM CA"
        // Class-level lock so concurrent instances don't generate/persist the CA twice.
        private val materialLock = Any()
        // P12_PASSWORD removed - now resolved dynamically via resolvePassword()
    }
}
