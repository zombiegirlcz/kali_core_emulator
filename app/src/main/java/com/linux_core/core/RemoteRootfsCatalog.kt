package com.linux_core.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Represents one distro plug-in from zombiegirlcz/ROOTFS-for-proot.
 *
 * Each plug-in is a shell script that defines:
 * - DISTRO_NAME / DISTRO_COMMENT
 * - TARBALL_URL[arch] / TARBALL_SHA256[arch]
 * - bootstrap.sh heredoc (inline)
 */
data class RemoteDistroScript(
    val scriptName: String,           // e.g. "kali.sh"
    val distroName: String,           // e.g. "Kali Linux"
    val distroComment: String,        // e.g. "Kali Linux official LXC rootfs"
    val tarballUrl: String,           // resolved for current arch
    val tarballSha256: String,        // resolved for current arch
    val architectures: List<String>,  // available archs in the script
    val bootstrapScript: String,      // full bootstrap.sh content from heredoc
    val commitSha: String             // latest commit SHA when fetched
) {
    /** Slug for directory naming: "kali", "debian-12-bookworm", etc. */
    val slug: String
        get() = scriptName.removeSuffix(".sh")

    /** Human-readable size hint — unknown for script-based entries. */
    val sizeHint: String
        get() = architectures.joinToString(", ")

    /** Whether we have a usable tarball URL for the current device arch. */
    val hasActiveArch: Boolean
        get() = tarballUrl.isNotEmpty()

    companion object {
        fun fromScript(
            scriptName: String,
            scriptContent: String,
            commitSha: String,
            currentArch: String
        ): RemoteDistroScript? {
            return try {
                val name = extractBashVar(scriptContent, "DISTRO_NAME") ?: scriptName.removeSuffix(".sh")
                val comment = extractBashVar(scriptContent, "DISTRO_COMMENT") ?: ""
                val urls = extractTarballMap(scriptContent, "TARBALL_URL")
                val sha256s = extractTarballMap(scriptContent, "TARBALL_SHA256")
                val archs = urls.keys.toList()
                val resolvedUrl = urls[currentArch] ?: urls.values.firstOrNull() ?: ""
                val resolvedSha = sha256s[currentArch] ?: sha256s.values.firstOrNull() ?: ""
                val bootstrap = extractBootstrapHeredoc(scriptContent)

                RemoteDistroScript(
                    scriptName = scriptName,
                    distroName = name,
                    distroComment = comment,
                    tarballUrl = resolvedUrl,
                    tarballSha256 = resolvedSha,
                    architectures = archs,
                    bootstrapScript = bootstrap,
                    commitSha = commitSha
                )
            } catch (e: Exception) {
                Log.e("RemoteDistroScript", "Failed to parse $scriptName: ${e.message}")
                null
            }
        }
    }
}

/**
 * GitHub client for zombiegirlcz/ROOTFS-for-proot.
 *
 * Workflow:
 * 1. List root `.sh` files in the repo.
 * 2. Download each script and parse it into [RemoteDistroScript].
 * 3. Cache results in SharedPreferences.
 */
object RemoteRootfsCatalog {
    private const val TAG = "RemoteRootfsCatalog"
    private const val GITHUB_REPO = "zombiegirlcz/ROOTFS-for-proot"
    private const val ROOT_CONTENTS_URL = "https://api.github.com/repos/$GITHUB_REPO/contents/"
    private const val PREFS_NAME = "remote_rootfs_catalog"
    private const val KEY_LAST_FETCH_MS = "last_fetch_ms"
    private const val KEY_LAST_SHA = "last_commit_sha"
    private const val CACHE_VALID_MS = 30 * 60 * 1000L // 30 minutes

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedScripts: List<RemoteDistroScript> = emptyList()

    fun getCachedScripts(): List<RemoteDistroScript> = cachedScripts

    fun fetchDistroScripts(
        context: Context,
        forceRefresh: Boolean = false
    ): Flow<List<RemoteDistroScript>> = flow {
        val scripts = fetchDistroScriptsSync(context, forceRefresh)
        emit(scripts)
    }.flowOn(Dispatchers.IO)

    fun fetchDistroScriptsSync(
        context: Context,
        forceRefresh: Boolean = false
    ): List<RemoteDistroScript> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Serve cache if fresh
            if (!forceRefresh) {
                val lastFetch = prefs.getLong(KEY_LAST_FETCH_MS, 0L)
                if (System.currentTimeMillis() - lastFetch < CACHE_VALID_MS &&
                    cachedScripts.isNotEmpty()
                ) {
                    return cachedScripts
                }
            }

