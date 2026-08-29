package com.linux_core.xlauncher

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * VNC authentication: the server sends a 16-byte challenge; the client must
 * encrypt it with DES using the password as the 8-byte key. VNC has the
 * well-known quirk of reversing the bits of each key byte.
 */
object VncAuth {

    fun challenge(password: String, challenge: ByteArray): ByteArray {
        require(challenge.size == 16) { "VNC challenge must be 16 bytes" }

        val keyBytes = ByteArray(8)
        val pw = password.toByteArray(Charsets.ISO_8859_1)
        val n = minOf(pw.size, 8)
        for (i in 0 until n) keyBytes[i] = reverseBits(pw[i])

        val key = SecretKeySpec(keyBytes, "DES")
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val out = ByteArray(16)
        cipher.doFinal(challenge, 0, 8, out, 0)
        cipher.doFinal(challenge, 8, 8, out, 8)
        return out
    }

    private fun reverseBits(b: Byte): Byte {
        var x = b.toInt() and 0xFF
        var r = 0
        repeat(8) {
            r = (r shl 1) or (x and 1)
            x = x shr 1
        }
        return r.toByte()
    }
}
