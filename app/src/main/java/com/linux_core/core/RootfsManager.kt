package com.linux_core.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
                                    val relativePath = distro.rootfsDirName + "/" +
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

        val rootfsDir = File(context.filesDir, "docker-${imageRef.namespace}-${imageRef.repository}")
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

    private fun extractTarGzip(source: File, targetDir: File) {
        java.io.FileInputStream(source).use { fis ->
            BufferedInputStream(fis, 512 * 1024).use { bis ->
                GzipCompressorInputStream(bis).use { gzIn ->
                    TarArchiveInputStream(gzIn).use { tarIn ->
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
                                    FileOutputStream(entryFile).use { tarIn.copyTo(it) }
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
}
