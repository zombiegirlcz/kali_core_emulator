package com.adguard.corelibs.network

enum class Protocol(val code: Int) {
    TCP(6),
    UDP(17),
    ICMP(1),
    UNKNOWN(-1);

    companion object {
        fun getByCode(code: Int): Protocol {
            return when (code) {
                6 -> TCP
                17 -> UDP
                1 -> ICMP
                else -> UNKNOWN
            }
        }
    }
}
