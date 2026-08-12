package com.adguard.corelibs.tcpip

import java.io.Closeable
import java.net.InetSocketAddress

interface NativeTcpIpStack : Closeable {
    fun getTcpConnectionIdBySocketAddress(socketAddress: InetSocketAddress?): Long?
    fun getUdpConnectionIdBySocketAddress(socketAddress: InetSocketAddress?): Long?
    fun stop()
    fun reset()
}
