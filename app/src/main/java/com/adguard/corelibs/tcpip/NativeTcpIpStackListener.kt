package com.adguard.corelibs.tcpip

interface NativeTcpIpStackListener {
    fun onConnectRequest(connectionInfo: TcpIpConnectionInfo): ConnectionRequestResult
}
