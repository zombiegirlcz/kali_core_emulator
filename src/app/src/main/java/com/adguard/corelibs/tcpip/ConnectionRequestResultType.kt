package com.adguard.corelibs.tcpip

enum class ConnectionRequestResultType(val code: Int) {
    ALLOW(0),
    BLOCK(1),
    REDIRECT(2);

    companion object {
        fun getByCode(code: Int): ConnectionRequestResultType {
            return values().firstOrNull { it.code == code } ?: ALLOW
        }
    }
}
