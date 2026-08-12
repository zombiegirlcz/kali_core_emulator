package com.adguard.corelibs.proxy

class BeforeRequestEvent {
    @JvmField var url: String? = null
    @JvmField var method: String? = null
    @JvmField var requestHeaders: Any? = null
}
