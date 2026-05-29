package com.adguard.corelibs.tcpip

import java.net.InetSocketAddress

class ConnectionRequestResult {
    @JvmField var resultType: ConnectionRequestResultType = ConnectionRequestResultType.ALLOW
    @JvmField var redirectAddress: InetSocketAddress? = null
    @JvmField var isForceDirectConnection: Boolean = false

    constructor()

    constructor(resultType: ConnectionRequestResultType, redirectAddress: InetSocketAddress?, isForceDirectConnection: Boolean) {
        this.resultType = resultType
        this.redirectAddress = redirectAddress
        this.isForceDirectConnection = isForceDirectConnection
    }

    companion object {
        @JvmField
        val REJECT = ConnectionRequestResult(ConnectionRequestResultType.BLOCK, null, false)
    }
}
