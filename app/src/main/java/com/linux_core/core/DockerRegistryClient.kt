package com.linux_core.core

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.Dispatchers.IO
import okhttp3.Credentials

data class DockerLayer(
    val digest: String,
    val size: Long,
    val mediaType: String
) {
    val isTarGzip: Boolean
        get() = mediaType.contains("tar+gzip") || mediaType.contains("tar+gzip")
}

data class DockerManifest(
    val layers: List<DockerLayer>,
    val config: DockerLayer?
)

/**
 * Docker Registry HTTP client v2 with full OAuth2 Bearer token support.
 *
 * Docker Hub vyžaduje autentizaci pro každý request (i pro veřejné obrazy).
 * Standardní flow:
 *   1. Request na registry → 401 + Www-Authenticate header
 *   2. GET na auth server s realm/service/scope → { "token": "..." }
 *   3. Retry původního requestu s Authorization: Bearer <token>
 *
 * @param httpClient  OkHttpClient instance (časový limit, proxy atd.)
 * @param username    Volitelné přihlašovací jméno pro privátní registry
 * @param password    Volitelné heslo / PAT pro privátní registry
 */
class DockerRegistryClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val username: String? = null,
    private val password: String? = null
) {
    // Cache tokenů – klíč = scope, hodnota = token + expirace (null = platí navždy)
    private data class CachedToken(
        val token: String,
        val expiresAtMs: Long?   // null = neexpiruje
    ) {
        val isValid: Boolean
            get() = expiresAtMs == null || System.currentTimeMillis() < expiresAtMs
    }

    private val tokenCache = mutableMapOf<String, CachedToken>()
    private val tokenLock = ReentrantReadWriteLock()

    // ---------------------------------------------------------------
    // Veřejné API
    // ---------------------------------------------------------------

    /**
     * Stáhne manifest obrazu z Docker Registry.
     * Automaticky řeší OAuth2 token exchange při 401.
     */
    fun fetchManifest(imageRef: DockerImageRef): DockerManifest {
        val url = imageRef.manifestUrl("application/vnd.docker.distribution.manifest.v2+json")
        Log.i("DockerRegistry", "Fetching manifest: $url")

        val request = Request.Builder()
            .url(url)
            // Accept both single manifest and manifest list (fat manifest for multi-arch)
            .addHeader("Accept",
                "application/vnd.docker.distribution.manifest.v2+json," +
                " application/vnd.docker.distribution.manifest.list.v2+json," +
                " application/vnd.oci.image.manifest.v1+json," +
                " application/vnd.oci.image.index.v1+json")
            .build()

        val responseBody = executeWithAuth(imageRef, request) { response ->
            val body = response.body?.string() ?: throw IOException("Empty manifest response")
            JSONObject(body)
        }

        return parseManifest(imageRef, responseBody)
    }

    /**
     * Stáhne jednu vrstvu (layer) obrazu jako proud bytů.
     * Automaticky řeší OAuth2 token exchange při 401.
     */
    fun downloadLayer(imageRef: DockerImageRef, layer: DockerLayer): Flow<ByteArray> = flow {
        val url = imageRef.blobUrl(layer.digest)
        Log.i("DockerRegistry", "Downloading layer: ${layer.digest} ($url)")

        val response = executeLayerRequest(imageRef, url)
        var lastEmitMs = 0L

        val body = response.body ?: throw IOException("Empty layer body")
        val totalBytes = body.contentLength()
        var downloaded = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                downloaded += read
                emit(buffer.copyOf(read))

                val now = System.currentTimeMillis()
                if (now - lastEmitMs >= 500 && totalBytes > 0) {
                    val progress = ((downloaded * 100) / totalBytes).toInt()
                    Log.d("DockerRegistry", "Layer download: $progress%")
                    lastEmitMs = now
                }
            }
        }

        Log.i("DockerRegistry", "Layer downloaded: ${downloaded} bytes")
    }.flowOn(IO)

    // ---------------------------------------------------------------
    // OAuth2 autentizace
    // ---------------------------------------------------------------

    /**
     * Provede request a při 401 automaticky získá token a retry.
     */
    private fun <T> executeWithAuth(
        imageRef: DockerImageRef,
        request: Request,
        parser: (okhttp3.Response) -> T
    ): T {
        val token = getOrObtainToken(imageRef)

        val requestBuilder = request.newBuilder()
        if (token != null) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }
        val authenticatedRequest = requestBuilder.build()

        httpClient.newCall(authenticatedRequest).execute().use { response ->
            if (response.code == 401 && token == null) {
                // První pokus – token jsme neměli, získat ho a retry
                Log.d("DockerRegistry", "Got 401, obtaining token…")
                val newToken = obtainTokenFromChallenge(imageRef, response)
                response.close()
                val retryReq = request.newBuilder()
                    .addHeader("Authorization", "Bearer $newToken")
                    .build()
                return httpClient.newCall(retryReq).execute().use { retryResp ->
                    if (!retryResp.isSuccessful) {
                        throw IOException("Failed after auth retry: ${retryResp.code} ${retryResp.message}")
                    }
                    parser(retryResp)
                }
            } else if (response.code == 401 && token != null) {
                // Token jsme měli, ale je neplatný – zkusit refresh
                Log.d("DockerRegistry", "Got 401 with cached token, refreshing…")
                invalidateToken(imageRef.authScope)
                val newToken = getOrObtainToken(imageRef)!!
                response.close()
                val retryReq = request.newBuilder()
                    .addHeader("Authorization", "Bearer $newToken")
                    .build()
                return httpClient.newCall(retryReq).execute().use { retryResp ->
                    if (!retryResp.isSuccessful) {
                        throw IOException("Failed after token refresh: ${retryResp.code} ${retryResp.message}")
                    }
                    parser(retryResp)
                }
            }

            if (!response.isSuccessful) {
                throw IOException("Failed: ${response.code} ${response.message}")
            }
            return parser(response)
        }
    }

    /**
     * Získá token z cache, nebo ho obstará z auth serveru.
     */
    private fun getOrObtainToken(imageRef: DockerImageRef): String? {
        val scope = imageRef.authScope

        // Zkusit cache
        tokenLock.read {
            tokenCache[scope]?.takeIf { it.isValid }?.let { return it.token }
        }

        // Získat nový token
        return tokenLock.write {
            // Double-check po získání zámku
            val existing = tokenCache[scope]
            if (existing != null && existing.isValid) {
                return@write existing.token
            }

            try {
                val newToken = fetchTokenFromAuthServer(imageRef)
                // Token typicky platí 60 minut, cache na 55 minut
                val expiresAt = System.currentTimeMillis() + 55 * 60 * 1000L
                tokenCache[scope] = CachedToken(newToken, expiresAt)
                Log.i("DockerRegistry", "Obtained new token for scope=$scope")
                newToken
            } catch (e: Exception) {
                Log.w("DockerRegistry", "Failed to obtain token, continuing without auth: ${e.message}")
                null
            }
        }
    }

    /**
     * Získá token z auth serveru Docker Hub.
     * Pro veřejné registry stačí GET na auth.docker.io.
     * Pro privátní registry přidá Basic autentizaci.
     */
    private fun fetchTokenFromAuthServer(imageRef: DockerImageRef): String {
        val scope = imageRef.authScope
        val service = imageRef.authService
        val realm = if (imageRef.authRealm.startsWith("http://") || imageRef.authRealm.startsWith("https://")) {
            imageRef.authRealm
        } else {
            "${imageRef.scheme}://${imageRef.authRealm}"
        }

        val url = "$realm?service=$service&scope=$scope"
        Log.d("DockerRegistry", "Requesting token: $url")

        val requestBuilder = Request.Builder().url(url)

        // Pokud máme credentials, přidat Basic auth
        if (username != null && password != null) {
            requestBuilder.addHeader("Authorization", Credentials.basic(username, password))
            Log.d("DockerRegistry", "Using basic auth for user=$username")
        }

        val request = requestBuilder.build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: "<empty>"
                throw IOException("Auth server error: ${response.code} – $body")
            }

            val json = response.body?.string()?.let { JSONObject(it) }
                ?: throw IOException("Empty auth response")

            // Docker Hub vrací "token" (někdy i "access_token")
            val token = json.optString("token")
                .takeIf { it.isNotEmpty() }
                ?: json.optString("access_token")
                ?.takeIf { it.isNotEmpty() }
                ?: throw IOException("No token in auth response: ${json.toString()}")

            Log.d("DockerRegistry", "Token obtained (${token.take(20)}…)")
            return token
        }
    }

    /**
     * Parsuje Www-Authenticate header z 401 odpovědi a získá token.
     * Použije se, pokud standardní GET na auth.docker.io selže.
     */
    private fun obtainTokenFromChallenge(imageRef: DockerImageRef, response: okhttp3.Response): String {
        val authHeader = response.header("Www-Authenticate")
            ?: throw IOException("Got 401 but no Www-Authenticate header – cannot obtain token")

        Log.d("DockerRegistry", "Www-Authenticate: $authHeader")

        // Parsování: Bearer realm="...",service="...",scope="..."
        // Challenge z registru má přednost (funguje i pro custom registry).
        val defaultRealm = if (imageRef.authRealm.startsWith("http://") || imageRef.authRealm.startsWith("https://")) {
            imageRef.authRealm
        } else {
            "${imageRef.scheme}://${imageRef.authRealm}"
        }
        val realm = extractAuthParam(authHeader, "realm") ?: defaultRealm
        val service = extractAuthParam(authHeader, "service") ?: imageRef.authService
        val scope = extractAuthParam(authHeader, "scope") ?: imageRef.authScope

        val url = "$realm?service=$service&scope=$scope"
        Log.d("DockerRegistry", "Obtaining token via challenge: $url")

        val requestBuilder = Request.Builder().url(url)
        if (username != null && password != null) {
            requestBuilder.addHeader("Authorization", Credentials.basic(username, password))
        }

        httpClient.newCall(requestBuilder.build()).execute().use { tokenResp ->
            if (!tokenResp.isSuccessful) {
                val body = tokenResp.body?.string() ?: "<empty>"
                throw IOException("Token endpoint error: ${tokenResp.code} – $body")
            }

            val json = tokenResp.body?.string()?.let { JSONObject(it) }
                ?: throw IOException("Empty token response")

            val token = json.optString("token")
                .takeIf { it.isNotEmpty() }
                ?: json.optString("access_token")
                ?: throw IOException("No token in challenge response")

            // Uložit do cache
            val expiresAt = System.currentTimeMillis() + 55 * 60 * 1000L
            tokenLock.write {
                tokenCache[imageRef.authScope] = CachedToken(token, expiresAt)
            }

            return token
        }
    }

    /**
     * Zneplatní token pro daný scope (např. při 401 i s tokenem).
     */
    private fun invalidateToken(scope: String) {
        tokenLock.write {
            tokenCache.remove(scope)
        }
    }

    // ---------------------------------------------------------------
    // Helpery
    // ---------------------------------------------------------------

    /**
     * Parsuje parametr z Www-Authenticate headeru.
     * Příklad: Bearer realm="...",service="...",scope="..."
     */
    private fun extractAuthParam(authHeader: String, paramName: String): String? {
        val regex = Regex("""$paramName="([^"]+)"""")
        return regex.find(authHeader)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    /**
     * Provede HTTP request na blob URL s autentizací.
     * Řeší 401 → token → retry stejně jako executeWithAuth,
     * ale vrací response (kterou caller musí .close()).
     */
    private fun executeLayerRequest(imageRef: DockerImageRef, url: String): okhttp3.Response {
        val token = getOrObtainToken(imageRef)

        val requestBuilder = Request.Builder().url(url)
        if (token != null) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()

        if (response.code == 401 && token == null) {
            // První pokus – získat token a retry
            Log.d("DockerRegistry", "Got 401 for layer, obtaining token…")
            val newToken = obtainTokenFromChallenge(imageRef, response)
            response.close()
            val retryReq = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $newToken")
                .build()
            return httpClient.newCall(retryReq).execute()
        }

        if (response.code == 401 && token != null) {
            // Token byl neplatný – refresh
            Log.d("DockerRegistry", "Got 401 with cached token, refreshing…")
            invalidateToken(imageRef.authScope)
            response.close()
            val newToken = getOrObtainToken(imageRef)!!
            val retryReq = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $newToken")
                .build()
            return httpClient.newCall(retryReq).execute()
        }

        if (!response.isSuccessful) {
            response.close()
            throw IOException("Failed to download layer: ${response.code}")
        }

        return response
    }

    /**
     * Parsuje manifest podle mediaType.
     * Podporuje:
     *   - Docker V2 manifest (application/vnd.docker.distribution.manifest.v2+json)
     *   - OCI manifest (application/vnd.oci.image.manifest.v1+json)
     *   - Docker manifest list (application/vnd.docker.distribution.manifest.list.v2+json)
     *   - OCI index (application/vnd.oci.image.index.v1+json)
     */
    private fun parseManifest(imageRef: DockerImageRef, json: JSONObject): DockerManifest {
        val mediaType = json.optString("mediaType", "")
        Log.d("DockerRegistry", "Parsing manifest, mediaType=$mediaType")

        return when {
            // Single manifest (Docker V2 nebo OCI)
            mediaType.contains("manifest.v2") || mediaType.contains("oci.image.manifest") ||
            (!mediaType.contains("list") && !mediaType.contains("index") && json.has("layers")) -> {
                parseSingleManifest(json)
            }
            // Manifest list nebo OCI index
            mediaType.contains("manifest.list") || mediaType.contains("oci.image.index") ||
            json.has("manifests") -> {
                resolveManifestList(imageRef, json)
            }
            // Fallback: zkusit jestli náhodou nemá "layers"
            json.has("layers") -> {
                parseSingleManifest(json)
            }
            else -> {
                throw IOException(
                    "Unknown manifest format (mediaType=$mediaType). " +
                    "Image may use an unsupported manifest schema or does not exist."
                )
            }
        }
    }

    private fun parseSingleManifest(json: JSONObject): DockerManifest {
        val layers = mutableListOf<DockerLayer>()
        val config = if (json.has("config")) {
            val configObj = json.getJSONObject("config")
            DockerLayer(
                digest = configObj.getString("digest"),
                size = configObj.getLong("size"),
                mediaType = configObj.optString("mediaType", "")
            )
        } else null

        val layersArray = json.getJSONArray("layers")
        for (i in 0 until layersArray.length()) {
            val layerObj = layersArray.getJSONObject(i)
            layers.add(
                DockerLayer(
                    digest = layerObj.getString("digest"),
                    size = layerObj.getLong("size"),
                    mediaType = layerObj.optString("mediaType", "")
                )
            )
        }

        Log.i("DockerRegistry", "Single manifest has ${layers.size} layers")
        return DockerManifest(layers = layers, config = config)
    }

    /**
     * Rozhodne manifest list / OCI index:
     * Vybere manifest podle architektury zařízení (arm64 > amd64),
     * stáhne ho rekurzivně a vrátí jeho single manifest.
     */
    private fun resolveManifestList(imageRef: DockerImageRef, json: JSONObject): DockerManifest {
        val manifestsArray = json.getJSONArray("manifests")
        Log.i("DockerRegistry", "Manifest list has ${manifestsArray.length()} entries")

        // Zjistit cílovou architekturu
        val targetArch = detectTargetArchitecture()
        Log.d("DockerRegistry", "Target architecture for manifest selection: $targetArch")

        // Hledat manifest pro cílovou architekturu (linux)
        var selectedDigest: String? = null
        for (i in 0 until manifestsArray.length()) {
            val entry = manifestsArray.getJSONObject(i)
            val platform = entry.optJSONObject("platform")
            if (platform != null) {
                val arch = platform.optString("architecture", "")
                val os = platform.optString("os", "")
                if (os == "linux" && arch == targetArch) {
                    selectedDigest = entry.getString("digest")
                    Log.d("DockerRegistry", "Found matching manifest: $selectedDigest ($targetArch)")
                    break
                }
            }
        }

        // Fallback na první manifest
        if (selectedDigest == null && manifestsArray.length() > 0) {
            selectedDigest = manifestsArray.getJSONObject(0).getString("digest")
            Log.w("DockerRegistry", "No match for $targetArch, falling back to first manifest: $selectedDigest")
        }

        val digest = selectedDigest
            ?: throw IOException("No suitable manifest found in manifest list (target=$targetArch, count=${manifestsArray.length()})")

        // Stáhnout konkrétní manifest podle digestu
        val resolvedRef = DockerImageRef(
            namespace = imageRef.namespace,
            repository = imageRef.repository,
            tag = "",
            digest = digest,
            registryHost = imageRef.registryHost
        )

        Log.i("DockerRegistry", "Resolving manifest list → fetching single manifest by digest: $digest")
        return fetchManifest(resolvedRef)
    }

    /**
     * Detekuje CPU architekturu zařízení pro výběr správného manifestu.
     * Priorita: arm64 > arm > amd64
     */
    private fun detectTargetArchitecture(): String {
        val arch = System.getProperty("os.arch")?.lowercase() ?: ""
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            arch.contains("arm") -> "arm"
            arch.contains("x86_64") || arch.contains("amd64") -> "amd64"
            arch.contains("x86") || arch.contains("i386") || arch.contains("i686") -> "386"
            else -> {
                Log.w("DockerRegistry", "Unknown architecture '$arch', defaulting to amd64")
                "amd64"
            }
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 256 * 1024 // 256KB
        private const val CONNECT_TIMEOUT_SEC = 30L
        // Mobilní sítě + velké vrstvy (ubuntu ~40 MB gzip): 60s stačilo jen na
        // rychlém Wi-Fi; pomalé LTE padalo na read timeout uprostřed vrstvy.
        private const val READ_TIMEOUT_SEC = 180L

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()
        }
    }
}
