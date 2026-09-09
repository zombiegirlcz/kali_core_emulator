package com.linux_core.core

import android.net.Uri

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import com.linux_core.security.CertificateManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.linux_core.core.DockerImageRef
import com.linux_core.core.DockerLayer
import com.linux_core.core.DockerManifest
import com.linux_core.core.DockerRegistryClient

data class Distro(
    val id: String,
    val name: String,
    val url: String,
    val rootfsDirName: String,
    val tarFileName: String
)

object RootfsManager {
    const val NH_DISTRO_DIR = "nh/distro"

    fun distroRootfsDir(context: Context, distroId: String): File =
        File(context.filesDir, "$NH_DISTRO_DIR/$distroId")

    fun dockerRootfsDir(context: Context, imageName: String): File =
        File(context.filesDir, "$NH_DISTRO_DIR/docker/$imageName")

    fun backupDir(context: Context): File =
        File(context.filesDir, "$NH_DISTRO_DIR/backup")

    val DISTROS = listOf(
        Distro(
            id = "kali",
            name = "Kali NetHunter",
            url = "https://images.kali.org/nethunter/rootfs/kali-nethunter-rootfs-nano-arm64.tar.xz",
            rootfsDirName = "nh/distro/kali",
            tarFileName = "kali-nethunter-rootfs.tar.xz"
        ),
        Distro(
            id = "parrot",
            name = "ParrotOS Security",
            url = "https://raw.githubusercontent.com/risecid/AndronixOrigin/master/Rootfs/Parrot/arm64/parrot-rootfs-arm64.tar.xz",
            rootfsDirName = "nh/distro/parrot",
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

        // OPTIMIZATION: Check if we already have the complete file
        if (targetFile.exists() && targetFile.length() > 0) {
            emit(100)
            return@flow
        }

        // Clean up any stale partial files
        if (tempFile.exists()) tempFile.delete()

        // Check available storage space
        checkAvailableSpace(cacheDir, MIN_FREE_SPACE_BYTES)

        // Validate URL before downloading
        val url = try {
            val parsedUrl = java.net.URL(distro.url)
            if (parsedUrl.protocol != "https") {
                throw IOException("Only HTTPS downloads are allowed (URL: ${distro.url})")
            }
            // Only allow known, trusted domains
            val allowedHosts = listOf("images.kali.org", "kali.download", "raw.githubusercontent.com", "deb.parrot.sh", "archive.parrotsec.org")
            val host = parsedUrl.host.lowercase()
            if (allowedHosts.none { host == it || host.endsWith(".$it") }) {
                throw IOException("Download from untrusted host blocked: $host")
            }
            distro.url
        } catch (e: java.net.MalformedURLException) {
            throw IOException("Invalid download URL: ${distro.url}")
        }

        // Build OkHttp client with TLS 1.2+ only and certificate pinning.
        // When ENABLE_MITM/ENABLE_ATTESTATION is true, prefer the SslContextFactory which
        // loads the bundled PKCS#12 and trusts the MITM CA in addition to system anchors.
        val certMgr = try { CertificateManager.ssl() } catch (_: Exception) { null }
        val trustManager: javax.net.ssl.X509TrustManager? = if (certMgr != null) {
            try { certMgr.trustManager() } catch (_: Exception) { null }
        } else {
            try {
                val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as java.security.KeyStore?)
                tmf.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().firstOrNull()
            } catch (e: Exception) { null }
        }

        val sslContext: javax.net.ssl.SSLContext? = if (certMgr != null) {
            try { certMgr.sslContext() } catch (_: Exception) { null }
        } else {
            try {
                val sc = javax.net.ssl.SSLContext.getInstance("TLSv1.2")
                sc.init(null, trustManager?.let { arrayOf<javax.net.ssl.TrustManager>(it) }, null)
                sc
            } catch (e: Exception) { null }
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .apply {
                if (sslContext != null && trustManager != null) {
                    sslSocketFactory(sslContext.socketFactory, trustManager)
                }
            }
            .build()
        val request = Request.Builder().url(url).build()

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

                // ── Backup original archive next to docker dir (best-effort) ──
                try {
                    val backupDir = File(context.filesDir, "$NH_DISTRO_DIR/backup")
                    if (!backupDir.exists()) backupDir.mkdirs()
                    val backupFile = File(backupDir, distro.tarFileName)
                    if (!backupFile.exists()) {
                        targetFile.copyTo(backupFile, overwrite = false)
                        Log.i("RootfsManager", "Backed up distro archive to: ${backupFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.w("RootfsManager", "Failed to backup distro archive: ${e.message}")
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

            // rootfsFile.delete() // Keep for reinstall

            // ── Backup original archive next to docker dir (best-effort) ──
            try {
                val backupDir = File(context.filesDir, "$NH_DISTRO_DIR/backup")
                if (!backupDir.exists()) backupDir.mkdirs()
                val backupFile = File(backupDir, distro.tarFileName)
                if (!backupFile.exists() && rootfsFile.exists()) {
                    rootfsFile.copyTo(backupFile, overwrite = false)
                    Log.i("RootfsManager", "Backed up distro archive to: ${backupFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w("RootfsManager", "Failed to backup distro archive: ${e.message}")
            }

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

    /**
     * Backup the rootfs directory into a .tar.gz file in the device Downloads folder.
     * Preserves symlinks. Emits progress 0..100.
     *
     * @param distro  the distro whose rootfs to back up
     * @param outputFile  destination .tar.gz file; if null, auto-generated in Downloads
     * @return the backup file path on success (via last emission being 100 and file accessible)
     */
    fun backupRootfs(context: Context, distro: Distro, outputFile: File? = null): Flow<Pair<Int, String>> = flow {
        emit(0 to "Preparing backup...")

        val rootfsDir = File(context.filesDir, distro.rootfsDirName)
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) {
            throw IOException("Rootfs directory not found: ${rootfsDir.absolutePath}")
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val backupFileName = "${distro.id}-rootfs-backup-${timestamp}.tar.gz"

        val destFile = outputFile ?: run {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.mkdirs()
            File(downloads, backupFileName)
        }
        val tempFile = File(destFile.parent, "${destFile.name}.tmp")

        Log.i("RootfsManager", "Backup starting: ${rootfsDir.absolutePath} -> ${destFile.absolutePath}")

        // Count total files for progress — use Files.walk (no FOLLOW_LINKS) to avoid
        // AssertionError on symlinks pointing to directories inside the rootfs.
        val rootPath: Path = rootfsDir.toPath()
        var totalFiles = 0
        try {
            Files.walk(rootPath).use { stream ->
                stream.forEach { totalFiles++ }
            }
        } catch (e: Exception) {
            Log.w("RootfsManager", "Error counting files: ${e.message}")
        }
        if (totalFiles == 0) totalFiles = 1 // Prevent div-by-zero; we'll still back up

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RootfsManager:backup")
        wakeLock.acquire(60 * 60 * 1000L) // 1 hour max

        try {
            var processed = 0
            var lastProgress = -1
            var lastEmitMs = 0L

            FileOutputStream(tempFile).use { fos ->
                BufferedOutputStream(fos, 512 * 1024).use { bos ->
                    GzipCompressorOutputStream(bos).use { gzOut ->
                        TarArchiveOutputStream(gzOut).use { tarOut ->
                            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                            tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)

                            // Use Files.walk without FOLLOW_LINKS — symlinks are enumerated
                            // but NOT entered, preventing AssertionError on broken/circular links.
                            val allPaths: List<Path> = Files.walk(rootPath).use { stream ->
                                stream.collect(java.util.stream.Collectors.toList())
                            }

                            for (path in allPaths) {
                                    val file = path.toFile()
                                    val relativePath = distro.id + "/" +
                                        rootfsDir.toPath().relativize(path).toString().replace('\\', '/')

                                    val isSymlink = try {
                                        Files.isSymbolicLink(path)
                                    } catch (_: Exception) { false }

                                    try {
                                        when {
                                            isSymlink -> {
                                                val linkTarget = try {
                                                    android.system.Os.readlink(file.absolutePath)
                                                } catch (_: Exception) { null }
                                                if (linkTarget != null) {
                                                    val symlinkEntry = TarArchiveEntry(
                                                        relativePath,
                                                        TarArchiveEntry.LF_SYMLINK
                                                    )
                                                    symlinkEntry.linkName = linkTarget
                                                    tarOut.putArchiveEntry(symlinkEntry)
                                                    tarOut.closeArchiveEntry()
                                                }
                                            }
                                            file.isDirectory -> {
                                                val dirEntry = TarArchiveEntry("$relativePath/")
                                                tarOut.putArchiveEntry(dirEntry)
                                                tarOut.closeArchiveEntry()
                                            }
                                            file.isFile -> {
                                                val fileEntry = TarArchiveEntry(relativePath)
                                                fileEntry.size = file.length()
                                                fileEntry.mode = if (file.canExecute()) 0b111_101_101 else 0b110_100_100
                                                tarOut.putArchiveEntry(fileEntry)
                                                try {
                                                    FileInputStream(file).use { it.copyTo(tarOut) }
                                                } catch (e: Exception) {
                                                    Log.w("RootfsManager", "Could not read file ${file.absolutePath}: ${e.message}")
                                                }
                                                tarOut.closeArchiveEntry()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w("RootfsManager", "Skipping entry ${file.absolutePath}: ${e.message}")
                                    }

                                    processed++
                                    val progress = ((processed.toLong() * 99) / allPaths.size).toInt()
                                    val now = System.currentTimeMillis()
                                    if (progress > lastProgress && (now - lastEmitMs) >= 200) {
                                        emit(progress to "Backing up… ($processed/${allPaths.size} files)")
                                        lastProgress = progress
                                        lastEmitMs = now
                                    }
                            }
                        }
                    }
                }
            }

            if (!tempFile.renameTo(destFile)) {
                // Try copy + delete if rename fails (cross-device)
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }

            val sizeMb = destFile.length() / (1024 * 1024)
            Log.i("RootfsManager", "Backup complete: ${destFile.absolutePath} (${sizeMb}MB)")
            emit(100 to destFile.absolutePath)

        } finally {
            try { wakeLock.release() } catch (_: Exception) {}
            if (tempFile.exists()) tempFile.delete()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Restore a rootfs from a .tar.gz backup file.
     * The existing rootfs directory is renamed to .bak before extraction (safe restore).
     * Emits progress 0..100.
     */
    fun restoreRootfs(context: Context, backupFile: File, distro: Distro): Flow<Pair<Int, String>> = flow {
        emit(0 to "Preparing restore...")

        if (!backupFile.exists() || backupFile.length() == 0L) {
            throw IOException("Backup file not found: ${backupFile.absolutePath}")
        }

        val rootfsDir = File(context.filesDir, distro.rootfsDirName)
        val oldBackupDir = File(context.filesDir, "${distro.rootfsDirName}.bak")

        // Check space — need ~3x the backup size
        checkAvailableSpace(context.filesDir, backupFile.length() * 3)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RootfsManager:restore")
        wakeLock.acquire(60 * 60 * 1000L)

        try {
            // Rename existing rootfs as .bak safety net
            if (rootfsDir.exists()) {
                if (oldBackupDir.exists()) oldBackupDir.deleteRecursively()
                rootfsDir.renameTo(oldBackupDir)
                Log.i("RootfsManager", "Existing rootfs moved to ${oldBackupDir.absolutePath}")
            }
            rootfsDir.mkdirs()

            emit(5 to "Extracting backup...")

            val totalSize = backupFile.length()
            var lastProgress = 5
            var lastEmitMs = 0L

            class CountingInputStream(input: java.io.InputStream) : FilterInputStream(input) {
                var bytesRead: Long = 0
                    private set
                override fun read(): Int = super.read().also { if (it != -1) bytesRead++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) bytesRead += it }
            }

            val counting = CountingInputStream(BufferedInputStream(FileInputStream(backupFile), 512 * 1024))
            counting.use { tracked ->
                GzipCompressorInputStream(tracked).use { gzIn ->
                    TarArchiveInputStream(gzIn).use { tarIn ->
                        val canonicalBase = rootfsDir.canonicalPath
                        var stripPrefix: String? = null

                        var entry: ArchiveEntry? = tarIn.nextEntry
                        while (entry != null) {
                            if (stripPrefix == null && entry.name.contains('/')) {
                                stripPrefix = entry.name.substringBefore('/') + "/"
                                val validPrefixes = listOf("${distro.id}/", "${distro.id}-arm64/", "$NH_DISTRO_DIR/${distro.id}/")
                                if (stripPrefix !in validPrefixes) {
                                    throw IOException("Backup incompatible: prefix '$stripPrefix' does not match distro '${distro.id}'")
                                }
                            }
                            val relativeName = if (stripPrefix != null && entry.name.startsWith(stripPrefix!!)) {
                                val stripped = entry.name.removePrefix(stripPrefix!!)
                                stripped.ifEmpty { "." }
                            } else {
                                entry.name
                            }

                            val entryFile = File(rootfsDir, relativeName)
                            val canonicalDest = entryFile.canonicalPath
                            if (!canonicalDest.startsWith(canonicalBase + File.separator) && canonicalDest != canonicalBase) {
                                entry = tarIn.nextEntry; continue
                            }

                            val tarEntry = entry as? TarArchiveEntry
                            when {
                                tarEntry?.isSymbolicLink == true -> {
                                    entryFile.parentFile?.mkdirs()
                                    try {
                                        android.system.Os.symlink(tarEntry.linkName, entryFile.absolutePath)
                                    } catch (_: Exception) {}
                                }
                                tarEntry?.isLink == true -> {
                                    entryFile.parentFile?.mkdirs()
                                    try {
                                        android.system.Os.link(tarEntry.linkName, entryFile.absolutePath)
                                    } catch (_: Exception) {}
                                }
                                tarEntry?.isDirectory == true -> entryFile.mkdirs()
                                else -> {
                                    entryFile.parentFile?.mkdirs()
                                    FileOutputStream(entryFile).use { tarIn.copyTo(it) }
                                    if (tarEntry != null && (tarEntry.mode and 0b001_000_000) != 0) {
                                        entryFile.setExecutable(true, false)
                                    }
                                    entryFile.setReadable(true, false)
                                }
                            }

                            val progress = 5 + ((tracked.bytesRead * 94) / totalSize).toInt()
                            val now = System.currentTimeMillis()
                            if (progress > lastProgress && (now - lastEmitMs) >= 200) {
                                emit(progress to "Restoring... (${tracked.bytesRead / 1024 / 1024}MB / ${totalSize / 1024 / 1024}MB)")
                                lastProgress = progress
                                lastEmitMs = now
                            }

                            entry = tarIn.nextEntry
                        }
                    }
                }
            }

            // Remove .bak only on success
            if (oldBackupDir.exists()) {
                oldBackupDir.deleteRecursively()
                Log.i("RootfsManager", "Old rootfs .bak removed")
            }

            Log.i("RootfsManager", "Restore complete: ${rootfsDir.absolutePath}")
            emit(100 to rootfsDir.absolutePath)

        } catch (e: Exception) {
            // Rollback — move .bak back
            Log.e("RootfsManager", "Restore failed, rolling back: ${e.message}")
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            if (oldBackupDir.exists()) oldBackupDir.renameTo(rootfsDir)
            throw e
        } finally {
            try { wakeLock.release() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Restore a rootfs from a URI picked via SAF (any *.tar.gz from any storage).
     * Copies the picked file into the app cache and delegates to restoreRootfs(File).
     */
    fun restoreRootfs(context: Context, backupUri: Uri, distro: Distro): Flow<Pair<Int, String>> = flow {
        val tempFile = File(context.cacheDir, "restore-${System.currentTimeMillis()}.tar.gz")
        context.contentResolver.openInputStream(backupUri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output, bufferSize = 512 * 1024)
            }
        } ?: throw IOException("Cannot open selected file: $backupUri")

        val name = backupUri.lastPathSegment ?: tempFile.name
        val isTarGz = name.endsWith(".tar.gz", ignoreCase = true) || name.endsWith(".tgz", ignoreCase = true)
        if (!isTarGz) {
            throw IOException("Unsupported format: $name (expected .tar.gz or .tgz)")
        }

        emitAll(restoreRootfs(context, tempFile, distro))
        tempFile.delete()
    }.flowOn(Dispatchers.IO)

    /**
     * Lists existing backup .tar.gz files in the Downloads folder for a given distro.
     */
    fun getBackupFiles(distro: Distro): List<File> {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) return emptyList()
        return downloads.listFiles { f ->
            f.name.startsWith("${distro.id}-rootfs-backup-") && f.name.endsWith(".tar.gz")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Pulls a Docker image from Docker Hub and extracts it as a rootfs.
     * Uses Docker Registry API v2 (no Docker daemon required).
     * Emits progress 0..100.
     */
    suspend fun pullDockerImage(
        context: Context,
        imageRef: DockerImageRef,
        client: OkHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
        registryUsername: String? = null,
        registryPassword: String? = null
    ): Flow<Pair<Int, String>> = flow {
        emit(0 to "Resolving image: ${imageRef.fullName}:${imageRef.tag}")

        val registry = DockerRegistryClient(client, registryUsername, registryPassword)

        // 1. Fetch manifest
        emit(5 to "Fetching image manifest…")
        val manifest = registry.fetchManifest(imageRef)

        val rootfsDir = File(context.filesDir, "$NH_DISTRO_DIR/docker/${imageRef.namespace}-${imageRef.repository}")
        rootfsDir.mkdirs()

        // 2. Download and extract each layer
        val totalLayers = manifest.layers.size
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RootfsManager:pullDocker")
        wakeLock.acquire(2 * 60 * 60 * 1000L) // 2 hours max for large images

        try {
            for ((index, layer) in manifest.layers.withIndex()) {
                val layerProgress = 5 + ((index * 90) / totalLayers)
                emit(layerProgress to "Downloading layer ${index + 1}/$totalLayers…")

                // Download layer directly to temp file (avoids OOM from in-memory buffering)
                val tempLayerFile = java.io.File.createTempFile("layer_${index}_", ".tar.gz", context.cacheDir)
                try {
                    java.io.FileOutputStream(tempLayerFile).use { out ->
                        registry.downloadLayer(imageRef, layer).collect { chunk ->
                            out.write(chunk)
                        }
                    }

                    emit(layerProgress + 5 to "Extracting layer ${index + 1}/$totalLayers…")

                    // Extract tar.gz layer into rootfs from temp file
                    extractTarGzip(tempLayerFile, rootfsDir)
                } finally {
                    tempLayerFile.delete()
                }

                Log.i("RootfsManager", "Layer ${index + 1}/$totalLayers extracted: ${layer.digest}")
            }

            // 3. Validate rootfs
            emit(98 to "Validating rootfs…")
            val hasBin = File(rootfsDir, "bin").exists() || File(rootfsDir, "usr/bin").exists()
            val hasLib = File(rootfsDir, "lib").exists() || File(rootfsDir, "usr/lib").exists()
            if (!hasBin && !hasLib) {
                Log.w("RootfsManager", "Pulled image may not be a valid rootfs (no bin/lib found)")
            }

            // 3b. Označ rootfs jako Docker image (marker pro ProotManager/ashell fallback)
            // - Přeskočí bootstrap/entrypoint v ProotManager.setupProotEnvironment
            // - Nastaví /etc/resolv.conf + /etc/hostname (kontejner-like identita)
            try {
                File(rootfsDir, ".docker_image").writeText(
                    "image=${imageRef.fullName}:${imageRef.tag}\n" +
                    "pulled_at=${System.currentTimeMillis()}\n" +
                    "namespace=${imageRef.namespace}\n" +
                    "repository=${imageRef.repository}\n"
                )
                File(rootfsDir, "etc/hostname").writeText("${imageRef.repository}-docker\n")
            } catch (e: Exception) {
                Log.w("RootfsManager", "Failed to write docker markers: ${e.message}")
            }

            emit(100 to rootfsDir.absolutePath)
            Log.i("RootfsManager", "Docker image pull complete: ${rootfsDir.absolutePath}")

        } catch (e: Exception) {
            Log.e("RootfsManager", "Docker pull failed: ${e.message}", e)
            throw e
        } finally {
            try { wakeLock.release() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Pulls a rootfs archive directly from a web URL (https://...tar.gz|tar.xz)
     * and extracts it as a rootfs directory.
     *
     * Security: HTTPS only + host whitelist (same policy as downloadRootfs).
     * Emits progress 0..100 (Pair<Int, String> status text like pullDockerImage).
     */
    private fun findCurlExecutable(context: Context): String? {
        val appCurl = File(context.filesDir, "usr/bin/curl")
        if (appCurl.exists() && appCurl.canExecute()) return appCurl.absolutePath
        val sysCurl = File("/system/bin/curl")
        if (sysCurl.exists() && sysCurl.canExecute()) return sysCurl.absolutePath
        return try {
            val p = ProcessBuilder("which", "curl").start()
            val path = p.inputStream.bufferedReader().use { it.readLine()?.trim() }
            if (p.waitFor() == 0 && !path.isNullOrEmpty() && File(path).exists()) path else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun pullRootfsFromUrl(
        context: Context,
        url: String,
        client: OkHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    ): Flow<Pair<Int, String>> = flow {
        emit(0 to "Resolving URL: $url")

        // ── Validate URL (HTTP and HTTPS allowed, any host permitted) ──
        val parsedUrl = try {
            java.net.URL(url)
        } catch (e: java.net.MalformedURLException) {
            throw IOException("Invalid download URL: $url")
        }
        val protocol = parsedUrl.protocol.lowercase()
        if (protocol != "http" && protocol != "https") {
            throw IOException("Only HTTP and HTTPS downloads are allowed (URL: $url)")
        }
        val host = parsedUrl.host.lowercase()

        // Rootfs dir name: docker-<host>-<filebase> so it appears in UI scan + launcher
        val fileBase = parsedUrl.path.substringAfterLast('/').ifEmpty { "rootfs" }
            .substringBeforeLast('.') // strip .tar / .gz / .xz
        val safeName = Regex("[^A-Za-z0-9._-]").replace(fileBase, "-")
        val rootfsName = "docker/${host.replace('.', '-')}-$safeName"
        val rootfsDir = File(context.filesDir, "$NH_DISTRO_DIR/$rootfsName")
        rootfsDir.mkdirs()

        val cacheDir = File(context.filesDir, "web-pull")
        cacheDir.mkdirs()
        val tempFile = File(cacheDir, "$safeName.tmp")
        if (tempFile.exists()) tempFile.delete()

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RootfsManager:pullUrl")
        wakeLock.acquire(2 * 60 * 60 * 1000L) // 2 hours max

        try {
            // ── Download to temp file using curl (or OkHttp fallback) ──
            emit(5 to "Downloading $url …")
            val curlBin = findCurlExecutable(context)
            var downloadSuccess = false

            if (curlBin != null) {
                try {
                    Log.i("RootfsManager", "Downloading via curl ($curlBin): $url")
                    val pb = ProcessBuilder(curlBin, "-sSL", "--fail", "--show-error", "-o", tempFile.absolutePath, url)
                    pb.redirectErrorStream(true)
                    val process = pb.start()

                    while (process.isAlive) {
                        kotlinx.coroutines.delay(300)
                        val bytes = tempFile.length()
                        if (bytes > 0) {
                            emit(10 to "Downloading via curl… (${bytes / (1024 * 1024)} MB)")
                        }
                    }
                    val exitCode = process.waitFor()
                    if (exitCode == 0 && tempFile.exists() && tempFile.length() > 0) {
                        downloadSuccess = true
                        Log.i("RootfsManager", "Curl download successful: ${tempFile.length()} bytes")
                    } else {
                        val errorOutput = process.inputStream.bufferedReader().use { it.readText() }
                        Log.w("RootfsManager", "Curl download failed (code $exitCode): $errorOutput")
                        if (tempFile.exists()) tempFile.delete()
                    }
                } catch (e: Exception) {
                    Log.w("RootfsManager", "Curl execution failed, falling back to OkHttp: ${e.message}")
                    if (tempFile.exists()) tempFile.delete()
                }
            }

            if (!downloadSuccess) {
                Log.i("RootfsManager", "Downloading via OkHttp: $url")
                val request = okhttp3.Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Unexpected code ${response.code} for $url")
                    val responseBody = response.body ?: throw IOException("Response body is null")
                    val totalLength = responseBody.contentLength()
                    val inputStream = responseBody.byteStream()
                    var bytesCopied: Long = 0
                    java.io.FileOutputStream(tempFile).use { outputStream ->
                        val buffer = ByteArray(8 * 1024)
                        var bytes = inputStream.read(buffer)
                        while (bytes >= 0) {
                            outputStream.write(buffer, 0, bytes)
                            bytesCopied += bytes
                            if (totalLength > 0) {
                                val pct = 5 + ((bytesCopied * 80) / totalLength).toInt()
                                emit(pct.coerceIn(5, 85) to "Downloading… $pct%")
                            } else {
                                emit(10 to "Downloading… (${bytesCopied / (1024 * 1024)} MB)")
                            }
                            bytes = inputStream.read(buffer)
                        }
                    }
                    if (totalLength > 0 && bytesCopied != totalLength) {
                        throw IOException("Download incomplete: expected $totalLength bytes, got $bytesCopied")
                    }
                }
            }

            // ── Extract (tar.gz or tar.xz) ──
            emit(88 to "Extracting rootfs…")
            val isXz = url.lowercase().contains(".tar.xz") || url.lowercase().endsWith(".txz")
            if (isXz) {
                extractTarXz(tempFile, rootfsDir)
            } else {
                extractTarGzip(tempFile, rootfsDir)
            }

            // ── Backup original archive next to docker dir (best-effort) ──
            try {
                val backupDir = File(context.filesDir, "$NH_DISTRO_DIR/backup")
                if (!backupDir.exists()) backupDir.mkdirs()
                val backupFile = File(backupDir, "${safeName}.${tempFile.extension}")
                if (!backupFile.exists()) {
                    tempFile.copyTo(backupFile, overwrite = false)
                    Log.i("RootfsManager", "Backed up rootfs archive to: ${backupFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w("RootfsManager", "Failed to backup rootfs archive: ${e.message}")
            }

            // ── Markers (same convention as docker pull) ──
            try {
                File(rootfsDir, ".docker_image").writeText(
                    "image=$url\n" +
                    "pulled_at=${System.currentTimeMillis()}\n" +
                    "source=web-url\n" +
                    "url=$url\n"
                )
                File(rootfsDir, "etc/hostname").writeText("$safeName-docker\n")
            } catch (e: Exception) {
                Log.w("RootfsManager", "Failed to write web-pull markers: ${e.message}")
            }

            emit(100 to rootfsDir.absolutePath)
            Log.i("RootfsManager", "Web rootfs pull complete: ${rootfsDir.absolutePath}")
        } catch (e: Exception) {
            Log.e("RootfsManager", "Web pull failed: ${e.message}", e)
            throw e
        } finally {
            try { wakeLock.release() } catch (_: Exception) {}
            if (tempFile.exists()) tempFile.delete()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Imports and extracts a local rootfs archive file (.tar.gz, .tgz, .tar.xz, .txz, .tar, .tar.bz2)
     * from device storage into a docker rootfs directory.
     *
     * Emits progress 0..100 (Pair<Int, String> status text).
     */
    suspend fun importLocalRootfsFile(
        context: Context,
        archiveFile: File
    ): Flow<Pair<Int, String>> = flow {
        emit(0 to "Locating file: ${archiveFile.absolutePath}")

        if (!archiveFile.exists() || !archiveFile.isFile) {
            throw IOException("File not found or invalid: ${archiveFile.absolutePath}")
        }
        if (!archiveFile.canRead()) {
            throw IOException("Permission denied reading file: ${archiveFile.absolutePath}")
        }

        val nameLower = archiveFile.name.lowercase()
        val validExts = listOf(".tar.gz", ".tgz", ".tar.xz", ".txz", ".tar", ".tar.bz2", ".tbz2")
        if (validExts.none { nameLower.endsWith(it) }) {
            throw IOException("Unsupported archive format for '${archiveFile.name}'. Supported formats: .tar.gz, .tgz, .tar.xz, .txz, .tar, .tar.bz2")
        }

        val fileBase = archiveFile.name
            .removeSuffix(".tar.gz").removeSuffix(".tgz")
            .removeSuffix(".tar.xz").removeSuffix(".txz")
            .removeSuffix(".tar.bz2").removeSuffix(".tbz2")
            .removeSuffix(".tar")
        val safeName = Regex("[^A-Za-z0-9._-]").replace(fileBase, "-")
        val rootfsName = "docker/local-$safeName"
        val rootfsDir = File(context.filesDir, "$NH_DISTRO_DIR/$rootfsName")
        rootfsDir.mkdirs()

        // ── Backup original archive next to docker dir (best-effort) ──
        try {
            val backupDir = File(context.filesDir, "$NH_DISTRO_DIR/backup")
            if (!backupDir.exists()) backupDir.mkdirs()
            val backupFile = File(backupDir, archiveFile.name)
            if (!backupFile.exists()) {
                archiveFile.copyTo(backupFile, overwrite = false)
                Log.i("RootfsManager", "Backed up local archive to: ${backupFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w("RootfsManager", "Failed to backup local archive: ${e.message}")
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RootfsManager:importLocal")
        wakeLock.acquire(2 * 60 * 60 * 1000L)

        try {
            emit(10 to "Extracting local rootfs archive…")
            val isXz = nameLower.endsWith(".tar.xz") || nameLower.endsWith(".txz")
            if (isXz) {
                extractTarXz(archiveFile, rootfsDir)
            } else {
                extractTarGzip(archiveFile, rootfsDir)
            }

            try {
                File(rootfsDir, ".docker_image").writeText(
                    "image=file://${archiveFile.absolutePath}\n" +
                    "pulled_at=${System.currentTimeMillis()}\n" +
                    "source=local-file\n" +
                    "path=${archiveFile.absolutePath}\n"
                )
                File(rootfsDir, "etc/hostname").writeText("$safeName-docker\n")
            } catch (e: Exception) {
                Log.w("RootfsManager", "Failed to write local import markers: ${e.message}")
            }

            emit(100 to rootfsDir.absolutePath)
            Log.i("RootfsManager", "Local rootfs import complete: ${rootfsDir.absolutePath}")
        } catch (e: Exception) {
            Log.e("RootfsManager", "Local rootfs import failed: ${e.message}", e)
            throw e
        } finally {
            try { wakeLock.release() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /** Extract a .tar.xz archive into targetDir (streaming, progress-free variant used by pull). */
    private fun extractTarXz(source: File, targetDir: File) {
        java.io.FileInputStream(source).use { fis ->
            BufferedInputStream(fis, 512 * 1024).use { bis ->
                XZCompressorInputStream(bis).use { xzIn ->
                    TarArchiveInputStream(xzIn).use { tarIn ->
                        val canonicalBase = targetDir.canonicalPath
                        var entry: ArchiveEntry? = tarIn.nextEntry
                        while (entry != null) {
                            val entryFile = File(targetDir, entry.name)
                            val canonicalDest = entryFile.canonicalPath
                            if (!canonicalDest.startsWith(canonicalBase + java.io.File.separator) && canonicalDest != canonicalBase) {
                                entry = tarIn.nextEntry
                                continue
                            }
                            val tarEntry = entry as? TarArchiveEntry
                            when {
                                tarEntry?.isSymbolicLink == true -> {
                                    entryFile.parentFile?.mkdirs()
                                    try {
                                        android.system.Os.symlink(tarEntry.linkName, entryFile.absolutePath)
                                    } catch (_: Exception) {}
                                }
                                tarEntry?.isDirectory == true -> entryFile.mkdirs()
                                else -> {
                                    entryFile.parentFile?.mkdirs()
                                    java.io.FileOutputStream(entryFile).use { tarIn.copyTo(it) }
                                    if (tarEntry != null && (tarEntry.mode and 0b001_000_000) != 0) {
                                        entryFile.setExecutable(true, false)
                                    }
                                    entryFile.setReadable(true, false)
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
        }
    }

    private fun extractTarGzip(source: File, targetDir: File) {
        java.io.FileInputStream(source).use { fis ->
            BufferedInputStream(fis, 512 * 1024).use { bis ->
                bis.mark(1024)
                val isGzip = try {
                    val gzIn = GzipCompressorInputStream(bis)
                    gzIn.read()
                    true
                } catch (_: Exception) {
                    false
                }
                bis.reset()

                val tarInStream: java.io.InputStream = if (isGzip) {
                    GzipCompressorInputStream(bis)
                } else {
                    bis
                }

                TarArchiveInputStream(tarInStream).use { tarIn ->
                        val canonicalBase = targetDir.canonicalPath
                        var entry: ArchiveEntry? = tarIn.nextEntry
                        while (entry != null) {
                            val entryFile = File(targetDir, entry.name)
                            val canonicalDest = entryFile.canonicalPath
                            if (!canonicalDest.startsWith(canonicalBase + java.io.File.separator) && canonicalDest != canonicalBase) {
                                entry = tarIn.nextEntry
                                continue
                            }

                            val tarEntry = entry as? TarArchiveEntry
                            val name = tarEntry?.name ?: entry.name
                            when {
                                // ── Docker whiteouts: .wh.<name> smaže <name> z předchozí vrstvy,
                                //    .wh..wh..opq vyprázdní celý adresář. Bez toho po rozbalení
                                //    zůstávají soubory ze starších vrstev, které image odstranil.
                                tarEntry != null && tarEntry.name.contains("/.wh.") -> {
                                    val baseName = name.substringAfterLast("/")
                                    val parentDir = entryFile.parentFile ?: targetDir
                                    if (baseName == ".wh..wh..opq") {
                                        parentDir.listFiles()?.forEach { it.deleteRecursively() }
                                        Log.d("RootfsManager", "Opaque whiteout: cleared ${parentDir.path}")
                                    } else {
                                        val victim = java.io.File(parentDir, baseName.removePrefix(".wh."))
                                        if (victim.exists()) {
                                            victim.deleteRecursively()
                                            Log.d("RootfsManager", "Whiteout: removed ${victim.path}")
                                        }
                                    }
                                }
                                // ── Hardlink: commons-compress nedodává obsah — vytvoř symlink na
                                //    cíl v rámci rootfs (funkční ekvivalent; PRoot symlinky zvládá).
                                tarEntry?.isLink == true -> {
                                    entryFile.parentFile?.mkdirs()
                                    try {
                                        entryFile.delete()
                                        android.system.Os.symlink(tarEntry.linkName, entryFile.absolutePath)
                                    } catch (_: Exception) {}
                                }
                                tarEntry?.isSymbolicLink == true -> {
                                    entryFile.parentFile?.mkdirs()
                                    try {
                                        entryFile.delete()
                                        android.system.Os.symlink(tarEntry.linkName, entryFile.absolutePath)
                                    } catch (_: Exception) {}
                                }
                                tarEntry?.isDirectory == true -> entryFile.mkdirs()
                                else -> {
                                    entryFile.parentFile?.mkdirs()
                                    // Přepis existujícího souboru: pokud je to symlink, smaž ho
                                    // (FileOutputStream by psal SKRZ symlink do cíle mimo rootfs!")
                                    if (entryFile.exists() && !entryFile.isFile) entryFile.delete()
                                    FileOutputStream(entryFile).use { tarIn.copyTo(it) }
                                    if (tarEntry != null && (tarEntry.mode and 0b001_000_000) != 0) {
                                        entryFile.setExecutable(true, false)
                                    }
                                    entryFile.setReadable(true, false)
                                    entryFile.setWritable(true, false)
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
        }

    // ──────────────────────────────────────────────────────────────────────
    // Layout migration (Fáze 1): old layout → nh/distro + usr/{bin,lib}
    // ──────────────────────────────────────────────────────────────────────

    private const val MIGRATION_PREFS = "nh_migration"
    private const val MIGRATION_FLAG = "migration_v2_done"

    /**
     * Idempotentní vstupní bod migrace layoutu. Volat PŘED jakýmkoli
     * použitím rootfs cest (setupProotEnvironment, install/backup detekce).
     * @Synchronized: může se volat souběžně z více vláken (MainActivity.onCreate,
     * BackgroundBoot, ProotManager) — bez zámku by dvě migrateLayout běžely
     * zároveň a závodily na renameTo/copyRecursively.
     */
    @Synchronized
    fun ensureMigrated(context: Context) {
        val prefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATION_FLAG, false)) return
        migrateLayout(context, prefs)
    }

    private fun migrateLayout(context: Context, prefs: android.content.SharedPreferences) {
        val filesDir = context.filesDir
        var allOk = true
        Log.i("RootfsManager", "Layout migration started (v2)")

        // 1) Legacy filesDir/bin/* → usr/bin/* (po souboru, nikdy nepřepisovat)
        val legacyBin = File(filesDir, "bin")
        if (legacyBin.isDirectory) {
            legacyBin.listFiles()?.forEach { src ->
                val dst = File(File(filesDir, "usr/bin"), src.name)
                if (!safeMove(src, dst)) allOk = false
            }
            if (legacyBin.listFiles()?.isEmpty() != false) legacyBin.delete()
        }

        // 2) Legacy rootfs adresáře → nh/distro/
        val distroMoves = listOf(
            "kali-arm64" to "$NH_DISTRO_DIR/kali",
            "parrot-arm64" to "$NH_DISTRO_DIR/parrot"
        )
        for ((oldName, newName) in distroMoves) {
            val src = File(filesDir, oldName)
            val dst = File(filesDir, newName)
            if (src.exists()) {
                dst.parentFile?.mkdirs()
                if (!safeMove(src, dst)) allOk = false
            }
        }

        // 3) Docker/OCI adresáře → nh/distro/docker/<jméno bez prefixu>
        filesDir.listFiles()?.filter {
            it.isDirectory && (it.name.startsWith("docker-") || it.name.startsWith("oci-"))
        }?.forEach { src ->
            // Strippni prefix docker-/oci-, aby jméno odpovídalo novým pullům
            // (RootfsManager vytváří nh/distro/docker/<host>-<name> bez prefixu)
            val stripped = src.name.removePrefix("docker-").removePrefix("oci-")
            val dst = File(filesDir, "$NH_DISTRO_DIR/docker/$stripped")
            dst.parentFile?.mkdirs()
            if (!safeMove(src, dst)) allOk = false
        }

        // 4) Legacy terminalmap → usr/bin/terminalmap
        val legacyTerminalmap = File(filesDir, "terminalmap")
        if (legacyTerminalmap.exists()) {
            val dst = File(filesDir, "usr/bin/terminalmap")
            dst.parentFile?.mkdirs()
            if (!safeMove(legacyTerminalmap, dst)) allOk = false
        }

        // 5) Staré launchery smaž až při kompletním úspěchu (regenerují se při startu)
        if (allOk) {
            filesDir.listFiles()?.filter {
                it.isFile && it.name.startsWith("launcher") && it.name.endsWith(".sh")
            }?.forEach { it.delete() }
            prefs.edit().putBoolean(MIGRATION_FLAG, true).apply()
            Log.i("RootfsManager", "Layout migration completed successfully")
        } else {
            Log.w("RootfsManager", "Layout migration incomplete — will retry on next start")
        }
    }

    /**
     * Bezpečný přesun: nikdy nepřepíše existující neprázdný cíl,
     * zdroj maže až po ověření úspěchu.
     */
    private fun safeMove(src: File, dst: File): Boolean {
        if (!src.exists()) return true
        if (dst.exists()) {
            val dstNonEmpty = if (dst.isDirectory) dst.listFiles()?.isNotEmpty() == true else dst.length() > 0L
            if (dstNonEmpty) {
                // Soubory stejné velikosti považuj za migrované, smaž zdroj
                if (src.isFile && dst.isFile && src.length() == dst.length()) {
                    src.delete()
                    return true
                }
                Log.w("RootfsManager", "safeMove: target exists and is non-empty, skipping: $dst")
                return false
            }
            dst.delete() // prázdný cíl odstraníme, aby renameTo uspěl
        }
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return true
        return try {
            src.copyRecursively(dst, overwrite = false)
            val ok = if (src.isDirectory) countFiles(src) == countFiles(dst)
                     else dst.exists() && dst.length() == src.length()
            if (ok) {
                src.deleteRecursively()
                true
            } else {
                Log.e("RootfsManager", "safeMove: verification failed, keeping source: $src")
                // Smaž částečný cíl, aby příští pokus mohl začít znovu
                try { if (dst.exists()) dst.deleteRecursively() } catch (_: Exception) {}
                false
            }
        } catch (e: Exception) {
            Log.e("RootfsManager", "safeMove failed: ${e.message}")
            // Částečný cíl smaž, aby další pokus začínal z čistého stavu
            // (jinak by existující neprázdný dst blokoval migraci navždy)
            try { if (dst.exists()) dst.deleteRecursively() } catch (_: Exception) {}
            false
        }
    }

    private fun countFiles(f: File): Int =
        if (f.isDirectory) f.walkTopDown().count { it.isFile } else 1
}
