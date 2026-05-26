package com.linux_core.core

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException

data class Distro(
    val id: String,
    val name: String,
    val url: String,
    val rootfsDirName: String,
    val tarFileName: String
)

object RootfsManager {
    val DISTROS = listOf(
        Distro(
            id = "kali",
            name = "Kali NetHunter",
            url = "https://images.kali.org/nethunter/rootfs/kali-nethunter-rootfs-nano-arm64.tar.xz",
            rootfsDirName = "kali-arm64",
            tarFileName = "kali-nethunter-rootfs.tar.xz"
        ),
        Distro(
            id = "parrot",
            name = "ParrotOS Security",
            url = "https://raw.githubusercontent.com/risecid/AndronixOrigin/master/Rootfs/Parrot/arm64/parrot-rootfs-arm64.tar.xz",
            rootfsDirName = "parrot-arm64",
            tarFileName = "parrot-rootfs-arm64.tar.xz"
        )
    )

    private const val TEMP_SUFFIX = ".tmp"
    private const val MIN_FREE_SPACE_BYTES = 1024L * 1024L * 1024L // 1 GB safety margin

    fun downloadRootfs(context: Context, distro: Distro): Flow<Int> = flow {
        val rootDir = context.filesDir
        val cacheDir = File(rootDir, distro.id)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val targetFile = File(cacheDir, distro.tarFileName)
        val tempFile = File(cacheDir, distro.tarFileName + TEMP_SUFFIX)

        // Clean up any stale partial files
        if (targetFile.exists()) targetFile.delete()
        if (tempFile.exists()) tempFile.delete()

        // Check available storage space
        checkAvailableSpace(cacheDir, MIN_FREE_SPACE_BYTES)

        val client = OkHttpClient()
        val request = Request.Builder().url(distro.url).build()

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "RootfsManager:download"
        )
        wakeLock.acquire(10 * 60 * 1000L) // 10-minute timeout

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                val responseBody = response.body ?: throw IOException("Response body is null")
                val totalLength = responseBody.contentLength()
                val inputStream = responseBody.byteStream()

                var bytesCopied: Long = 0
                FileOutputStream(tempFile).use { outputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var bytes = inputStream.read(buffer)
                    var lastProgress = 0
                    var lastEmitTimeMs = 0L

                    while (bytes >= 0) {
                        outputStream.write(buffer, 0, bytes)
                        bytesCopied += bytes

                        if (totalLength > 0) {
                            val progress = ((bytesCopied * 100) / totalLength).toInt()
                            val now = System.currentTimeMillis()
                            if (progress > lastProgress && (now - lastEmitTimeMs) >= 100) {
                                emit(progress)
                                lastProgress = progress
                                lastEmitTimeMs = now
                            }
                        } else {
                            if (lastProgress == 0) {
                                emit(-1)
                                lastProgress = -1
                            }
                        }
                        bytes = inputStream.read(buffer)
                    }
                }

                if (totalLength > 0 && bytesCopied != totalLength) {
                    tempFile.delete()
                    throw IOException(
                        "Download incomplete: expected ${totalLength} bytes, got ${bytesCopied}"
                    )
                }

