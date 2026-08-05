package com.adguard.dnslibs.proxy

import android.content.Context
import java.io.Closeable

class DnsProxy private constructor() : Closeable {

    companion object {
        init {
            try {
                // System.loadLibrary("a")
                System.loadLibrary("io_utils")
                System.loadLibrary("common_native_jni")
                System.loadLibrary("adguard-dns")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("DnsProxy", "Failed to load libraries: ${e.message}", e)
                throw e
            }
        }


        @JvmStatic
        private external fun setApplicationContext(context: Context)

        @JvmStatic
        private external fun setLogLevel(level: Int)

        @JvmStatic
        private external fun log(level: Int, message: String)

        @JvmStatic
        private external fun testUpstreamNative(nativePtr: Long, upstream: Any, timeout: Long, check: Boolean, bootstrap: Any?, serverName: Boolean): String?

        @JvmStatic
        external fun isValidRule(rule: String): Boolean

        @JvmStatic
        external fun version(): String
    }

    enum class State {
        NEW, INITIALIZED, CLOSED
    }

    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR, TRACE
    }

    enum class ReapplyOption(val code: Int) {
        SETTINGS(0),
        FILTERS(1)
    }

    interface LoggingCallback {
        fun log(level: Int, message: String)
    }

    enum class InitErrorCode {
        PROXY_NOT_SET,
        EVENT_LOOP_NOT_SET,
        INVALID_ADDRESS,
        EMPTY_PROXY,
        PROTOCOL_ERROR,
        LISTENER_INIT_ERROR,
        INVALID_IPV4,
        INVALID_IPV6,
        UPSTREAM_INIT_ERROR,
        FALLBACK_FILTER_INIT_ERROR,
        FILTER_LOAD_ERROR,
        MEM_LIMIT_REACHED,
        NON_UNIQUE_FILTER_ID,
        OK
    }

    class InitResult {
        @JvmField var success: Boolean = false
        @JvmField var code: InitErrorCode = InitErrorCode.OK
        @JvmField var description: String? = ""
    }

    class EventsAdapter(private val events: DnsProxyEvents?) {
        fun onRequestProcessed(event: DnsRequestProcessedEvent) {
            events?.onRequestProcessed(event)
        }

        fun onCertificateVerification(event: CertificateVerificationEvent): String? {
            try {
                val certBytes = event.getCertificate() ?: return "No certificate found"
                val factory = java.security.cert.CertificateFactory.getInstance("X.509")
                
                val certStream = java.io.ByteArrayInputStream(certBytes)
                val leafCert = factory.generateCertificate(certStream) as java.security.cert.X509Certificate

                val certChain = mutableListOf<java.security.cert.X509Certificate>()
                certChain.add(leafCert)

                val chainBytes = event.getChain()
                if (chainBytes != null) {
                    for (bytes in chainBytes) {
                        val stream = java.io.ByteArrayInputStream(bytes)
                        val cert = factory.generateCertificate(stream) as java.security.cert.X509Certificate
                        certChain.add(cert)
                    }
                }

                val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as java.security.KeyStore?)

                var verified = false
                for (tm in tmf.trustManagers) {
                    if (tm is javax.net.ssl.X509TrustManager) {
                        try {
                            val authType = leafCert.publicKey.algorithm ?: "RSA"
                            tm.checkServerTrusted(certChain.toTypedArray(), authType)
                            verified = true
                            break
                        } catch (e: java.security.cert.CertificateException) {

                            return e.toString()
                        }
                    }
                }
                
                if (!verified) {
                    return "No X509TrustManager found"
                }

                val customError = events?.onCertificateVerification(event)
                if (customError != null) {
                    return customError
                }

                return null
            } catch (e: Throwable) {
                return e.toString()
            }
        }
    }

    private var nativePtr: Long = 0
    @Volatile
    private var state: State = State.NEW
    @JvmField
    var eventsAdapter: EventsAdapter? = null

    init {
        nativePtr = create()
    }

    // Finalizer removed to prevent fatal SIGABRTs during GC

    constructor(context: Context, settings: DnsProxySettings) : this(context, settings, null)

    constructor(context: Context, settings: DnsProxySettings, events: DnsProxyEvents?) : this() {
        setApplicationContext(context)
        if (settings.detectSearchDomains) {
            val domains = DnsNetworkUtils.getDNSSearchDomains(context)
            if (domains.isNotEmpty()) {
                val fallbackDomains = settings.fallbackDomains
                for (domain in domains) {
                    val trimmed = domain.trim().trim('.')
                    if (trimmed.isNotEmpty()) {
                        val wildcardDomain = "*.$trimmed"
                        if (!fallbackDomains.contains(wildcardDomain)) {
                            fallbackDomains.add(wildcardDomain)
                        }
                    }
                }
            }
        }
        val adapter = EventsAdapter(events)
        this.eventsAdapter = adapter
        val result = init(nativePtr, settings, adapter)
        if (result.success) {
            state = State.INITIALIZED
        } else {
            close()
            throw DnsProxyInitException(result)
        }
    }

    private external fun create(): Long
    private external fun deinit(nativePtr: Long)
    private external fun delete(nativePtr: Long)
    private external fun init(nativePtr: Long, settings: DnsProxySettings, adapter: EventsAdapter): InitResult
    private external fun handleMessage(nativePtr: Long, message: ByteArray, info: DnsMessageInfo): ByteArray
    private external fun getSettings(nativePtr: Long): DnsProxySettings
    private external fun getDefaultSettings(nativePtr: Long): DnsProxySettings

    fun handleMessage(message: ByteArray, info: DnsMessageInfo): ByteArray {
        if (state == State.INITIALIZED) {
            return handleMessage(nativePtr, message, info)
        }
        throw IllegalStateException("Closed")
    }

    override fun close() {
        synchronized(this) {
            if (state == State.INITIALIZED) {
                deinit(nativePtr)
                state = State.NEW
            }
            if (state == State.NEW) {
                if (nativePtr != 0L) {
                    delete(nativePtr)
                    nativePtr = 0L
                }
                state = State.CLOSED
            }
        }
    }

}

