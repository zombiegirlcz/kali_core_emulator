package com.adguard.corelibs.tcpip

import com.adguard.corelibs.network.Protocol
import java.net.InetSocketAddress

class TcpIpConnectionInfo(
    val protocol: Protocol,
    val source: InetSocketAddress,
    val destination: InetSocketAddress
)
