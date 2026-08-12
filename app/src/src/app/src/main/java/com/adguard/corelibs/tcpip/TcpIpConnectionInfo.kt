package com.adguard.corelibs.tcpip

import com.adguard.corelibs.network.Protocol
import java.net.InetSocketAddress

class TcpIpConnectionInfo {
    @JvmField var protocol: Protocol = Protocol.TCP
    @JvmField var source: InetSocketAddress? = null
    @JvmField var destination: InetSocketAddress? = null

    constructor()

    constructor(protocol: Protocol, source: InetSocketAddress, destination: InetSocketAddress) {
        this.protocol = protocol
        this.source = source
        this.destination = destination
    }
}
