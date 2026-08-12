package com.adguard.corelibs.proxy

class ProxyUtils {
    class ProxySocketProtector {
        fun protect(socketFd: Int): Boolean {
            return com.linux_core.core.VpnCaptureService.protectSocket(socketFd)
        }

        fun protect(socket: java.net.Socket): Boolean {
            return com.linux_core.core.VpnCaptureService.protectSocket(socket)
        }

        fun protect(socket: java.net.DatagramSocket): Boolean {
            return com.linux_core.core.VpnCaptureService.protectSocket(socket)
        }
    }
}
