package com.adguard.corelibs.tcpip

import java.net.InetSocketAddress

class ConnectionRequestResult(
    val resultType: ConnectionRequestResultType,
    val redirectAddress: InetSocketAddress?,
    val isForceDirectConnection: Boolean
)