            val scripts = fetchFromGitHub(context)
            cachedScripts = scripts
            prefs.edit()
                .putLong(KEY_LAST_FETCH_MS, System.currentTimeMillis())
                .putString(KEY_LAST_SHA, scripts.firstOrNull()?.commitSha ?: "")
                .apply()
            scripts
        } catch (e: Exception) {
            Log.e(TAG, "fetchDistroScriptsSync failed: ${e.message}")
            cachedScripts.takeIf { it.isNotEmpty() } ?: emptyList()
        }
    }

    // ─── GitHub API ──────────────────────────────────────────────

    private fun fetchFromGitHub(context: Context): List<RemoteDistroScript> {
        // 1. Get latest commit SHA
        val commitRequest = Request.Builder()
            .url("https://api.github.com/repos/$GITHUB_REPO/commits?per_page=1")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        val latestCommitSha: String = httpClient.newCall(commitRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub commits API ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty commits response")
            val arr = JSONArray(body)
            arr.getJSONObject(0).getString("sha")
        }

        Log.i(TAG, "Latest commit: $latestCommitSha")

        // 2. List root files
        val contentsRequest = Request.Builder()
            .url(ROOT_CONTENTS_URL)
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        val rootShFiles: List<JSONObject> = httpClient.newCall(contentsRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub contents API ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty contents response")
            val arr = JSONArray(body)
            val result = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("type") == "file" &&
                    obj.optString("name", "").endsWith(".sh") &&
                    obj.optString("name", "") != "README.md"
                ) {
                    result.add(obj)
                }
            }
            result
        }

        Log.i(TAG, "Found ${rootShFiles.size} distro scripts at repo root")

        // 3. Download and parse each script
        val scripts = mutableListOf<RemoteDistroScript>()
        val currentArch = detectCurrentArch()

        for (fileObj in rootShFiles) {
            val name = fileObj.optString("name", "")
            val downloadUrl = fileObj.optString("download_url", "")
            if (downloadUrl.isEmpty()) continue

            try {
                val scriptContent = downloadScript(downloadUrl)
                val parsed = RemoteDistroScript.fromScript(name, scriptContent, latestCommitSha, currentArch)
                if (parsed != null && parsed.hasActiveArch) {
                    scripts.add(parsed)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse $name: ${e.message}")
            }
        }

        return scripts.sortedBy { it.distroName.lowercase(Locale.getDefault()) }
    }

    private fun downloadScript(url: String): String {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download script: $url")
            return response.body?.string() ?: throw IOException("Empty script: $url")
        }
    }

    private fun detectCurrentArch(): String {
        val arch = System.getProperty("os.arch", "").lowercase(Locale.getDefault())
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
            arch.contains("arm") -> "arm"
            arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
            arch.contains("x86") || arch.contains("i386") || arch.contains("i686") -> "x86"
            else -> "aarch64" // default fallback
        }
    }
}

// ─── Bash parsing helpers ──────────────────────────────────────

private fun extractBashVar(script: String, varName: String): String? {
    // Match: VAR_NAME="value" or VAR_NAME='value'
    val regex = Regex("""^\s*$varName\s*=\s*"([^"]*)"""", RegexOption.MULTILINE)
    val match = regex.find(script)
    return match?.groupValues?.getOrNull(1)
}

private fun extractTarballMap(script: String, mapName: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    // Match: MAP_NAME['arch']="url"
    val regex = Regex("""^\s*$mapName\['([^']+)'\]\s*=\s*"([^"]*)"""", RegexOption.MULTILINE)
    for (match in regex.findAll(script)) {
        val arch = match.groupValues[1]
        val value = match.groupValues[2]
        result[arch] = value
    }
    return result
}

private fun extractBootstrapHeredoc(script: String): String {
    // Find the heredoc: cat <<'EOF' > bootstrap.sh ... EOF
    val startMarker = "cat <<'EOF' > bootstrap.sh"
    val endMarker = "EOF"
    
    val startIndex = script.indexOf(startMarker)
    if (startIndex == -1) return ""
    
    val contentStart = script.indexOf('\n', startIndex) + 1
    val endIndex = script.indexOf(endMarker, contentStart)
    if (endIndex == -1) return ""
    
    return script.substring(contentStart, endIndex).trim()
}
