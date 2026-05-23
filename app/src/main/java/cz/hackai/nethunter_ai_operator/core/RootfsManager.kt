package cz.hackai.nethunter_ai_operator.core

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

object RootfsManager {
    private const val ROOTFS_URL =
        "https://images.kali.org/nethunter/rootfs/kali-nethunter-rootfs-nano-arm64.tar.xz"
    private const val FILE_NAME = "kali-nethunter-rootfs.tar.xz"
    private const val TEMP_SUFFIX = ".tmp"
    private const val MIN_FREE_SPACE_BYTES = 1024L * 1024L * 1024L // 1 GB safety margin

    fun downloadRootfs(context: Context): Flow<Int> = flow {
        val rootDir = context.filesDir
        val nethunterDir = File(rootDir, "nethunter")
        if (!nethunterDir.exists()) {
            nethunterDir.mkdirs()
        }

        val targetFile = File(nethunterDir, FILE_NAME)
        val tempFile = File(nethunterDir, FILE_NAME + TEMP_SUFFIX)

        // Always re-download: delete any stale archive (partial or complete)

        // Clean up any stale partial files
        if (targetFile.exists()) targetFile.delete()
        tempFile.delete()

        // Check available storage space
        checkAvailableSpace(nethunterDir, MIN_FREE_SPACE_BYTES)

        val client = OkHttpClient()
        val request = Request.Builder().url(ROOTFS_URL).build()

        // Acquire wake lock to prevent Doze from killing the download
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

                // Use .use {} to ensure FileOutputStream is always closed,
                // even if an exception (e.g. network interruption, IOException)
                // is thrown mid-download. An orphaned FileOutputStream would
                // cause "A resource failed to call close" and may lock the
                // temp file, preventing renameTo from succeeding.
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
                            // Content-Length unknown (chunked transfer) â€” emit indeterminate sentinel
                            if (lastProgress == 0) {
                                emit(-1)
                                lastProgress = -1
                            }
                        }

                        bytes = inputStream.read(buffer)
                    }
                }
                // FileOutputStream is now guaranteed closed by .use { },
                // so renameTo can acquire the file without contention.

                // Validate download completeness against Content-Length
                if (totalLength > 0 && bytesCopied != totalLength) {
                    tempFile.delete()
                    throw IOException(
                        "Download incomplete: expected ${totalLength} bytes, got ${bytesCopied}"
                    )
                }

                // Atomic rename: temp ? target
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.delete()
                    throw IOException("Failed to finalize download â€” rename failed")
                }
                emit(100)
            }
        } finally {
            try {
                wakeLock.release()
            } catch (_: Exception) {
            }
            // Clean up temp file on any failure (success already renamed it)
            if (tempFile.exists()) tempFile.delete()
        }
    }.flowOn(Dispatchers.IO)

    fun isRootfsExtracted(context: Context): Boolean {

        val rootfsDir = File(context.filesDir, "kali-arm64")
        return rootfsDir.exists() && rootfsDir.isDirectory && File(rootfsDir, "bin/bash").exists()
    }

    /**
     * Delete the extracted rootfs directory and the downloaded archive,
     * allowing a clean re-download. Safe to call even if files don't exist.
     */
    fun deleteRootfs(context: Context): Boolean {

        val nethunterDir = File(context.filesDir, "nethunter")
        val rootfsDir = File(context.filesDir, "kali-arm64")
        val archiveFile = File(nethunterDir, FILE_NAME)
        val tempFile = File(nethunterDir, FILE_NAME + TEMP_SUFFIX)

        var success = true
        if (rootfsDir.exists()) success = rootfsDir.deleteRecursively() && success
        if (archiveFile.exists()) success = archiveFile.delete() && success
        if (tempFile.exists()) success = tempFile.delete() && success
        return success
    }

    fun extractRootfs(context: Context): Flow<Int> = flow {

        val nethunterDir = File(context.filesDir, "nethunter")
        val rootfsFile = File(nethunterDir, FILE_NAME)
        val extractDir = File(context.filesDir, "kali-arm64")

        if (!extractDir.exists()) {
            extractDir.mkdirs()
        }

        if (!rootfsFile.exists() || rootfsFile.length() == 0L) {
            throw IOException("Rootfs archive not found. Please download first.")
        }

        // Check available storage space (extraction needs ~2x archive size)
        val archiveSize = rootfsFile.length()
        val requiredSpace = archiveSize * 3 + MIN_FREE_SPACE_BYTES / 2
        checkAvailableSpace(nethunterDir, requiredSpace)

        emit(0)

        val totalSize = rootfsFile.length()
        var lastProgress = 0
        var lastEmitTimeMs = 0L

        // Acquire wake lock to prevent Doze from killing the extraction
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "RootfsManager:extract"
        )
        wakeLock.acquire(30 * 60 * 1000L) // 30-minute timeout

        try {
            // Progress-tracking wrapper around the file input stream
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

            val countingStream =
                CountingInputStream(BufferedInputStream(FileInputStream(rootfsFile)))

            countingStream.use { tracked ->
                XZCompressorInputStream(tracked).use { xzIn ->
                    TarArchiveInputStream(xzIn).use { tarIn ->
                        val canonicalBase = extractDir.canonicalPath

                        // Detect top-level prefix to strip (e.g. "kali-arm64/").
                        // Kali rootfs archives wrap everything in a single top-level dir;
                        // stripping it prevents nested extraction like .../kali-arm64/kali-arm64/.
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

                            // Security: prevent directory traversal
                            if (!canonicalDest.startsWith(canonicalBase + File.separator) &&
                                canonicalDest != canonicalBase
                            ) {
                                entry = tarIn.nextEntry
                                continue
                            }

                            val tarEntry = entry as? TarArchiveEntry
                            if (tarEntry != null && tarEntry.isDirectory) {
                                // Case 1: Directory entry â€” create the directory and skip
                                entryFile.mkdirs()
                                entry = tarIn.nextEntry
                                continue
                            }

                            if (tarEntry != null && (tarEntry.isSymbolicLink || tarEntry.isLink)) {
                                // Case 2: Symlink or hard link entry
                                entryFile.parentFile?.mkdirs()
                                val linkTarget = tarEntry.linkName
                                try {
                                    if (tarEntry.isSymbolicLink) {
                                        android.system.Os.symlink(
                                            linkTarget,
                                            entryFile.absolutePath
                                        )
                                    } else {
                                        android.system.Os.link(
                                            linkTarget,
                                            entryFile.absolutePath
                                        )
                                    }
                                } catch (_: Exception) {
                                    // Symlink/link creation may fail on non-rooted devices.
                                    // Fallback: create directory since most rootfs
                                    // symlinks target directories (bin?usr/bin etc.)
                                    entryFile.mkdirs()
                                }
                            } else {
                                // Case 3: Regular file
                                entryFile.parentFile?.mkdirs()
                                FileOutputStream(entryFile).use { out ->
                                    tarIn.copyTo(out)
                                }
                                entryFile.setExecutable(true, false)
                            }

                            // Emit progress based on compressed bytes consumed
                            val progress =
                                if (totalSize > 0) ((tracked.bytesRead * 100) / totalSize).toInt() else 0
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

            // Clean up the archive to save space
            rootfsFile.delete()
            emit(100)
        } finally {
            try {
                wakeLock.release()
            } catch (_: Exception) {
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Check that the filesystem containing [dir] has at least [requiredBytes] free.
     * Throws [IOException] with a descriptive message if not.
     */
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
                "Insufficient storage space. Need at least ${needMb}MB free, " +
                        "but only ${haveMb}MB available."
            )
        }
    }
}

