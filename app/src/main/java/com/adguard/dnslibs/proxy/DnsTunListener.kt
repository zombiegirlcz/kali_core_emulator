package com.adguard.dnslibs.proxy

class DnsTunListener {
    class InitResult {
        @JvmField var success: Boolean = false
        @JvmField var error: String? = null
    }

    interface RequestCallback {
        fun onInitCompleted(result: InitResult)
    }
}