                if (!tempFile.renameTo(targetFile)) {
                    tempFile.delete()
                    throw IOException("Failed to finalize download - rename failed")
                }
                emit(100)
            }
        } finally {
            try {
                wakeLock.release()
            } catch (_: Exception) {}
            if (tempFile.exists()) tempFile.delete()
        }
    }.flowOn(Dispatchers.IO)

    fun isRootfsExtracted(context: Context, distro: Distro): Boolean {
        val rootfsDir = File(context.filesDir, distro.rootfsDirName)
        return rootfsDir.exists() && rootfsDir.isDirectory && (File(rootfsDir, "bin/bash").exists() || File(rootfsDir, "bin/sh").exists())
    }

    fun deleteRootfs(context: Context, distro: Distro): Boolean {
        val cacheDir = File(context.filesDir, distro.id)
        val rootfsDir = File(context.filesDir, distro.rootfsDirName)
        val archiveFile = File(cacheDir, distro.tarFileName)
        val tempFile = File(cacheDir, distro.tarFileName + TEMP_SUFFIX)

        var success = true
        if (rootfsDir.exists()) success = rootfsDir.deleteRecursively() && success
        if (archiveFile.exists()) success = archiveFile.delete() && success
        if (tempFile.exists()) success = tempFile.delete() && success
        return success
    }

    fun extractRootfs(context: Context, distro: Distro): Flow<Int> = flow {
        val cacheDir = File(context.filesDir, distro.id)
        val rootfsFile = File(cacheDir, distro.tarFileName)
        val extractDir = File(context.filesDir, distro.rootfsDirName)

        if (!extractDir.exists()) {
            extractDir.mkdirs()
        }

        if (!rootfsFile.exists() || rootfsFile.length() == 0L) {
            throw IOException("Rootfs archive not found. Please download first.")
        }

        val archiveSize = rootfsFile.length()
        val requiredSpace = archiveSize * 3 + MIN_FREE_SPACE_BYTES / 2
        checkAvailableSpace(cacheDir, requiredSpace)

        emit(0)

        val totalSize = rootfsFile.length()
        var lastProgress = 0
        var lastEmitTimeMs = 0L

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "RootfsManager:extract"
        )
        wakeLock.acquire(30 * 60 * 1000L) // 30-minute timeout

        try {
            class CountingInputStream(input: java.io.InputStream) : FilterInputStream(input) {
                var bytesRead: Long = 0
                    private set

                override fun read(): Int {
                    val b = super.read()
                    if (b != -1) bytesRead++
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val result = super.read(b, off, len)
                    if (result > 0) bytesRead += result
                    return result
                }
            }

            val countingStream = CountingInputStream(BufferedInputStream(FileInputStream(rootfsFile)))

            countingStream.use { tracked ->
                XZCompressorInputStream(tracked).use { xzIn ->
                    TarArchiveInputStream(xzIn).use { tarIn ->
                        val canonicalBase = extractDir.canonicalPath
                        var stripPrefix: String? = null

                        var entry: ArchiveEntry? = tarIn.nextEntry
                        while (entry != null) {
                            if (stripPrefix == null && entry.name.contains('/')) {
                                stripPrefix = entry.name.substringBefore('/') + "/"
                            }
                            val relativeName = if (stripPrefix != null && entry.name.startsWith(stripPrefix!!)) {
                                val stripped = entry.name.removePrefix(stripPrefix!!)
                                stripped.ifEmpty { "." }
                            } else {
                                entry.name
                            }
                            val entryFile = File(extractDir, relativeName)
                            val canonicalDest = entryFile.canonicalPath

                            if (!canonicalDest.startsWith(canonicalBase + File.separator) &&
                                canonicalDest != canonicalBase
                            ) {
                                entry = tarIn.nextEntry
                                continue
                            }

                            val tarEntry = entry as? TarArchiveEntry
                            if (tarEntry != null && tarEntry.isDirectory) {
                                entryFile.mkdirs()
                                entry = tarIn.nextEntry
                                continue
                            }

                            if (tarEntry != null && (tarEntry.isSymbolicLink || tarEntry.isLink)) {
                                entryFile.parentFile?.mkdirs()
                                val linkTarget = tarEntry.linkName
                                try {
                                    if (tarEntry.isSymbolicLink) {
                                        android.system.Os.symlink(linkTarget, entryFile.absolutePath)
                                    } else {
                                        android.system.Os.link(linkTarget, entryFile.absolutePath)
                                    }
                                } catch (_: Exception) {
                                    entryFile.mkdirs()
                                }
                            } else {
                                entryFile.parentFile?.mkdirs()
                                FileOutputStream(entryFile).use { out ->
                                    tarIn.copyTo(out)
                                }
                                entryFile.setExecutable(true, false)
                            }

                            val progress = if (totalSize > 0) ((tracked.bytesRead * 100) / totalSize).toInt() else 0
                            val now = System.currentTimeMillis()
                            if (progress > lastProgress && progress <= 100 &&
                                (now - lastEmitTimeMs) >= 100
                            ) {
                                emit(progress)
                                lastProgress = progress
                                lastEmitTimeMs = now
                            }

                            entry = tarIn.nextEntry
                        }
                    }
                }
            }

            rootfsFile.delete()
            emit(100)
        } finally {
            try {
                wakeLock.release()
            } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    private fun checkAvailableSpace(dir: File, requiredBytes: Long) {
        val stat = StatFs(dir.absolutePath)
        val freeBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            stat.availableBytes
        } else {
            @Suppress("DEPRECATION")
            stat.availableBlocksLong * stat.blockSizeLong
        }
        if (freeBytes < requiredBytes) {
            val needMb = requiredBytes / (1024 * 1024)
            val haveMb = freeBytes / (1024 * 1024)
            throw IOException(
                "Insufficient storage space. Need at least ${needMb}MB free, but only ${haveMb}MB available."
            )
        }
    }
}
