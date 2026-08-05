package com.adguard.dnslibs.proxy

import java.util.ArrayList

class Dns64Settings {
    @JvmField var upstreams: List<UpstreamSettings>? = ArrayList()
    @JvmField var maxTries: Long = 0
    @JvmField var waitTimeMs: Long = 0
}
