package com.adguard.corelibs.tcpip

interface NativeTcpIpStackListener {
    fun onTcpConnectRequest(id: Long, connectionInfo: TcpIpConnectionInfo): ConnectionRequestResult
    fun onUdpConnectRequest(id: Long, connectionInfo: TcpIpConnectionInfo): ConnectionRequestResult
    fun onConnectionClosed(id: Long) {}
    fun onConnectionStats(id: Long, bytesSent: Long, bytesReceived: Long) {}
    fun onUdpConnectionClosed(id: Long) {}
    fun onUdpConnectionStats(id: Long, bytesSent: Long, bytesReceived: Long) {}
}
