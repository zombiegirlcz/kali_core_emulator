package com.linux_core.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Regrese k incidentu 2026-09-19: staticky linkovany `su_wrapper` postaveny
 * NDK r27 mel `PT_TLS` segment s `p_align = 8`. ARM64 Bionic linker takovou
 * binarku odmitne spustit:
 *
 *   error: "sudo": executable's TLS segment is underaligned: alignment is 8
 *          (skew 0), needs to be at least 64 for ARM64 Bionic
 *
 * NDK r28 pro tyhle binarky `PT_TLS` segment vubec negeneruje. Test cte primo
 * ELF program headers assetu a hlida, ze se rozbita binarka necommitne zpet.
 *
 * Bezi z modulu `app` (cwd = app/) — viz DeployAssetPathsTest.
 */
class SuWrapperTlsAlignTest {

    private fun asset(name: String): File = File("src/main/assets/$name")

    /** Vrati (p_align, p_filesz) PT_TLS segmentu, nebo null kdyz PT_TLS neni. */
    private fun ptTls(file: File): Pair<Long, Long>? {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // e_ident: 0x7f 'E' 'L' 'F'
        assertTrue(
            "neni ELF: ${file.name}",
            bytes.size > 64 &&
                bytes[0] == 0x7f.toByte() &&
                bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() &&
                bytes[3] == 'F'.code.toByte(),
        )
        // 64-bit little-endian only (aarch64)
        val eiClass = bytes[4].toInt() and 0xff // 2 = ELFCLASS64
        val eiData = bytes[5].toInt() and 0xff // 1 = ELFDATA2LSB
        assertTrue("ocekavam ELF64 LE: ${file.name}", eiClass == 2 && eiData == 1)

        val ePhoff = buf.getLong(0x20) // e_phoff
        val ePhentsize = buf.getShort(0x36).toInt() and 0xffff // e_phentsize (56)
        val ePhnum = buf.getShort(0x38).toInt() and 0xffff // e_phnum

        for (i in 0 until ePhnum) {
            val off = (ePhoff + i.toLong() * ePhentsize).toInt()
            val pType = buf.getInt(off)
            if (pType == 7) { // PT_TLS
                val pFilesz = buf.getLong(off + 0x20)
                val pAlign = buf.getLong(off + 0x30)
                return pAlign to pFilesz
            }
        }
        return null
    }

    @Test
    fun `su_wrapper asset nema podalignovany TLS segment`() {
        val f = asset("su_wrapper")
        assertTrue("asset chybi: ${f.absolutePath}", f.isFile && f.length() > 0L)
        val tls = ptTls(f)
        if (tls != null) {
            val (align, _) = tls
            assertTrue(
                "su_wrapper PT_TLS p_align=$align — ARM64 Bionic vyzaduje >=64 " +
                    "(rozbita binarka z NDK r27). Prebuild s NDK r28.",
                align >= 64L,
            )
        }
        // null (zadny PT_TLS) je v poradku = presne to, co produkuje NDK r28.
    }
}