package com.linux_core.core

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers.IO

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

class DockerRegistryClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    fun fetchManifest(imageRef: DockerImageRef): DockerManifest {
        val url = imageRef.manifestUrl("application/vnd.docker.distribution.manifest.v2+json")
        Log.i("DockerRegistry", "Fetching manifest: $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.docker.distribution.manifest.v2+json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch manifest: ${response.code} ${response.message}")
            }

            val body = response.body?.string() ?: throw IOException("Empty manifest response")
            val json = JSONObject(body)

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

            Log.i("DockerRegistry", "Manifest has ${layers.size} layers")
            return DockerManifest(layers = layers, config = config)
        }
    }

    fun downloadLayer(imageRef: DockerImageRef, layer: DockerLayer): Flow<ByteArray> = flow {
        val url = imageRef.blobUrl(layer.digest)
        Log.i("DockerRegistry", "Downloading layer: ${layer.digest} ($url)")

        val request = Request.Builder()
            .url(url)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download layer ${layer.digest}: ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty layer body")
            val totalBytes = body.contentLength()
            var downloaded = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var lastEmitMs = 0L

            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    downloaded += read
                    emit(buffer.copyOf(read))

                    // Emit progress every 500ms
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= 500 && totalBytes > 0) {
                        val progress = ((downloaded * 100) / totalBytes).toInt()
                        Log.d("DockerRegistry", "Layer download: $progress%")
                        lastEmitMs = now
                    }
                }
            }

            Log.i("DockerRegistry", "Layer downloaded: ${downloaded} bytes")
        }
    }.flowOn(IO)

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 256 * 1024 // 256KB
        private const val CONNECT_TIMEOUT_SEC = 30L
        private const val READ_TIMEOUT_SEC = 60L

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()
        }
    }
}
