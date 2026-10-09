package com.linux_core.core.rootfs

import org.apache.commons.compress.archivers.ArchiveException
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.CompressorException
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Rozpoznání formátu rootfs archivu podle obsahu (magic bajty), ne podle
 * přípony — URL z katalogu/registry příponu mít nemusí a Docker vrstvy bývají
 * gzip, zstd i nekomprimovaný tar.
 *
 * Pořadí: nejdřív kontejner (tar/zip) přímo, pak komprese (gzip, xz, bzip2,
 * zstd, lzma, lz4, .Z, deflate, …) a kontejner uvnitř ní. Kontejner první,
 * protože deflate signatura (0x78 …) by jinak mohla chytit tar, jehož první
 * položka začíná na „x“.
 */
internal object ArchiveFormats {

    sealed class Opened {
        class Tar(val stream: InputStream) : Opened()
        class Zip(val stream: ZipArchiveInputStream) : Opened()
    }

    private const val BUF = 512 * 1024

    /** Otevře archiv; volající MUSÍ stream zavřít. */
    fun open(source: File): Opened {
        val raw = BufferedInputStream(FileInputStream(source), BUF)
        try {
            container(raw)?.let { return it }
            val compressor =
                try {
                    CompressorStreamFactory.detect(raw)
                } catch (_: CompressorException) {
                    throw IOException("Nepodporovaný formát archivu ${source.name}: ${describeHead(source)}")
                }
            // decompressUntilEOF=true: vícečlenný gzip/bzip2/xz (pigz, pbzip2)
            val decompressed =
                try {
                    CompressorStreamFactory(true).createCompressorInputStream(compressor, raw)
                } catch (e: CompressorException) {
                    throw IOException("Kompresi '$compressor' nelze otevřít: ${e.message}", e)
                }
            val inner = BufferedInputStream(decompressed, BUF)
            return container(inner)
                ?: throw IOException("Archiv ${source.name} ($compressor) neobsahuje tar ani zip")
        } catch (t: Throwable) {
            try { raw.close() } catch (_: IOException) {}
            throw t
        }
    }

    private fun container(stream: BufferedInputStream): Opened? {
        val type =
            try {
                ArchiveStreamFactory.detect(stream)
            } catch (_: ArchiveException) {
                return null
            }
        return when (type) {
            ArchiveStreamFactory.TAR -> Opened.Tar(stream)
            ArchiveStreamFactory.ZIP -> Opened.Zip(ZipArchiveInputStream(stream))
            else -> throw IOException("Archiv typu '$type' není podporován (jen tar a zip)")
        }
    }

    /** Pro chybovou hlášku: HTML místo archivu (chybová stránka serveru) nebo hex hlavičky. */
    private fun describeHead(source: File): String {
        val head = ByteArray(16)
        val n = FileInputStream(source).use { it.read(head) }.coerceAtLeast(0)
        val text = String(head, 0, n, Charsets.ISO_8859_1).trimStart()
        if (text.startsWith("<")) return "server vrátil HTML/XML místo archivu"
        return "hlavička " + head.take(n).joinToString(" ") { "%02x".format(it) }
    }
}
