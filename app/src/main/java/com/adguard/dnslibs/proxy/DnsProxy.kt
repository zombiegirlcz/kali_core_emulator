package com.adguard.dnslibs.proxy

import android.content.Context
import java.io.Closeable

class DnsProxy private constructor() : Closeable {

    companion object {
        init {
            System.loadLibrary("adguard-dns")
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
        DEBUG, INFO, WARN, ERROR
    }

    class InitResult {
        var success: Boolean = false
        var error: String? = null
        var errorCode: Int = 0
    }

    class EventsAdapter(private val events: DnsProxyEvents?) {
        fun onRequestProcessed(event: DnsRequestProcessedEvent) {
            events?.onRequestProcessed(event)
        }
    }

    private var nativePtr: Long = 0
    private var state: State = State.NEW

    init {
        nativePtr = create()
    }

    constructor(context: Context, settings: DnsProxySettings) : this(context, settings, null)

    constructor(context: Context, settings: DnsProxySettings, events: DnsProxyEvents?) : this() {
        setApplicationContext(context)
        val adapter = EventsAdapter(events)
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
        if (state == State.INITIALIZED) {
            deinit(nativePtr)
            state = State.NEW
        }
        if (state == State.NEW) {
            delete(nativePtr)
            state = State.CLOSED
        }
    }
}
